package north.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What was added to the node client after the independent audit of 0.3.1 (findings F-09, N-9, N-10) and for
 * public nodes: no login without an account, patience with a busy node, limits on what a node may send,
 * long lists sent in parts. A small server that is not a node answers here; it shows what the client sends.
 */
class HardeningTest {
    private class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private class Fake(private val answer: (count: Int, body: String) -> Reply) : AutoCloseable {
        val seen = ArrayList<Pair<Map<String, String>, String>>()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val url: String get() = "http://127.0.0.1:${server.localPort}/"

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
            val body = raw.decodeToString()
            val count = synchronized(seen) { seen.add(headers to body); seen.size }
            val reply = answer(count, body)
            val out = reply.body.encodeToByteArray()
            s.getOutputStream().apply {
                write(("HTTP/1.1 ${reply.status} x\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n" +
                    reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").encodeToByteArray())
                write(out)
                flush()
            }
        }

        override fun close() = server.close()
    }

    private fun ok(result: String) = Reply(200, "{\"result\":$result,\"error\":null,\"id\":\"north\"}")
    private fun method(body: String): String = Json.parseToJsonElement(body).jsonObject["method"].toString().trim('"')

    @Test
    fun aNodeWithoutAnAccountIsSentNoLogin() {
        Fake { _, _ -> ok("7") }.use { fake ->
            assertEquals(7, NodeClient(fake.url, "", "").blockCount())
            assertEquals(7, NodeClient(fake.url, "phone", "Pass-1").blockCount())
            assertFalse(fake.seen[0].first.containsKey("authorization"), "a public node was sent a login")
            assertTrue(fake.seen[1].first["authorization"].orEmpty().startsWith("Basic "), "a node of one's own was not sent its login")
        }
    }

    @Test
    fun aBusyNodeIsAskedAgainAfterTheTimeItNames() {
        // a gateway that asks to slow down, then one that is busy, then a node still running an earlier scan, then the answer
        Fake { count, _ ->
            when (count) {
                1 -> Reply(429, "{\"result\":null,\"error\":{\"code\":-32000,\"message\":\"slow down\"},\"id\":\"north\"}", mapOf("Retry-After" to "1"))
                2 -> Reply(503, "busy", mapOf("Retry-After" to "1"))
                3 -> Reply(500, "{\"result\":null,\"error\":{\"code\":-8,\"message\":\"Scan already in progress, use action \\\"abort\\\" or \\\"status\\\"\"},\"id\":\"north\"}")
                else -> ok("9")
            }
        }.use { fake ->
            val start = System.nanoTime()
            assertEquals(9, NodeClient(fake.url, "", "").blockCount())
            val took = (System.nanoTime() - start) / 1_000_000
            assertEquals(4, fake.seen.size)
            assertTrue(took in 3_000..20_000, "the client should have waited about four seconds, it took $took ms")
        }
    }

    @Test
    fun whatANodeSendsHasLimits() {
        val huge = "x".repeat(NodeClient.MAX_ANSWER_BYTES + 10)
        Fake { _, body ->
            when (method(body)) {
                "getblockcount" -> ok("\"$huge\"")
                "getblockchaininfo" -> Reply(500, "{\"result\":null,\"error\":{\"code\":-1,\"message\":\"${"long \\u0007 text ".repeat(400)}\"},\"id\":\"north\"}")
                "scantxoutset" -> ok("{\"success\":true,\"height\":50,\"unspents\":[" +
                    "{\"txid\":\"${"11".repeat(32)}\",\"vout\":0,\"scriptPubKey\":\"5220${"00".repeat(32)}\",\"amount\":1.0,\"height\":5,\"coinbase\":false}," +
                    "{\"txid\":\"${"11".repeat(32)}\",\"vout\":0,\"scriptPubKey\":\"5220${"00".repeat(32)}\",\"amount\":1.0,\"height\":5,\"coinbase\":false}]}")
                "scanblocks" -> ok("{\"from_height\":0,\"to_height\":50,\"relevant_blocks\":[\"not a hash\"],\"completed\":true}")
                "sendrawtransaction" -> ok("\"${"ab".repeat(400)}\"")
                else -> ok("null")
            }
        }.use { fake ->
            val node = NodeClient(fake.url, "", "")
            assertTrue("too long" in assertFailsWith<NodeError> { node.blockCount() }.message.orEmpty())
            val said = assertFailsWith<NodeError> { node.chainInfo() }.message.orEmpty()
            assertTrue(said.length <= 200 && said.none { it < ' ' }, "the node's words reach the screen as they came: ${said.length} characters")
            assertTrue("twice" in assertFailsWith<NodeError> { node.balance(listOf("rnorth1address")) }.message.orEmpty())
            assertFailsWith<NodeError> { node.relevantBlocks(listOf("addr(rnorth1address)"), 1) }
            assertTrue(node.broadcast("00").length <= 80)
            assertFalse(node.unspent("11".repeat(32), 0), "an output the node does not know counts as spent")
        }
    }

    @Test
    fun aLongListOfAddressesGoesOutInParts() {
        Fake { count, body ->
            val asked = Json.parseToJsonElement(body).jsonObject["params"]!!.jsonArray[1].jsonArray.size
            check(asked <= SCAN_CHUNK) { "one request carried $asked descriptors" }
            ok("{\"success\":true,\"height\":${60 - count},\"unspents\":[" +
                "{\"txid\":\"${"%02x".format(count).repeat(32)}\",\"vout\":0,\"scriptPubKey\":\"5220${"00".repeat(32)}\",\"amount\":1.5,\"height\":5,\"coinbase\":false}]}")
        }.use { fake ->
            val found = NodeClient(fake.url, "", "").scanDescriptors(List(SCAN_CHUNK * 2 + 100) { "addr(rnorth1address$it)" }, 1)
            assertEquals(3, fake.seen.size)
            assertEquals(3, found.utxos.size)
            assertEquals(57, found.tip, "the lowest height of the parts is the one to count from")
            assertEquals(450_000_000L, found.totalSat)
        }
    }

    @Test
    fun oneTransactionIsListedOnceEvenIfTwoBlocksHoldIt() {
        val ours = "5220" + "aa".repeat(32)
        val tx = ChainTx("11".repeat(32), false, listOf(TxInput("22".repeat(32), 0)), listOf(TxOutput(0, 5_00000000L, ours)))
        val blocks = listOf(ChainBlock("b1".repeat(32), 10, 1000, listOf(tx)), ChainBlock("b2".repeat(32), 10, 1001, listOf(tx)))
        val moves = History.movements(blocks, setOf(ours))
        assertEquals(1, moves.size)
        assertEquals(5_00000000L, moves[0].receivedSat)
        assertEquals(setOf(ours), History.paidScripts(blocks))
    }
}
