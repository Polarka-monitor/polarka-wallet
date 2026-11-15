package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Planner
import north.core.Qrh
import north.core.Unspent
import north.core.Words
import north.node.ServerClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Reviewer's tests (independent review of 0.6.0). Not part of the author's suite. */
@RunWith(RobolectricTestRunner::class)
class ReviewerServerTest {
    private val coin = 100_000_000L
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-reviewer-").toFile()
        Stores.open = { setups }
        Stores.wallet = { wallets }
        Stores.words = { vault() }
    }

    @After
    fun restore() {
        Stores.open = Stores.default
        Stores.wallet = Stores.defaultWallet
        Stores.words = Stores.defaultWords
        dir.deleteRecursively()
    }

    private class Bench(val node: ServerNode, val server: StandIn) : AutoCloseable {
        val relay = ServerRelay(server.url)
        val direct = ServerClient(server.url, maxWaitMs = 0)
        fun setup() = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
        fun held(address: String): Long = direct.utxos(listOf(address)).utxos.sumOf { it.amountSat }
        override fun close() { relay.close(); server.close(); node.close() }
    }

    private fun bench(): Bench {
        val node = ServerNode.start()
        return try { Bench(node, StandIn.start(node)) } catch (e: Throwable) { node.close(); throw e }
    }

    private fun until(what: String, millis: Long = 120_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            Thread.sleep(15)
        }
    }

    private fun settled(model: WalletModel) = repeat(3) {
        until("nothing in progress") { !model.state.busy && !model.state.sending }
        Thread.sleep(40)
    }

    private fun opened(): WalletModel {
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        until("the first look") { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
        return model
    }

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)
    private fun script(address: String) = Qrh.scriptForAddress(Chain.REGTEST, address)
    private fun key(c: Unspent) = c.txid + ":" + c.vout

    /**
     * A hostile server keeps the signed transaction it is given at /validate, says "no", and the app forgets
     * the payment. Next time the server hides that coin, the app builds a second payment on another coin, and
     * the server then hands both to the network: the shop is paid twice.
     */
    @Test
    fun aServerThatKeepsWhatItIsAskedToCheckMakesTheWalletPayTwice() {
        bench().use { b ->
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(1, wallet.address(0, 1))
            b.node.mine(100, miner)
            setups.value = b.setup()
            val model = opened()

            var kept: String? = null
            b.relay.instead = { path, body ->
                if (path == "/v1/transactions/validate" && kept == null) {
                    kept = Json.parseToJsonElement(body).let { (it as JsonObject)["transaction_hex"]!!.jsonPrimitive.content }
                    200 to "{\"allowed\":false,\"rejection_reason\":\"min relay fee not met\"}"
                } else null
            }
            model.prepare(shop, "5", false, 2)
            until("worked out") { !model.state.sending }
            val first = assertNotNull(model.state.draft).plan
            model.confirmSend(owner)
            until("refused") { !model.state.sending }
            settled(model)
            assertEquals(Problem.Kind.REJECTED, model.state.sendProblem?.kind)
            assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty(), "the app has forgotten the first payment")
            assertNotNull(kept)

            // the server hides the coin of the first payment
            val hidden = first.inputs.map { key(it) }.toSet()
            b.relay.rewrite = { path, answer ->
                if (path != "/v1/utxos") answer else {
                    val o = Json.parseToJsonElement(answer) as JsonObject
                    val left = (o["utxos"] as JsonArray).filter { u ->
                        val m = u as JsonObject
                        (m["transaction_id"]!!.jsonPrimitive.content + ":" + m["output_index"]!!.jsonPrimitive.content) !in hidden
                    }
                    JsonObject(o + ("utxos" to JsonArray(left))).toString()
                }
            }
            model.refresh()
            settled(model)
            model.dropDraft()
            model.prepare(shop, "5", false, 2)
            until("worked out again") { !model.state.sending }
            val second = assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}").plan
            assertTrue(second.inputs.none { key(it) in hidden }, "the second payment does not stand against the first")
            model.confirmSend(owner)
            until("sent") { !model.state.sending }
            settled(model)
            // and the server now passes on what it kept
            b.direct.broadcast(kept!!)
            b.node.mine(1, miner)
            assertEquals(10 * coin, b.held(shop), "the shop was paid twice")
        }
    }

    /**
     * A payment Q that is in a block, made to stand against an earlier payment P that did not go through and
     * spends only coins of P. Its inputs sit in two parts of the addresses and one part gets back more than it
     * put in, so the server's lines do not give a history line for it. ServerEngine.judge then writes
     * ANOTHER over Q's own id in spentBy (P is judged after Q), and the model calls Q beaten.
     */
    @Test
    fun aPaymentInABlockIsCalledBeaten() {
        bench().use { b ->
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val other = fresh()
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))                    // part A
            b.node.mine(1, wallet.address(0, 60))                   // part B
            b.node.mine(100, miner)
            val engine = ServerEngine(Chain.REGTEST, emptyList(), ServerClient(b.server.url, maxWaitMs = 0))
            val base = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, receiveIndex = 61, changeIndex = 61, lockOnOpen = false, lookTo = 100)
            val coins = engine.fetch(base).coins
            assertEquals(2, coins.size)
            val p = Planner.pay(coins, script(other.address(0, 0)), 20 * coin, 2, wallet.script(1, 61), must = coins)
            val pSigned = p.sign(wallet)                            // never handed over
            val q = Planner.pay(coins, script(other.address(0, 1)), 10 * coin, 2, wallet.script(1, 60), must = coins)
            val qSigned = q.sign(wallet)
            assertEquals(qSigned.txid, b.direct.broadcast(qSigned.hex))
            b.node.mine(1, miner)
            val ins = coins.map { key(it) }.toSet()
            assertEquals(ins, q.inputs.map { key(it) }.toSet())
            val info = base.copy(
                pending = listOf(Pending(qSigned.txid, q.sentSat, q.feeSat, ins, unsure = false, atMillis = 1)),
                dropped = listOf(Dropped(pSigned.txid, p.sentSat, p.feeSat, ins, atMillis = 1)),
            )
            val seen = engine.fetch(info)
            assertTrue(seen.moves.none { it.txid == qSigned.txid }, "Q has no history line")
            assertTrue(qSigned.txid !in seen.held)
            val control = engine.fetch(info.copy(dropped = emptyList()))
            println("REVIEWER control spentBy=${control.spentBy} ; with P dropped spentBy=${seen.spentBy} ; whole=${seen.historyWhole}")
            assertTrue(control.spentBy.values.all { it == qSigned.txid })
            assertTrue(seen.spentBy.values.all { it == "another transaction" }, "Q's id was overwritten")

            // and the model, as the app would run it
            vault().let { it.store(it.encryptor(), words) }
            wallets.value = info
            setups.value = Setup(b.server.url, "", "", Chain.REGTEST, emptyList(), server = true)
            val model = opened()
            model.goneAfterMillis = 0
            settled(model)
            model.refresh()
            settled(model)
            println("REVIEWER notice=${model.state.notice} pending=${model.state.pending.map { it.txid }} dropped=${model.state.dropped.map { it.txid }}")
            // the model recovers: P is let go in the first look, and the next look sees Q's own id again
            assertEquals(null, model.state.notice)
            assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty())
        }
    }

    /**
     * A payment that did not go through (the server held it) is remembered, and the next payment should be
     * built on one of its coins. The server then leaves that coin out of its list and says it does not know
     * the payment: the app concludes that a transaction in a block spent the coin ("another transaction"),
     * lets the payment go without asking the owner, and the next payment no longer stands against it.
     */
    @Test
    fun aServerThatHidesACoinUndoesTheGuardAgainstASecondPayment() {
        bench().use { b ->
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(1, wallet.address(0, 1))
            b.node.mine(100, miner)
            setups.value = b.setup()
            val model = opened()

            var kept: String? = null
            b.relay.instead = { path, body ->
                when (path) {
                    "/v1/transactions/validate" -> {
                        kept = Json.parseToJsonElement(body).let { (it as JsonObject)["transaction_hex"]!!.jsonPrimitive.content }
                        null
                    }
                    "/v1/transactions/broadcast" -> 502 to "{\"message\":\"The blockchain node is unavailable or returned an invalid response.\"}"
                    "/v1/transactions/status" -> 200 to "{\"known\":false}"
                    else -> null
                }
            }
            model.prepare(shop, "5", false, 2)
            until("worked out") { !model.state.sending }
            val first = assertNotNull(model.state.draft).plan
            model.confirmSend(owner)
            until("handed over") { !model.state.sending }
            settled(model)
            assertEquals(Problem.Kind.UNSURE, model.state.sendProblem?.kind)
            b.relay.instead = { _, _ -> null }
            model.goneAfterMillis = 0
            model.refresh()
            settled(model)
            assertEquals(Notice.PAYMENT_GONE, model.state.notice)
            assertEquals(1, model.state.dropped.size)

            val hidden = first.inputs.map { key(it) }.toSet()
            b.relay.rewrite = { path, answer ->
                if (path != "/v1/utxos") answer else {
                    val o = Json.parseToJsonElement(answer) as JsonObject
                    val left = (o["utxos"] as JsonArray).filter { u ->
                        val m = u as JsonObject
                        (m["transaction_id"]!!.jsonPrimitive.content + ":" + m["output_index"]!!.jsonPrimitive.content) !in hidden
                    }
                    JsonObject(o + ("utxos" to JsonArray(left))).toString()
                }
            }
            model.refresh()
            settled(model)
            println("REVIEWER after hiding: notice=${model.state.notice} dropped=${model.state.dropped.size} spentBy=${model.state.snapshot?.spentBy}")
            assertTrue(model.state.dropped.isEmpty(), "the payment that did not go through was let go")

            model.dropDraft()
            model.prepare(shop, "5", false, 2)
            until("worked out again") { !model.state.sending }
            val draft = assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
            assertEquals(0, draft.against)
            assertTrue(draft.plan.inputs.none { key(it) in hidden })
            model.confirmSend(owner)
            until("sent") { !model.state.sending }
            settled(model)
            b.direct.broadcast(kept!!)
            b.node.mine(1, miner)
            assertEquals(10 * coin, b.held(shop), "the shop was paid twice")
        }
    }

    /**
     * Second look (d739709). The time a question may take ([withinMs]) is counted from before the wait for the
     * per-server lock, but `synchronized` waits for as long as the question that holds the lock takes. A check
     * given one second waits here eight seconds behind a slow question about the tip.
     */
    @Test
    fun aQuestionWaitsForTheLockLongerThanItsTime() {
        ServerRelay("http://127.0.0.1:1").use { relay ->
            relay.instead = { path, _ ->
                if (path == "/v1/chain/tip") 200 to "{\"height\":5}" else 200 to "{\"allowed\":true,\"rejection_reason\":null}"
            }
            relay.after = { path, _ -> if (path == "/v1/chain/tip") Thread.sleep(8000) }
            val client = ServerClient(relay.url, maxWaitMs = 0)
            val slow = kotlin.concurrent.thread { runCatching { client.tipHeight() } }
            Thread.sleep(500)
            val start = System.nanoTime()
            val result = runCatching { client.validate("00", 1000) }
            val took = (System.nanoTime() - start) / 1_000_000
            slow.join()
            println("REVIEWER lock: validate(within 1000 ms) took $took ms, result=${result.exceptionOrNull()?.message ?: result.getOrNull()}")
            assertTrue(took >= 6000, "the check kept its time limit ($took ms)")
        }
    }

    /**
     * An honest server. A payment D did not go through and is remembered; the next payment P is built on D's
     * coin and waits for a block. While P only waits, the server leaves D's coin out of its list (a waiting
     * transaction spends it), D is not known, and the app lets D go as if a block had spent its coin.
     */
    @Test
    fun aPaymentThatDidNotGoThroughIsLetGoWhileItsRivalOnlyWaits() {
        bench().use { b ->
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(1, wallet.address(0, 1))
            b.node.mine(100, miner)
            setups.value = b.setup()
            val model = opened()

            b.server.set("{\"hold\":true}")
            model.prepare(shop, "5", false, 2)
            until("worked out") { !model.state.sending }
            model.confirmSend(owner)
            until("handed over") { !model.state.sending }
            settled(model)
            assertEquals(Problem.Kind.UNSURE, model.state.sendProblem?.kind)
            b.server.set("{\"hold\":false}")
            model.goneAfterMillis = 0
            model.refresh()
            settled(model)
            assertEquals(Notice.PAYMENT_GONE, model.state.notice)
            val d = model.state.dropped.single()

            model.dropDraft()
            model.prepare(shop, "5", false, 2)
            until("worked out again") { !model.state.sending }
            assertEquals(1, assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}").against)
            model.confirmSend(owner)
            until("sent") { !model.state.sending }
            settled(model)
            assertEquals(1, b.node.mempool().size, "P waits for a block")
            model.refresh()
            settled(model)
            println("REVIEWER rival waits: pending=${model.state.pending.size} dropped=${model.state.dropped.map { it.txid }} spentBy=${model.state.snapshot?.spentBy}")
            assertTrue(model.state.dropped.none { it.txid == d.txid }, "D was let go although P is in no block")
            assertEquals(1, b.node.mempool().size)
        }
    }
}
