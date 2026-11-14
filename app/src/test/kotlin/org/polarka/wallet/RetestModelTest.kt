package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Qrh
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retest of 0.4.0 by the independent reviewer: what the fixes left open or brought in.
 * Every test describes what a user is entitled to expect; a failure is a finding (see RETEST.md for which).
 */
@RunWith(RobolectricTestRunner::class)
class RetestModelTest {
    /** Like AuditRelay, and two things more: act before a call is passed on, and answer in the node's place after it was. */
    private class Relay(private val target: Int) : AutoCloseable {
        @Volatile var before: (method: String) -> Unit = {}
        @Volatile var instead: (method: String) -> Pair<Int, String>? = { null }
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
            val method = Json.parseToJsonElement(raw.decodeToString()).jsonObject["method"]!!.jsonPrimitive.content
            before(method)
            val c = URL("http://127.0.0.1:$target/").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            headers["authorization"]?.let { c.setRequestProperty("Authorization", it) }
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(raw) }
            var status = c.responseCode
            var answer = ((if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).decodeToString()
            val other = instead(method)
            if (other != null) {
                status = other.first
                answer = other.second
            }
            val out = answer.encodeToByteArray()
            s.getOutputStream().apply {
                write("HTTP/1.1 $status relay\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                write(out)
                flush()
            }
        }

        override fun close() = server.close()
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
        dir = Files.createTempDirectory("polarka-retest-model-").toFile()
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

    private fun opened(millis: Long = 120_000): WalletModel {
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        if (model.state.locked) {
            model.unlockApp(owner)
            until("the app is unlocked") { !model.state.locked }
        }
        until("the first look at the node", millis) { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
        return model
    }

    private fun refreshed(model: WalletModel) {
        until("no look in progress") { !model.state.busy }
        model.refresh()
        until("a fresh look") { !model.state.busy }
    }

    private fun pay(model: WalletModel, to: String, amount: String, rate: Long): Problem? {
        until("no look at the node in progress") { !model.state.busy }
        model.dropDraft()
        model.prepare(to, amount, false, rate)
        until("the payment is worked out") { !model.state.sending }
        if (model.state.draft == null) return model.state.sendProblem
        model.confirmSend(owner)
        until("the payment is sent or refused") { !model.state.sending }
        return model.state.sendProblem
    }

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)

    /**
     * R-01 (what F-02 left open). gateway.py answers HTTP 502 with a JSON error when its own call to the node
     * raised: the node may have taken the transaction, only its answer did not come back. The app takes every
     * answer that is not "no connection", 429 or 503 for a refusal and strikes the waiting payment out.
     */
    @Test
    fun aGatewayThatDidNotHearTheNodeIsNotTakenForARefusal() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Relay(node.port).use { relay ->
                val words = Words.generate()
                val wallet = Account.fromWords(words, Chain.REGTEST)
                val shop = fresh().address(0, 0)
                val miner = fresh().address(0, 0)
                node.mine(3, wallet.address(0, 0))
                node.mine(100, miner)
                setups.value = node.setup(port = relay.port)
                wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                vault().let { it.store(it.encryptor(), words) }
                val model = opened()

                // the node takes the transaction; the gateway in between reports that the node did not answer
                relay.instead = { m ->
                    if (m == "sendrawtransaction") 502 to "{\"result\":null,\"error\":{\"code\":-32603,\"message\":\"the node behind the gateway did not answer\"},\"id\":null}" else null
                }
                val told = pay(model, shop, "100", 2)
                relay.instead = { null }
                val inMempool = node.mempool().size
                val remembered = model.state.pending.size
                // told that the node answered with an error, and shown no waiting payment, the user pays again at once
                val again = if (told != null) pay(model, shop, "100", 2) else null
                until("no look in progress") { !model.state.busy }          // the test asks the node itself next; the node does one scan at a time
                node.mine(1, miner)
                val shopGot = node.held(shop)
                println("RETEST R-01: gateway answers 502 after the node took the transaction: the app says $told, remembers $remembered waiting payment(s), " +
                    "the node's mempool holds $inMempool; paying again at once: problem=$again; the shop now holds ${Format.coins(shopGot)}")
                assertEquals(100_00000000L, shopGot, "one payment of 100 NORTH was wanted; the shop received")
            }
        }
    }

    /**
     * R-02 (new with the fix of F-01/F-02). One look reads the history, then the unspent coins, then asks whether
     * the node still holds each waiting payment. A block that arrives between the last two steps makes a payment
     * that has just been confirmed look "not in a block, not held, coins still there": it is reported as gone.
     */
    @Test
    fun aPaymentThatEntersABlockDuringALookIsNotReportedAsGone() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Relay(node.port).use { relay ->
                val words = Words.generate()
                val wallet = Account.fromWords(words, Chain.REGTEST)
                val shop = fresh().address(0, 0)
                val miner = fresh().address(0, 0)
                node.mine(2, wallet.address(0, 0))
                node.mine(100, miner)
                setups.value = node.setup(port = relay.port)
                wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                vault().let { it.store(it.encryptor(), words) }
                val model = opened()
                assertNull(pay(model, shop, "100", 2), "the payment")
                until("the look after the payment") { !model.state.busy }
                check(model.state.pending.size == 1 && node.mempool().size == 1) { "the payment is not waiting as the test needs it" }

                // the next block is found while the app is in the middle of its look
                val mined = AtomicBoolean(false)
                relay.before = { m -> if (m == "getmempoolentry" && mined.compareAndSet(false, true)) node.mine(1, miner) }
                refreshed(model)
                relay.before = {}
                until("no look in progress") { !model.state.busy }
                val notice = model.state.notice
                val detail = model.state.noticeDetail
                val waiting = model.state.pending.size
                val shopGot = node.held(shop)
                println("RETEST R-02: a block arrived during the look: notice=$notice ($detail), waiting payments now $waiting; the shop holds ${Format.coins(shopGot)} in a block")
                assertNotEquals(Notice.PAYMENT_GONE, notice, "the app says that a payment of $detail NORTH did not go through, while the shop holds ${Format.coins(shopGot)} in a block")
            }
        }
    }

    /**
     * R-03 (new with the fix of F-03 and the public node). The search for used keys believes any block the node
     * shows. A public node is told the addresses, so it can show a block that pays the last ones it was told,
     * again and again: the app then names up to 5000 addresses of each branch and moves its key numbers there.
     */
    @Test
    fun aPublicNodeCannotPushTheKeyNumbersFarAhead() {
        val account = Account.fromWords(Words.generate(), Chain.REGTEST)
        val told = ConcurrentHashMap<String, String>()          // every address the node was told, with its script
        fun note(list: JsonElement) = list.jsonArray.forEach {
            val address = it.jsonPrimitive.content.removePrefix("addr(").removeSuffix(")")
            told.computeIfAbsent(address) { a -> Format.hex(Qrh.scriptForAddress(Chain.REGTEST, a)) }
        }
        val hash = "ab".repeat(32)
        AuditFakeNode { method, params ->
            when (method) {
                "getblockchaininfo" -> "{\"chain\":\"regtest\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false,\"bestblockhash\":\"$hash\"}"
                "scanblocks" -> {
                    note(params[1])
                    "{\"from_height\":0,\"to_height\":1,\"relevant_blocks\":[\"$hash\"],\"completed\":true}"
                }
                "scantxoutset" -> {
                    note(params[1])
                    "{\"success\":true,\"height\":1,\"unspents\":[]}"
                }
                // one block that pays a little to every address the node has been told so far
                "getblock" -> "{\"hash\":\"$hash\",\"height\":1,\"time\":1700000000,\"tx\":[{\"txid\":\"${"cd".repeat(32)}\",\"vin\":[{\"coinbase\":\"00\"}],\"vout\":[" +
                    told.values.toList().mapIndexed { i, script -> "{\"n\":$i,\"value\":0.00001000,\"scriptPubKey\":{\"hex\":\"$script\"}}" }.joinToString(",") + "]}]}"
                "getnetworkhashps" -> "1"
                else -> "null"
            }
        }.use { fake ->
            setups.value = Setup("http://127.0.0.1:${fake.port}/", "", "", Chain.REGTEST, emptyList())      // a public node: no account
            wallets.value = WalletInfo(Chain.REGTEST, account.watchKey().xpub, lockOnOpen = false)
            val model = opened(300_000)
            until("the key numbers are written down") { wallets.value?.receiveIndex == model.state.wallet?.receiveIndex }
            val now = model.state.wallet!!
            println("RETEST R-03: after one look at a hostile public node: addresses named to it ${told.size}; receiving key number ${now.receiveIndex}, change key number ${now.changeIndex}, " +
                "window ${now.window}; stored in the wallet's file: ${wallets.value?.receiveIndex}; problem=${model.state.problem}")
            assertTrue(told.size <= 400 && now.receiveIndex <= 40, "a public node made the wallet name ${told.size} addresses and moved its receiving address to number ${now.receiveIndex + 1}")
        }
    }

    /**
     * Sound (expected to pass). The app died after a payment was written into the wallet's file and before it was
     * handed to the node. On the next start the owner is told that it did not go through, and its coin is free again.
     */
    @Test
    fun aPaymentWrittenDownButNeverHandedOverIsClearedUpOnTheNextStart() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            node.mine(1, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            // what the phone holds after dying between the two steps: the payment written down as "not known", nothing at the node
            val engine = Engine(node.setup())
            val plan = engine.plan(info, shop, 100_00000000L, 2)
            val signed = engine.sign(info.xpub, plan, words)
            wallets.value = info.copy(changeIndex = 1, pending = listOf(
                Pending(signed.txid, plan.sentSat, plan.feeSat, plan.inputs.map { it.txid + ":" + it.vout }.toSet(), unsure = true, atMillis = 1),
            ))
            val model = opened()
            val notice = model.state.notice
            val left = model.state.pending.size
            val spendable = model.state.snapshot?.spendableSat
            println("RETEST sound: next start after dying between writing down and handing over: notice=$notice, waiting payments $left, can be spent ${spendable?.let { Format.coins(it) }}")
            assertEquals(Notice.PAYMENT_GONE, notice)
            assertEquals(0, left)
            assertEquals(512_00000000L, spendable)
            assertNull(pay(model, shop, "100", 2), "the payment made anew")
            node.mine(1, miner)
            assertEquals(100_00000000L, node.held(shop))
        }
    }

    /** R-04 (lock). The minute away is measured with the clock that anybody holding the phone can set. */
    @Test
    fun settingTheClockBackDoesNotKeepTheWalletOpen() {
        wallets.value = WalletInfo(Chain.MAIN, Account.fromWords(Words.generate(), Chain.MAIN).watchKey().xpub, lockOnOpen = true)
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        model.unlockApp(owner)
        until("the app is unlocked") { !model.state.locked }
        val left = 1_800_000_000_000L
        model.left(left)
        // ten minutes pass; whoever holds the phone sets its clock back by twenty before opening the wallet again
        model.returned(left + 10 * 60_000L - 20 * 60_000L)
        assertTrue(model.state.locked, "after ten minutes away the wallet is open: the phone's clock was set back, and the app measures the time away with that clock")
    }
}
