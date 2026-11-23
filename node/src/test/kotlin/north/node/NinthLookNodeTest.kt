package north.node

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
 * The ninth look (an outside review of 0.7.1 by people), finding M-4: the node client at the edge of trust. A small
 * server that is not a node answers here, as a gateway in front of a node might.
 */
class NinthLookNodeTest {
    /** Answers each question with [reply] for its method. With [drip] the answer comes one byte at a time, so many ms apart. */
    private class Fake(private val drip: Long = 0, private val reply: (method: String) -> Pair<Int, String>) : AutoCloseable {
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
            val length = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val raw = ByteArray(length)
            var n = 0
            while (n < raw.size) {
                val got = input.read(raw, n, raw.size - n)
                if (got < 0) break
                n += got
            }
            val method = Regex("\"method\":\"([a-z]+)\"").find(raw.decodeToString())?.groupValues?.get(1).orEmpty()
            val (status, text) = reply(method)
            val body = text.encodeToByteArray()
            val out = s.getOutputStream()
            out.write("HTTP/1.1 $status x\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.flush()
            if (drip == 0L) {
                out.write(body)
            } else {
                for (b in body) {
                    Thread.sleep(drip)
                    out.write(b.toInt())
                    out.flush()
                }
            }
            out.flush()
        }

        override fun close() = server.close()
    }

    private val id = "ab".repeat(32)

    private fun answer(result: String) = 200 to """{"result":$result,"error":null,"id":"north"}"""

    /** A failure of a gateway in front of the node is no answer of the node: not "not held", not "spent". */
    @Test
    fun aGatewayFailureIsNoAnswerOfTheNode() {
        Fake { 502 to """{"message":"bad gateway"}""" }.use { fake ->
            val node = NodeClient(fake.url, "", "")
            assertEquals(502, assertFailsWith<NodeError> { node.waiting(id) }.httpStatus)
            assertEquals(502, assertFailsWith<NodeError> { node.unspent(id, 0) }.httpStatus)
        }
        // an answer of success without a result is none either
        Fake { 200 to """{"message":"ok"}""" }.use { fake ->
            val node = NodeClient(fake.url, "", "")
            assertFailsWith<NodeError> { node.waiting(id) }
            assertFailsWith<NodeError> { node.unspent(id, 0) }
        }
        // a result of another shape than the call has
        Fake { answer("\"yes\"") }.use { fake ->
            val node = NodeClient(fake.url, "", "")
            assertFailsWith<NodeError> { node.waiting(id) }
            assertFailsWith<NodeError> { node.unspent(id, 0) }
        }
        Fake { answer("null") }.use { fake ->
            val node = NodeClient(fake.url, "", "")
            assertFalse(node.waiting(id), "null: not held, as some stand-ins say it")
            assertFalse(node.unspent(id, 0), "null: an output that is spent, as the node says it")
        }
        // the node's own words are taken as before
        Fake { m ->
            if (m == "getmempoolentry") 500 to """{"result":null,"error":{"code":-5,"message":"Transaction not in mempool"},"id":"north"}"""
            else answer("""{"value":5.00000000,"confirmations":3}""")
        }.use { fake ->
            val node = NodeClient(fake.url, "", "")
            assertFalse(node.waiting(id))
            assertTrue(node.unspent(id, 0))
        }
        Fake { answer("""{"vsize":163}""") }.use { fake -> assertTrue(NodeClient(fake.url, "", "").waiting(id)) }
    }

    /** A block time that cannot be a date is refused where it is read; as a date on the history page it would end the app. */
    @Test
    fun aBlockTimeThatCannotBeADateIsRefused() {
        for (time in listOf("-1", "32503680001", "9223372036854775807")) {
            Fake { m ->
                when (m) {
                    "getblockhash" -> answer("\"$id\"")
                    "getblockheader" -> answer("""{"hash":"$id","height":5,"time":$time}""")
                    else -> answer("""{"hash":"$id","height":5,"time":$time,"tx":[]}""")
                }
            }.use { fake ->
                val node = NodeClient(fake.url, "", "")
                assertFailsWith<NodeError>(time) { node.blockTime(5) }
                assertFailsWith<NodeError>(time) { node.block(id) }
            }
        }
        Fake { m -> if (m == "getblockhash") answer("\"$id\"") else answer("""{"hash":"$id","height":5,"time":1700000000,"tx":[]}""") }.use { fake ->
            val node = NodeClient(fake.url, "", "")
            assertEquals(1_700_000_000L, node.blockTime(5))
            assertEquals(1_700_000_000L, node.block(id).time)
        }
    }

    /**
     * A node that sends its answer one byte at a time, each well within the time a read may wait, is not waited for
     * without end: the whole question has a time of its own (the time to connect and one read's time more).
     */
    @Test
    fun aNodeThatDripsItsAnswerIsNotWaitedForWithoutEnd() {
        Fake(drip = 100) { answer("1" + "0".repeat(200)) }.use { fake ->
            val node = NodeClient(fake.url, "", "", timeoutMs = 400)
            val start = System.nanoTime()
            val e = assertFailsWith<NodeError> { node.call("getblockcount") }
            val took = (System.nanoTime() - start) / 1_000_000
            assertEquals(NodeError.TIMEOUT, e.code)
            assertTrue(took < 5_000, "given up after $took ms; the answer would have taken twenty seconds")
            assertTrue(took >= 700, "not before the time of the whole question ($took ms)")
        }
        // an answer that comes in time is read as before
        Fake(drip = 1) { answer("7") }.use { fake -> assertEquals(7, NodeClient(fake.url, "", "", timeoutMs = 2_000).blockCount()) }
    }
}
