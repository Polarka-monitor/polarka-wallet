package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Words
import north.node.ServerClient
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
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sixth look of the independent reviewer (0.6.0, `review/independent-review-0.6.0`).
 *
 * Whoever is handed a signed transaction can keep it and send it later, whatever they answered. A wallet
 * server is handed every payment, and so is a public node. The reviewer showed two ways in which such a
 * party could make the wallet pay the same thing twice (H1, H2), and one in which the wallet let go of its
 * own safeguard with an honest server (M1). His tests ended with a shop that was paid twice. The tests here
 * tell the same stories and end with a shop that was paid once.
 *
 * Every test runs against a real TrueNorth Core on regtest. The dishonest part is played by a relay in front
 * of the stand-in for a wallet server, or by a door in front of the node. Skipped when TN_BIN does not name
 * the node's programs or python3 is missing.
 */
@RunWith(RobolectricTestRunner::class)
class SixthLookTest {
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
        dir = Files.createTempDirectory("polarka-sixth-look-").toFile()
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

    /** A node, the stand-in server on it and a relay in front of that. The app is given the relay's address. */
    private class Bench(val node: ServerNode, val server: StandIn) : AutoCloseable {
        val relay = ServerRelay(server.url)
        val direct = ServerClient(server.url, maxWaitMs = 0)
        fun setup() = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
        fun held(address: String): Long = direct.utxos(listOf(address)).utxos.sumOf { it.amountSat }

        /** From now on the relay leaves these coins ("txid:n") out of the server's list of coins. */
        fun hide(coins: Set<String>) {
            relay.rewrite = { path, answer ->
                if (path != "/v1/utxos") answer else {
                    val whole = Json.parseToJsonElement(answer).jsonObject
                    val left = whole["utxos"]!!.jsonArray.filter { u ->
                        val c = u.jsonObject
                        c["transaction_id"]!!.jsonPrimitive.content + ":" + c["output_index"]!!.jsonPrimitive.content !in coins
                    }
                    JsonObject(whole + ("utxos" to JsonArray(left))).toString()
                }
            }
        }

        fun showAll() {
            relay.rewrite = { _, answer -> answer }
        }

        override fun close() {
            relay.close()
            server.close()
            node.close()
        }
    }

    private fun bench(): Bench {
        val node = ServerNode.start()
        return try {
            Bench(node, StandIn.start(node))
        } catch (e: Throwable) {
            node.close()
            throw e
        }
    }

    /**
     * Stands in front of a node as the gateway of a public node does: it logs in to the node itself, and the
     * app sends no login. [instead] may answer in the node's place, and then the node never hears of the call.
     */
    private class Door(private val target: Int, private val login: String) : AutoCloseable {
        @Volatile var instead: (method: String, params: JsonArray) -> Pair<Int, String>? = { _, _ -> null }
        private val methods = ArrayList<String>()
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
            check("authorization:" !in head.toString().lowercase()) { "a login was sent to a public node" }
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
            synchronized(methods) { methods.add(method) }
            var (status, answer) = instead(method, call["params"]!!.jsonArray) ?: (0 to "")
            if (status == 0) {
                val c = URL("http://127.0.0.1:$target/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(login.encodeToByteArray()))
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(raw) }
                status = c.responseCode
                answer = ((if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).decodeToString()
            }
            val out = answer.encodeToByteArray()
            s.getOutputStream().apply {
                write("HTTP/1.1 $status door\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                write(out)
                flush()
            }
        }

        fun count(method: String): Int = synchronized(methods) { methods.count { it == method } }

        override fun close() = server.close()
    }

    /** What a node answers to "would you take this transaction" when it would not. */
    private fun no(reason: String) =
        200 to "{\"result\":[{\"txid\":\"${"0".repeat(64)}\",\"allowed\":false,\"reject-reason\":\"$reason\"}],\"error\":null,\"id\":\"north\"}"

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

    private fun looked(model: WalletModel, times: Int = 1) = repeat(times) {
        model.refresh()
        settled(model)
    }

    private fun opened(): WalletModel {
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        until("the first look") { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
        return model
    }

    private fun workedOut(model: WalletModel, to: String, amount: String): Draft? {
        until("no look in progress") { !model.state.busy }
        model.dropDraft()
        model.prepare(to, amount, false, 2)
        until("the payment is worked out") { !model.state.sending }
        return model.state.draft
    }

    private fun pay(model: WalletModel, to: String, amount: String): Problem? {
        if (workedOut(model, to, amount) == null) return model.state.sendProblem
        model.confirmSend(owner)
        until("the payment is sent or refused") { !model.state.sending }
        settled(model)
        return model.state.sendProblem
    }

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)

    /** Puts a wallet with new words into the phone's stores, as if it had been made there. */
    private fun walletInThePhone(): Account {
        val words = Words.generate()
        val account = Account.fromWords(words, Chain.REGTEST)
        wallets.value = WalletInfo(Chain.REGTEST, account.watchKey().xpub, lockOnOpen = false)
        vault().let { it.store(it.encryptor(), words) }
        return account
    }

    /**
     * H1. The server keeps the transaction it is asked to check and says that its node will not take it. The
     * app used to forget such a payment. It is kept now, as one that did not go through. When the server then
     * leaves the coin of that payment out of its list, no payment is made at all. When it shows the coin, the
     * next payment is built on it, and the node has no room for the transaction that the server kept.
     */
    @Test
    fun aPaymentThatAServerRefusedIsNotForgotten() {
        bench().use { b ->
            val wallet = walletInThePhone()
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(1, wallet.address(0, 1))
            b.node.mine(100, miner)
            setups.value = b.setup()
            val model = opened()
            assertNull(model.state.problem)

            var kept: String? = null
            b.relay.instead = { path, body ->
                if (path == "/v1/transactions/validate" && kept == null) {
                    kept = Json.parseToJsonElement(body).jsonObject["transaction_hex"]!!.jsonPrimitive.content
                    200 to "{\"allowed\":false,\"rejection_reason\":\"min relay fee not met\"}"
                } else null
            }
            val told = assertNotNull(pay(model, shop, "5"))
            assertEquals(Problem.Kind.REFUSED_KEPT to "min relay fee not met", told.kind to told.detail)
            assertNotNull(kept)
            assertTrue("/v1/transactions/broadcast" !in b.relay.paths, "a refused payment was handed over for sending")
            assertTrue(model.state.pending.isEmpty())
            val refused = model.state.dropped.single()
            assertNull(model.state.draft)
            assertEquals(1, model.state.wallet!!.changeIndex, "the change key of a transaction that could still arrive stays used")
            until("the wallet's file is written") { wallets.value!!.dropped.map { it.txid } == listOf(refused.txid) && wallets.value!!.pending.isEmpty() }

            // The coin of that payment is left out of the list, look after look. Nothing is concluded from that:
            // the payment stays, and no payment is made that does not stand against it.
            b.relay.instead = { _, _ -> null }
            b.hide(refused.inputs)
            model.goneAfterMillis = 0
            looked(model, 3)
            assertEquals(listOf(refused.txid), model.state.dropped.map { it.txid }, "the payment was let go because its coin is not listed")
            assertTrue(model.state.snapshot!!.spentBy.isEmpty())
            assertNull(workedOut(model, shop, "5"))
            assertEquals(Problem.Kind.EARLIER_PAYMENT, model.state.sendProblem?.kind)

            // shown the coin again, the next payment is built on it
            b.showAll()
            looked(model)
            val second = assertNotNull(workedOut(model, shop, "5"), "problem: ${model.state.sendProblem}")
            assertEquals(1, second.against)
            assertTrue(second.plan.inputs.any { it.txid + ":" + it.vout in refused.inputs })
            model.confirmSend(owner)
            until("sent") { !model.state.sending }
            settled(model)
            assertNull(model.state.sendProblem)
            assertEquals(1, b.node.mempool().size)

            // the server now passes on what it kept: the node has no room for it, not beside the second payment and not after its block
            assertFails { b.direct.broadcast(kept!!) }
            b.node.mine(1, miner)
            assertFails { b.direct.broadcast(kept!!) }
            assertEquals(5 * coin, b.held(shop), "the shop was paid once")
            looked(model)
            assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty(), "the block has settled both")
        }
    }

    /**
     * H2. A hand-over fails and the server says that it does not know the payment; the app keeps it as one
     * that did not go through. The server then leaves the coin of that payment out of its list. The app used
     * to read that as "a block has spent the coin" and let the payment go. It concludes nothing now.
     */
    @Test
    fun aCoinThatAServerLeavesOutDoesNotUndoTheGuard() {
        bench().use { b ->
            val wallet = walletInThePhone()
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
                        kept = Json.parseToJsonElement(body).jsonObject["transaction_hex"]!!.jsonPrimitive.content
                        null
                    }
                    "/v1/transactions/broadcast" -> 502 to "{\"message\":\"The blockchain node is unavailable or returned an invalid response.\"}"
                    "/v1/transactions/status" -> 200 to "{\"known\":false}"
                    else -> null
                }
            }
            assertEquals(Problem.Kind.UNSURE, pay(model, shop, "5")?.kind)
            assertNotNull(kept)
            b.relay.instead = { _, _ -> null }
            model.goneAfterMillis = 0
            looked(model)
            assertEquals(Notice.PAYMENT_GONE, model.state.notice)
            val gone = model.state.dropped.single()
            model.note(null)

            b.hide(gone.inputs)
            looked(model, 3)
            assertEquals(listOf(gone.txid), model.state.dropped.map { it.txid }, "the payment was let go because its coin is not listed")
            assertNull(model.state.notice, "the owner was told that the payment is beaten")
            assertTrue(model.state.snapshot!!.spentBy.isEmpty())
            assertNull(workedOut(model, shop, "5"))
            assertEquals(Problem.Kind.EARLIER_PAYMENT, model.state.sendProblem?.kind)

            b.showAll()
            looked(model)
            val second = assertNotNull(workedOut(model, shop, "5"), "problem: ${model.state.sendProblem}")
            assertEquals(1, second.against)
            assertTrue(second.plan.inputs.any { it.txid + ":" + it.vout in gone.inputs })
            model.confirmSend(owner)
            until("sent") { !model.state.sending }
            settled(model)
            assertNull(model.state.sendProblem)
            assertFails { b.direct.broadcast(kept!!) }
            b.node.mine(1, miner)
            assertEquals(5 * coin, b.held(shop), "the shop was paid once")
        }
    }

    /**
     * M1, with an honest server. A payment did not go through; the next one is built on its coin and waits for
     * a block. While it only waits, the first one is still remembered, and nothing stands in the way of
     * further payments. The first one is let go when the second is in a block.
     */
    @Test
    fun aPaymentThatDidNotGoThroughIsKeptWhileTheOneBuiltOnItsCoinOnlyWaits() {
        bench().use { b ->
            val wallet = walletInThePhone()
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(1, wallet.address(0, 1))
            b.node.mine(100, miner)
            setups.value = b.setup()
            val model = opened()

            b.server.set("{\"hold\":true}")
            assertEquals(Problem.Kind.UNSURE, pay(model, shop, "5")?.kind)
            b.server.set("{\"hold\":false}")
            model.goneAfterMillis = 0
            looked(model)
            assertEquals(Notice.PAYMENT_GONE, model.state.notice)
            val first = model.state.dropped.single()
            model.note(null)

            val second = assertNotNull(workedOut(model, shop, "5"), "problem: ${model.state.sendProblem}")
            assertEquals(1, second.against)
            model.confirmSend(owner)
            until("sent") { !model.state.sending }
            settled(model)
            assertNull(model.state.sendProblem)
            assertEquals(1, b.node.mempool().size, "the second payment waits for a block")
            looked(model, 2)
            assertEquals(listOf(first.txid), model.state.dropped.map { it.txid }, "the first payment was let go although the second is in no block")
            assertEquals(1, model.state.pending.size)
            assertNull(model.state.notice)
            assertTrue(model.state.snapshot!!.spentBy.isEmpty())
            // the second payment stands against the first, so the first asks nothing of a third one, which goes out on the other coin
            assertTrue(model.state.wallet!!.unanswered.isEmpty())
            val third = assertNotNull(workedOut(model, shop, "7"), "problem: ${model.state.sendProblem}")
            assertEquals(0, third.against)
            assertTrue(third.plan.inputs.none { it.txid + ":" + it.vout in first.inputs })
            model.dropDraft()

            b.node.mine(1, miner)
            looked(model)
            assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty(), "the block has settled both")
            assertEquals(5 * coin, b.held(shop), "the shop was paid once")
        }
    }

    /** L2. The one refusal of the server that is not about the addresses is said as what it is. */
    @Test
    fun moreCoinsThanAServerListsAreCalledThat() {
        bench().use { b ->
            val wallet = walletInThePhone()
            b.node.mine(1, wallet.address(0, 0))
            setups.value = b.setup()
            b.relay.instead = { path, _ -> if (path == "/v1/utxos") 400 to "{\"message\":\"request exceeds the 10,000 output limit\"}" else null }
            val model = opened()
            assertEquals(Problem(Problem.Kind.TOO_MANY_COINS, "request exceeds the 10,000 output limit"), model.state.problem)
            assertNull(model.state.snapshot)
            // every other refusal of what the wallet asks is about the addresses: a server of another network, or of another kind
            b.relay.instead = { path, _ -> if (path == "/v1/utxos") 400 to "{\"message\":\"invalid address or checksum\"}" else null }
            looked(model)
            assertEquals(Problem(Problem.Kind.OTHER_CHAIN, "invalid address or checksum"), model.state.problem)
            b.relay.instead = { _, _ -> null }
            looked(model)
            assertNull(model.state.problem)
        }
    }

    /**
     * H1 on the node's side. A public node is somebody else's node, like a wallet server. It used to be shown
     * the signed transaction before the payment was written down, and its no left no trace. It now sees the
     * transaction only when the payment is in the wallet's file, and a payment it refuses is kept.
     */
    @Test
    fun aPublicNodeSeesAPaymentOnlyWhenItIsWrittenDownAndItsNoIsNotForgotten() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Door(node.port, "phone:${node.password}").use { door ->
                val wallet = walletInThePhone()
                val shop = fresh().address(0, 0)
                val miner = fresh().address(0, 0)
                node.mine(1, wallet.address(0, 0))
                node.mine(1, wallet.address(0, 1))
                node.mine(100, miner)
                setups.value = Setup("http://127.0.0.1:${door.port}/", "", "", Chain.REGTEST, emptyList())
                check(setups.value!!.isPublic)
                val model = opened()
                assertNull(model.state.problem)

                var kept: String? = null
                var written: Boolean? = null
                door.instead = { method, params ->
                    if (method == "testmempoolaccept" && kept == null) {
                        kept = params[0].jsonArray[0].jsonPrimitive.content
                        written = wallets.value!!.pending.size == 1
                        no("min relay fee not met")
                    } else null
                }
                assertNotNull(workedOut(model, shop, "5"), "problem: ${model.state.sendProblem}")
                assertEquals(0, door.count("testmempoolaccept"), "the node was shown a transaction before its owner said yes")
                model.confirmSend(owner)
                until("refused") { !model.state.sending }
                settled(model)
                assertEquals(true, written, "the public node saw the payment before it was written down")
                assertEquals(Problem.Kind.REFUSED_KEPT to "min relay fee not met", model.state.sendProblem?.let { it.kind to it.detail })
                assertEquals(0, door.count("sendrawtransaction"))
                assertTrue(model.state.pending.isEmpty())
                val refused = model.state.dropped.single()

                door.instead = { _, _ -> null }
                val second = assertNotNull(workedOut(model, shop, "5"), "problem: ${model.state.sendProblem}")
                assertEquals(1, second.against)
                assertTrue(second.plan.inputs.any { it.txid + ":" + it.vout in refused.inputs })
                model.confirmSend(owner)
                until("sent") { !model.state.sending }
                settled(model)
                assertNull(model.state.sendProblem)
                assertEquals(1, node.mempool().size)
                assertFails { node.cli("sendrawtransaction", kept!!) }
                node.mine(1, miner)
                assertFails { node.cli("sendrawtransaction", kept!!) }
                assertEquals(5 * coin, node.held(shop), "the shop was paid once")
                looked(model)
                assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty(), "the block has settled both")
            }
        }
    }

    /** The other side of the same rule: the owner's own node is still asked first, and what it refuses leaves no trace. */
    @Test
    fun theOwnersNodeIsAskedBeforeAnythingIsWrittenDownAndItsNoIsTheEnd() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            AuditRelay(node.port).use { relay ->
                val wallet = walletInThePhone()
                val shop = fresh().address(0, 0)
                val miner = fresh().address(0, 0)
                node.mine(1, wallet.address(0, 0))
                node.mine(100, miner)
                setups.value = node.setup(relay.port)
                check(!setups.value!!.isPublic)
                val model = opened()
                assertNull(model.state.problem)

                var written: Boolean? = null
                relay.rewrite = { method, answer ->
                    if (method != "testmempoolaccept") answer else {
                        written = wallets.value!!.pending.isNotEmpty()
                        no("min relay fee not met").second
                    }
                }
                val told = assertNotNull(pay(model, shop, "5"))
                assertEquals(Problem.Kind.REJECTED to "min relay fee not met", told.kind to told.detail)
                assertEquals(false, written, "the payment was written down before the owner's node was asked")
                assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty())
                assertEquals(0, model.state.wallet!!.changeIndex)
                assertEquals(0, relay.count("sendrawtransaction"))
                assertTrue(node.mempool().isEmpty())
            }
        }
    }
}
