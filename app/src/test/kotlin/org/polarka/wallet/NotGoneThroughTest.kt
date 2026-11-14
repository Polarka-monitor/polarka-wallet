package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Words
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.4.2. "It did not go through" is said from what one node shows. The transaction of such a payment is
 * signed and stays valid while its coins are unspent, so it could still reach a block from elsewhere, and a
 * second payment made meanwhile with other coins would then pay twice.
 *
 * These tests hand a payment to a gate that keeps it away from the node (as a gateway does whose node is
 * away), let the app find that the payment did not go through, and then bring the kept transaction back.
 * The node is the official TrueNorth Core on regtest; the tests are skipped when TN_BIN does not name it.
 */
@RunWith(RobolectricTestRunner::class)
class NotGoneThroughTest {
    /**
     * Stands between the app and the node. While [keeps] is set, a transaction handed over does not reach the
     * node, and the app gets the answer of a gateway whose node is away. Every transaction handed over is kept.
     */
    private class Gate(private val target: Int) : AutoCloseable {
        @Volatile var keeps = false
        private val handed = ArrayList<String>()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { runCatching { handle(socket) } }
                }
            }
        }

        private fun handle(socket: Socket) = socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return@use
                head.append(b.toChar())
            }
            val headers = head.lines().drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val raw = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var n = 0
            while (n < raw.size) {
                val got = input.read(raw, n, raw.size - n)
                if (got < 0) break
                n += got
            }
            val call = Json.parseToJsonElement(raw.decodeToString()).jsonObject
            val method = call["method"]!!.jsonPrimitive.content
            var status = 502
            var answer = "{\"result\":null,\"error\":{\"code\":-32603,\"message\":\"the node behind the gateway did not answer\"},\"id\":\"north\"}"
            val kept = method == "sendrawtransaction" && keeps
            if (method == "sendrawtransaction") synchronized(handed) { handed.add(call["params"]!!.jsonArray[0].jsonPrimitive.content) }
            if (!kept) {
                val c = URL("http://127.0.0.1:$target/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                headers["authorization"]?.let { c.setRequestProperty("Authorization", it) }
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(raw) }
                status = c.responseCode
                answer = ((if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).decodeToString()
            }
            val out = answer.encodeToByteArray()
            s.getOutputStream().apply {
                write("HTTP/1.1 $status gate\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                write(out)
                flush()
            }
        }

        /** The transaction handed over last, as the hexadecimal text the app sent. */
        fun last(): String = synchronized(handed) { handed.last() }
        fun count(): Int = synchronized(handed) { handed.size }

        override fun close() = server.close()
    }

    /** An account with a block reward that can be spent, and a way to pay from it to a test wallet. */
    private class Bank(private val node: AuditNode, private val miner: String) {
        private val words = Words.generate()
        private val account = Account.fromWords(words, Chain.REGTEST)
        private val engine = Engine(node.setup())

        init {
            node.mine(1, account.address(0, 0))
            node.mine(100, miner)
        }

        fun give(to: String, coins: Long) {
            val info = WalletInfo(Chain.REGTEST, account.watchKey().xpub)
            engine.send(info.xpub, engine.plan(info, to, coins * 100_000_000L, 2), words)
            node.mine(1, miner)
        }
    }

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-not-gone-through-").toFile()
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

    private fun until(what: String, millis: Long = 120_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            Thread.sleep(15)
        }
    }

    private fun settled(model: WalletModel) {
        repeat(3) {
            until("nothing in progress") { !model.state.busy && !model.state.sending }
            Thread.sleep(40)
        }
    }

    private fun opened(): WalletModel {
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        until("the first look at the node") { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
        return model
    }

    private fun workedOut(model: WalletModel, to: String, amount: String): Draft? {
        until("no look at the node in progress") { !model.state.busy }
        model.dropDraft()
        model.prepare(to, amount, false, 2)
        until("the payment is worked out") { !model.state.sending }
        return model.state.draft
    }

    private fun pay(model: WalletModel, to: String, amount: String): Problem? {
        if (workedOut(model, to, amount) == null) return model.state.sendProblem
        model.confirmSend(owner)
        until("the payment is sent or refused") { !model.state.sending }
        return model.state.sendProblem
    }

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)
    private fun coinsOf(draft: Draft): Set<String> = draft.plan.inputs.map { it.txid + ":" + it.vout }.toSet()
    private fun refused(node: AuditNode, hex: String): Boolean = runCatching { node.cli("sendrawtransaction", hex) }.isFailure

    /**
     * A wallet with [coins] (whole NORTH, one coin on each of its first addresses) pays five to [shop] through
     * a gate that keeps the transaction from the node. The app then finds that the payment did not go through.
     */
    private fun lostPayment(node: AuditNode, gate: Gate, bank: Bank, words: List<String>, shop: String, coins: List<Long>): WalletModel {
        val wallet = Account.fromWords(words, Chain.REGTEST)
        coins.forEachIndexed { i, c -> bank.give(wallet.address(0, i), c) }
        setups.value = node.setup(port = gate.port)
        wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
        vault().let { it.store(it.encryptor(), words) }
        val model = opened()
        gate.keeps = true
        val problem = pay(model, shop, "5")
        gate.keeps = false
        assertEquals(Problem.Kind.UNSURE, problem?.kind, "the outcome of the payment is not known")
        assertEquals(1, model.state.pending.size)
        assertTrue(model.state.pending[0].unsure)
        assertTrue(node.mempool().isEmpty(), "the node never got the transaction")
        settled(model)
        assertEquals(coins.max() * 100_000_000L, model.state.asideSat, "the coin of a payment whose outcome is not known is not shown as one that can be spent")
        // a minute later (the minute is switched off here) two looks in a row decide
        model.goneAfterMillis = 0
        model.refresh()
        settled(model)
        assertEquals(Notice.PAYMENT_GONE, model.state.notice)
        assertEquals("5.00000000", model.state.noticeDetail)
        assertTrue(model.state.pending.isEmpty())
        assertEquals(1, model.state.dropped.size, "the payment is kept as one that did not go through")
        assertEquals(0L, model.state.asideSat, "its coin is free again")
        until("the wallet's file is written") { wallets.value!!.dropped.size == 1 && wallets.value!!.pending.isEmpty() }
        return model
    }

    /**
     * The case that pays twice without the safeguard: after the payment did not go through, a larger coin
     * arrives. Left to itself, the next payment would take the larger coin, and the old transaction could
     * reach a block beside it. The next payment is built on the coin of the old one instead, and the node has
     * no room for the old transaction beside it, nor after it.
     */
    @Test
    fun theNextPaymentIsBuiltOnACoinOfTheOneThatDidNotGoThrough() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Gate(node.port).use { gate ->
                val miner = fresh().address(0, 0)
                val bank = Bank(node, miner)
                val words = Words.generate()
                val shop = fresh().address(0, 0)
                val model = lostPayment(node, gate, bank, words, shop, listOf(10))
                val lost = model.state.dropped.single()
                val lostHex = gate.last()
                bank.give(Account.fromWords(words, Chain.REGTEST).address(0, 1), 50)
                model.refresh()
                settled(model)
                assertEquals(60_00000000L, model.state.snapshot!!.spendableSat)

                val draft = assertNotNull(workedOut(model, shop, "5"), "problem: ${model.state.sendProblem}")
                assertEquals(1, draft.against)
                assertEquals(lost.inputs, coinsOf(draft), "the new payment spends the coin of the old one, not the larger coin")
                model.confirmSend(owner)
                settled(model)
                assertNull(model.state.sendProblem)
                assertEquals(1, model.state.pending.size)
                assertEquals(1, node.mempool().size)
                assertEquals(1, model.state.dropped.size, "the old one stays until a block settles it")

                assertTrue(refused(node, lostHex), "the node took the old payment beside the new one")
                node.mine(1, miner)
                assertTrue(refused(node, lostHex), "the node took the old payment after the new one was in a block")
                model.refresh()
                settled(model)
                assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty(), "the block has settled both")
                assertEquals(5_00000000L, node.held(shop), "the shop was paid once")
                until("the wallet's file is written") { wallets.value!!.dropped.isEmpty() && wallets.value!!.pending.isEmpty() }
            }
        }
    }

    /**
     * The old transaction reaches the node after all. The app sees that the node holds it again: the payment
     * waits for a block as any other, its coin is not offered for another payment, and the shop is paid once.
     */
    @Test
    fun aPaymentThatTheNodeHoldsAgainWaitsForABlockAgain() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Gate(node.port).use { gate ->
                val miner = fresh().address(0, 0)
                val shop = fresh().address(0, 0)
                val model = lostPayment(node, gate, Bank(node, miner), Words.generate(), shop, listOf(10))
                val lost = model.state.dropped.single()
                node.cli("sendrawtransaction", gate.last())
                model.refresh()
                settled(model)
                assertEquals(Notice.PAYMENT_BACK, model.state.notice)
                assertEquals("5.00000000", model.state.noticeDetail)
                assertTrue(model.state.dropped.isEmpty())
                assertEquals(listOf(lost.txid), model.state.pending.map { it.txid })
                assertFalse(model.state.pending[0].unsure)
                assertEquals(0L, model.state.snapshot!!.spendableSat, "its coin is tied up again")
                assertEquals(Problem.Kind.NOTHING_TO_SPEND, pay(model, shop, "5")?.kind, "nothing is paid beside a payment that waits")

                node.mine(1, miner)
                model.refresh()
                settled(model)
                assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty())
                assertEquals(5_00000000L, node.held(shop), "the shop was paid once")
            }
        }
    }

    /** The old transaction reaches a block without the app having seen it wait. The owner is told that it arrived after all. */
    @Test
    fun aPaymentThatReachesABlockAfterAllIsToldAsArrived() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Gate(node.port).use { gate ->
                val miner = fresh().address(0, 0)
                val shop = fresh().address(0, 0)
                val model = lostPayment(node, gate, Bank(node, miner), Words.generate(), shop, listOf(10))
                node.cli("sendrawtransaction", gate.last())
                node.mine(1, miner)
                model.refresh()
                settled(model)
                assertEquals(Notice.PAYMENT_ARRIVED, model.state.notice)
                assertEquals("5.00000000", model.state.noticeDetail)
                assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty())
                assertEquals(5_00000000L, node.held(shop))
                until("the wallet's file is written") { wallets.value!!.dropped.isEmpty() }
            }
        }
    }

    /**
     * A second payment was worked out while the first was still thought to be on its way; it does not spend a
     * coin of the first. When the first is then found not to have gone through, the second is not sent as it
     * stands. Worked out again, it stands against the first. Only the owner can let the first one go. And a
     * payment that did not go through and has no coin the app could spend keeps the wallet from paying beside it.
     */
    @Test
    fun aPaymentWorkedOutEarlierIsNotSentAndOnlyTheOwnerLetsTheOldOneGo() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Gate(node.port).use { gate ->
                val miner = fresh().address(0, 0)
                val bank = Bank(node, miner)
                val words = Words.generate()
                val wallet = Account.fromWords(words, Chain.REGTEST)
                val shop = fresh().address(0, 0)
                val other = fresh().address(0, 0)
                bank.give(wallet.address(0, 0), 10)
                bank.give(wallet.address(0, 1), 50)
                setups.value = node.setup(port = gate.port)
                wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                vault().let { it.store(it.encryptor(), words) }
                val model = opened()
                gate.keeps = true
                assertEquals(Problem.Kind.UNSURE, pay(model, shop, "5")?.kind)          // it takes the coin of fifty
                gate.keeps = false
                settled(model)
                val first = model.state.pending.single()

                val early = assertNotNull(workedOut(model, other, "3"), "problem: ${model.state.sendProblem}")
                assertEquals(0, early.against)
                assertTrue(coinsOf(early).none { it in first.inputs }, "a coin of a waiting payment is not offered")
                model.goneAfterMillis = 0
                model.refresh()
                settled(model)
                assertEquals(listOf(first.txid), model.state.dropped.map { it.txid })
                assertNotNull(model.state.draft, "the payment worked out earlier is still on the screen")
                val handed = gate.count()
                model.confirmSend(owner)
                settled(model)
                assertEquals(Problem.Kind.EARLIER_PAYMENT, model.state.sendProblem?.kind)
                assertNull(model.state.draft)
                assertEquals(handed, gate.count(), "nothing was handed to the node")
                assertTrue(model.state.pending.isEmpty())

                val again = assertNotNull(workedOut(model, other, "3"), "problem: ${model.state.sendProblem}")
                assertEquals(1, again.against)
                assertEquals(first.inputs, coinsOf(again))
                model.dropDraft()

                owner.there = false
                val asked = owner.shows
                model.forgetDropped(first.txid, owner)
                until("the owner was asked") { owner.shows == asked + 1 }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, model.state.dropped.size, "somebody who is not the owner let the payment go")
                owner.there = true
                model.forgetDropped(first.txid, owner)
                until("the old payment is let go") { model.state.dropped.isEmpty() }
                assertEquals(0, assertNotNull(workedOut(model, other, "3")).against)
                model.dropDraft()
                until("the wallet's file is written") { wallets.value!!.dropped.isEmpty() }

                val ghost = Dropped("ab".repeat(32), 1_00000000L, 300, setOf("cd".repeat(32) + ":0"), 1)
                wallets.value = wallets.value!!.copy(dropped = listOf(ghost))
                val reopened = opened()
                assertEquals(Problem.Kind.EARLIER_PAYMENT, pay(reopened, other, "3")?.kind)
                reopened.refresh()
                settled(reopened)
                assertEquals(listOf(ghost), reopened.state.dropped, "nothing shows that it cannot arrive, so it stays")
                assertEquals(handed, gate.count(), "nothing was handed to the node")
            }
        }
    }
}
