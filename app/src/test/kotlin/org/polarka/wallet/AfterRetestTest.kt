package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Planner
import north.core.SignedSpend
import north.core.Unspent
import north.core.Words
import north.node.NodeError
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests written with the fixes that followed the reviewer's retest of 0.4.0 (R-01 to R-06 and the notes of
 * RETEST.md). The reviewer's own tests for R-01 to R-04 are in RetestModelTest; these cover what those do not.
 */
@RunWith(RobolectricTestRunner::class)
class AfterRetestTest {
    /**
     * Stands in front of a node as a gateway or a web server does. [instead] may answer in the node's place,
     * and then the node never hears of the call. With [login] the door logs in to the node itself, as the
     * gateway of a public node does; the app then sends no login. Every request is kept.
     */
    private class Door(private val target: Int?, private val login: String? = null) : AutoCloseable {
        @Volatile var instead: (method: String, params: JsonArray) -> Pair<Int, String>? = { _, _ -> null }
        private val bodies = ArrayList<String>()
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
            synchronized(bodies) {
                bodies.add(raw.decodeToString())
                methods.add(method)
            }
            var (status, answer) = instead(method, call["params"]!!.jsonArray) ?: (0 to "")
            if (status == 0) {
                val c = URL("http://127.0.0.1:${target!!}/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                val authorization = login?.let { "Basic " + Base64.getEncoder().encodeToString(it.encodeToByteArray()) } ?: headers["authorization"]
                authorization?.let { c.setRequestProperty("Authorization", it) }
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

        fun count(method: String): Int = synchronized(bodies) { methods.count { it == method } }

        fun forget() = synchronized(bodies) {
            bodies.clear()
            methods.clear()
        }

        /** Every address the node was told, in whatever call. */
        fun addresses(): Set<String> = synchronized(bodies) {
            bodies.flatMap { body -> Regex("addr\\(([a-z0-9]+)\\)").findAll(body).map { it.groupValues[1] }.toList() }.toSet()
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
        dir = Files.createTempDirectory("polarka-after-retest-").toFile()
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
    private fun error(code: Int, message: String) = "{\"result\":null,\"error\":{\"code\":$code,\"message\":\"$message\"},\"id\":\"north\"}"
    private fun result(json: String) = "{\"result\":$json,\"error\":null,\"id\":\"north\"}"

    private fun signedPayment(): SignedSpend {
        val account = fresh()
        val coin = Unspent("ab".repeat(32), 0, 512_00000000L, 0, 0, 1, false)
        return Planner.pay(listOf(coin), fresh().script(0, 0), 100_00000000L, 2, account.script(1, 0)).sign(account)
    }

    /**
     * R-01. Whether a payment was refused is decided by the node and by nobody else. An error of a gateway, of
     * a web server in front of the node, or an answer nobody can read leaves the outcome open: the node is
     * asked whether it holds the transaction, and the payment is never reported as refused.
     */
    @Test
    fun onlyTheNodesOwnNoIsARefusal() {
        val signed = signedPayment()
        Door(null).use { door ->
            val engine = Engine(Setup("http://127.0.0.1:${door.port}/", "phone", "pw", Chain.REGTEST, emptyList()))
            fun outcome(send: Pair<Int, String>, held: Boolean): Result<Boolean> {
                door.forget()
                door.instead = { method, _ ->
                    when (method) {
                        "sendrawtransaction" -> send
                        "getmempoolentry" -> if (held) 200 to result("{\"vsize\":163}") else 500 to error(-5, "Transaction not in mempool")
                        else -> 404 to error(-32601, "Method not found")
                    }
                }
                return runCatching { engine.deliver(signed) }
            }
            // the node's own no is a refusal, and the transaction is not handed over again
            for (code in listOf(-26, -25, -22)) {
                val refused = outcome(500 to error(code, "the node says no"), held = false)
                assertEquals(code, (refused.exceptionOrNull() as? NodeError)?.code, "the node's code $code")
                assertEquals(1, door.count("sendrawtransaction"))
            }
            // not the node's own decision, and the node holds the transaction: it went out
            val openAnswers = listOf(
                502 to error(-32603, "the node behind the gateway did not answer"), 504 to "", 500 to "<html>Internal Server Error</html>",
                403 to "forbidden", 200 to "not json", 200 to result("\"" + "cd".repeat(32) + "\""), 500 to error(-28, "Loading block index"),
            )
            for (answer in openAnswers) {
                val went = outcome(answer, held = true)
                assertEquals(true, went.getOrNull(), "HTTP ${answer.first} ${answer.second.take(50)}: ${went.exceptionOrNull()}")
            }
            // not the node's own decision, and the node does not hold it: not known, and never "refused"
            val open = outcome(502 to error(-32603, "the node behind the gateway did not answer"), held = false)
            assertEquals(false, open.getOrNull(), "a gateway's error: ${open.exceptionOrNull()}")
            assertEquals(Engine.DELIVERY_TRIES, door.count("sendrawtransaction"), "the same transaction is handed over again")
            // a transaction that is already in a block went out as well
            assertEquals(true, outcome(500 to error(-27, "Transaction outputs already in utxo set"), held = false).getOrNull())
        }
    }

    /**
     * R-01 and N-3 in the app. A payment the node itself refuses is shown as refused, nothing stays written
     * down about it, and its change key is given back. Payments that do go out move the change key on, but
     * never further than a wallet brought back from its words would look.
     */
    @Test
    fun aRefusedPaymentLeavesNothingBehindAndTheChangeKeyStaysWhereARestoreLooks() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Door(node.port).use { door ->
                val words = Words.generate()
                val wallet = Account.fromWords(words, Chain.REGTEST)
                val shop = fresh().address(0, 0)
                val miner = fresh().address(0, 0)
                node.mine(WalletInfo.GAP + 5, wallet.address(0, 0))
                node.mine(100, miner)
                setups.value = node.setup(port = door.port)
                wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                vault().let { it.store(it.encryptor(), words) }
                val model = opened()

                // the node says no to the transaction itself (the door says it in the node's words, before the node hears of it)
                door.instead = { method, _ -> if (method == "sendrawtransaction") 500 to error(-26, "min relay fee not met") else null }
                repeat(3) {
                    val told = pay(model, shop, "100", 2)
                    assertEquals(Problem.Kind.REJECTED, told?.kind, "a refusal is shown as a refusal")
                    settled(model)
                    assertEquals(0, model.state.pending.size, "nothing stays written down about a refused payment")
                    assertEquals(0, model.state.wallet!!.changeIndex, "the change key of a refused payment is given back")
                }
                assertEquals(0, node.mempool().size)

                // payments that go out without a block in between: the door takes each one and says that the node holds it
                door.instead = { method, _ ->
                    when (method) {
                        "sendrawtransaction" -> 502 to error(-32603, "the node behind the gateway did not answer")
                        "getmempoolentry" -> 200 to result("{\"vsize\":163}")
                        else -> null
                    }
                }
                repeat(WalletInfo.GAP + 5) { n ->
                    assertNull(pay(model, shop, "100", 2), "payment ${n + 1}")
                }
                settled(model)
                val changeIndex = model.state.wallet!!.changeIndex
                println("AFTER RETEST N-3: ${WalletInfo.GAP + 5} payments without a block in between; the change key number is $changeIndex")
                assertEquals(WalletInfo.GAP + 5, model.state.pending.size)
                assertEquals(WalletInfo.GAP - 1, changeIndex, "the change key moved further than a wallet brought back from its words looks")
            }
        }
    }

    /** N-4. The wallet's file holds two hundred waiting payments; a payment beyond that is not started. */
    @Test
    fun noMoreWaitingPaymentsAreStartedThanTheWalletFileHolds() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val miner = fresh().address(0, 0)
            node.mine(1, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            val many = (0 until WalletInfo.MAX_PENDING).map {
                Pending("%064x".format(it + 1), 1, 1, setOf("%064x".format(100_000 + it) + ":0"), atMillis = System.currentTimeMillis())
            }
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false, pending = many)
            vault().let { it.store(it.encryptor(), words) }
            val model = opened()
            val told = pay(model, fresh().address(0, 0), "100", 2)
            assertEquals(Problem.Kind.TOO_MANY_WAITING, told?.kind)
            assertEquals(0, node.mempool().size)
            assertEquals(0, owner.unlocks, "the owner is not asked for a payment that will not be started")
            assertEquals(WalletInfo.MAX_PENDING, WalletInfo.parse(model.state.wallet!!.toJson()).pending.size, "the wallet's file can be read back")
        }
    }

    /**
     * R-03 from the honest side. A wallet that has really used more addresses than the app looks at by itself
     * with a public node is found in full once its owner lets the app look further, and until then the node
     * is told no more than the first forty addresses of each branch.
     */
    @Test
    fun aMuchUsedWalletIsFoundAfterTheOwnerLetsTheAppLookFurther() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            Door(node.port, login = "phone:${node.password}").use { door ->
                val coin = 512_00000000L
                val words = Words.generate()
                val wallet = Account.fromWords(words, Chain.REGTEST)
                val used = Engine.AUTO_PUBLIC + 5
                repeat(used) { node.mine(1, wallet.address(0, it)) }
                setups.value = Setup("http://127.0.0.1:${door.port}/", "", "", Chain.REGTEST, emptyList())      // a public node: no account
                val model = opened()
                model.restoreWallet(words.joinToString(" "), owner)
                until("the wallet is stored") { model.state.wallet != null || model.state.walletProblem != null }
                settled(model)

                // by itself the app looks at the first forty addresses and no further, and says that more may be in use
                val first = model.state.snapshot!!
                assertEquals(Engine.AUTO_PUBLIC, first.lookedTo)
                assertTrue(first.wantsFurther)
                assertEquals(Engine.AUTO_PUBLIC * coin, first.walletSat)
                assertEquals(Engine.AUTO_PUBLIC - 1, model.state.wallet!!.receiveIndex, "the receiving address stays one that is looked at")
                assertEquals(2 * Engine.AUTO_PUBLIC, door.addresses().size, "addresses named to the node before the owner said yes")

                // the owner knows that the wallet was used that much, and says so
                model.lookFurther()
                settled(model)
                val second = model.state.snapshot!!
                assertEquals(2 * Engine.AUTO_PUBLIC, second.lookedTo)
                assertFalse(second.wantsFurther)
                assertEquals(used * coin, second.walletSat, "all coins of the wallet are found")
                assertEquals(used, model.state.wallet!!.receiveIndex)
                until("the wallet's file is written") { wallets.value?.lookTo == 2 * Engine.AUTO_PUBLIC && wallets.value?.receiveIndex == used }

                // the key numbers can be worked out again; with an honest node they stay where they are
                model.renumberKeys()
                settled(model)
                assertEquals(used, model.state.wallet!!.receiveIndex)
                assertEquals(Notice.SAVED, model.state.notice)
            }
        }
    }

    /**
     * R-03, the way back. A node that did not show the truth has moved the key numbers ahead. With another
     * node the owner has them worked out again, and they return to what the chain shows.
     */
    @Test
    fun keyNumbersThatRanAheadCanBeWorkedOutAgain() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            node.mine(1, wallet.address(0, 0))
            node.mine(1, wallet.address(0, 1))
            setups.value = node.setup()
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, receiveIndex = 39, changeIndex = 39, lockOnOpen = false)
            val model = opened()
            assertEquals(39, model.state.wallet!!.receiveIndex, "a look alone never moves a key number back")
            model.renumberKeys()
            settled(model)
            assertEquals(2, model.state.wallet!!.receiveIndex)
            assertEquals(0, model.state.wallet!!.changeIndex)
            assertEquals(2 * 512_00000000L, model.state.snapshot!!.walletSat)
            until("the wallet's file is written") { wallets.value?.receiveIndex == 2 && wallets.value?.changeIndex == 0 }
        }
    }

    /**
     * R-05. A look takes a while. If the wallet is removed and another one brought back meanwhile, what the
     * look found for the first wallet must not be written into the second.
     */
    @Test
    fun aLookMadeForOneWalletIsNotSettledIntoAnother() {
        val a = fresh()
        val wordsB = Words.generate()
        val b = Account.fromWords(wordsB, Chain.REGTEST)
        val hash = "ab".repeat(32)
        val holding = AtomicBoolean(true)
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        Door(null).use { door ->
            // a node whose only block pays the sixth receiving address of wallet A, and which is slow with the coins
            door.instead = { method, _ ->
                200 to result(when (method) {
                    "getblockchaininfo" -> "{\"chain\":\"regtest\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false,\"bestblockhash\":\"$hash\"}"
                    "scanblocks" -> "{\"from_height\":0,\"to_height\":1,\"relevant_blocks\":[\"$hash\"],\"completed\":true}"
                    "getblock" -> "{\"hash\":\"$hash\",\"height\":1,\"time\":1700000000,\"tx\":[{\"txid\":\"${"cd".repeat(32)}\",\"vin\":[{\"coinbase\":\"00\"}]," +
                        "\"vout\":[{\"n\":0,\"value\":1.0,\"scriptPubKey\":{\"hex\":\"${Format.hex(a.script(0, 5))}\"}}]}]}"
                    "scantxoutset" -> {
                        if (holding.get()) {
                            reached.countDown()
                            release.await(60, TimeUnit.SECONDS)
                        }
                        "{\"success\":true,\"height\":1,\"unspents\":[]}"
                    }
                    "getnetworkhashps" -> "1"
                    else -> "null"
                })
            }
            setups.value = Setup("http://127.0.0.1:${door.port}/", "phone", "pw", Chain.REGTEST, emptyList())
            wallets.value = WalletInfo(Chain.REGTEST, a.watchKey().xpub, lockOnOpen = false)
            val model = WalletModel(app)
            until("the stores are read") { model.state.loaded }
            check(reached.await(60, TimeUnit.SECONDS)) { "the test is not valid: the look for wallet A did not get to the node" }

            // while the look for wallet A is under way, A is removed and B is brought back from its words
            model.removeWallet(owner)
            until("wallet A is removed") { model.state.wallet == null }
            model.restoreWallet(wordsB.joinToString(" "), owner)
            until("wallet B is stored") { model.state.wallet?.xpub == b.watchKey().xpub }
            holding.set(false)
            release.countDown()
            settled(model)

            assertEquals(0, model.state.wallet!!.receiveIndex, "key numbers that a look found for another wallet were written into this one")
            assertEquals(0, wallets.value!!.receiveIndex)
            assertEquals(0, model.state.snapshot!!.nextReceive, "the picture on the screen belongs to the wallet that is there now")
        }
    }
}
