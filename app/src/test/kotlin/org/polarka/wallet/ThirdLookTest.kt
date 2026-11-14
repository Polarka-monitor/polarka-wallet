package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Planner
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the reviewer's third look (review/independent-review-0.4.2/RETEST2.md) named and did not test itself:
 * R2-03 (a no after a try without an answer) and note N2-3 (the owner's yes to look further and another node).
 * The reviewer's own tests are in Retest2ModelTest. Note N2-1 is tested in NotGoneThroughTest, N2-4 and what the
 * entry of a kept payment says (R2-02) in NotGoneThroughScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
class ThirdLookTest {
    /** A node that is not a node: every call is answered by [answer] with an HTTP status and a body. */
    private class Scripted(private val answer: (method: String) -> Pair<Int, String>) : AutoCloseable {
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
            val length = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val raw = ByteArray(length)
            var n = 0
            while (n < raw.size) {
                val got = input.read(raw, n, raw.size - n)
                if (got < 0) break
                n += got
            }
            val method = Json.parseToJsonElement(raw.decodeToString()).jsonObject["method"]!!.jsonPrimitive.content
            synchronized(methods) { methods.add(method) }
            val (status, body) = answer(method)
            val out = body.encodeToByteArray()
            s.getOutputStream().apply {
                write("HTTP/1.1 $status scripted\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                write(out)
                flush()
            }
        }

        fun count(method: String): Int = synchronized(methods) { methods.count { it == method } }

        override fun close() = server.close()
    }

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-third-look-").toFile()
        Stores.open = { setups }
        Stores.wallet = { wallets }
        Stores.words = { WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1")) }
    }

    @After
    fun restore() {
        Stores.open = Stores.default
        Stores.wallet = Stores.defaultWallet
        Stores.words = Stores.defaultWords
        dir.deleteRecursively()
    }

    private fun until(what: String, millis: Long = 60_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            Thread.sleep(15)
        }
    }

    private fun error(code: Int, message: String) = "{\"result\":null,\"error\":{\"code\":$code,\"message\":\"$message\"},\"id\":\"north\"}"

    /**
     * R2-03. A no from the node settles a payment only when no try went before it. After a try that brought
     * no answer the transaction may already be out (one address can stand for more than one node), and a no
     * on the next try does not take that back: the outcome stays "not known", the payment stays written down,
     * and it is settled later like any other whose outcome is not known.
     */
    @Test
    fun aNoAfterATryWithoutAnAnswerSettlesNothing() {
        val account = Account.fromWords(Words.generate(), Chain.REGTEST)
        val coin = Unspent("ab".repeat(32), 0, 512_00000000L, 0, 0, 1, false)
        val to = Account.fromWords(Words.generate(), Chain.REGTEST).script(0, 0)
        val signed = Planner.pay(listOf(coin), to, 100_00000000L, 2, account.script(1, 0)).sign(account)
        val tries = AtomicInteger()
        Scripted { method ->
            when (method) {
                "sendrawtransaction" -> if (tries.getAndIncrement() == 0) 502 to error(-32603, "the node behind the gateway did not answer") else 500 to error(-26, "min relay fee not met")
                "getmempoolentry" -> 500 to error(-5, "Transaction not in mempool")
                else -> 404 to error(-32601, "Method not found")
            }
        }.use { node ->
            val engine = Engine(Setup("http://127.0.0.1:${node.port}/", "phone", "pw", Chain.REGTEST, emptyList()))
            val outcome = runCatching { engine.deliver(signed) }
            assertEquals(false, outcome.getOrNull(), "a no that follows a try without an answer: ${outcome.exceptionOrNull()}")
            assertEquals(2, node.count("sendrawtransaction"), "after the no the transaction is not handed over again")
        }
        // a no on the very first try is a refusal, as before
        Scripted { method -> if (method == "sendrawtransaction") 500 to error(-26, "min relay fee not met") else 500 to error(-5, "Transaction not in mempool") }.use { node ->
            val engine = Engine(Setup("http://127.0.0.1:${node.port}/", "phone", "pw", Chain.REGTEST, emptyList()))
            assertEquals(-26, (runCatching { engine.deliver(signed) }.exceptionOrNull() as? NodeError)?.code)
            assertEquals(1, node.count("sendrawtransaction"))
        }
    }

    /**
     * N2-3. The owner's yes to look at more keys was given for the node that was asked then. When another node
     * is set up, the app goes back to what it looks at by itself, and the new node is not named thousands of
     * addresses without a new question. Keys the wallet uses are looked at whatever the node.
     */
    @Test
    fun theOwnersYesToLookFurtherDoesNotGoAlongToAnotherNode() {
        val account = Account.fromWords(Words.generate(), Chain.MAIN)
        setups.value = Setup("https://127.0.0.1:1/", "phone", "Pass-1", Chain.MAIN, listOf(account.address(0, 0)))
        wallets.value = WalletInfo(Chain.MAIN, account.watchKey().xpub, receiveIndex = 60, lockOnOpen = false, lookTo = 800)
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        until("the first look is over") { !model.state.busy }
        assertEquals(800, Engine(setups.value!!).allowed(model.state.wallet!!))

        assertTrue(model.offerPublic("https://127.0.0.1:2/"))
        model.acceptOffer(false)
        until("the other node is taken") { model.state.setup?.isPublic == true && !model.state.busy }
        assertEquals(0, model.state.wallet!!.lookTo)
        until("the wallet's file is written") { wallets.value!!.lookTo == 0 }
        assertEquals(61, Engine(setups.value!!).allowed(model.state.wallet!!), "a public node is named the keys in use, and no more")
    }
}
