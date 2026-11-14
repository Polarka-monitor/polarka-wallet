package north.node

import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Independent audit of 0.3.1: the client that talks to the node, against servers that misbehave.
 * Every test describes behaviour that is expected to hold; a failure is a finding.
 */
class AuditNodeTest {
    /** A one-line HTTP server: records what it is sent and answers every request with [answer]. */
    private class Server(private val answer: (Int) -> String) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = try { socket.accept() } catch (e: Exception) { break }
                    runCatching {
                        s.use {
                            val input = it.getInputStream()
                            val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) {
                                val b = input.read()
                                if (b < 0) break
                                head.append(b.toChar())
                            }
                            val length = head.lines().firstOrNull { l -> l.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                            val body = ByteArray(length)
                            var n = 0
                            while (n < length) {
                                val got = input.read(body, n, length - n)
                                if (got < 0) break
                                n += got
                            }
                            requests.add(head.toString() + body.decodeToString())
                            it.getOutputStream().apply { write(answer(port).encodeToByteArray()); flush() }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test
    fun thePasswordDoesNotFollowARedirect() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            Server { "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 39\r\nConnection: close\r\n\r\n{\"result\":7,\"error\":null,\"id\":\"north\"}\n" }.use { other ->
                Server { "HTTP/1.1 $status Moved\r\nLocation: http://127.0.0.1:${other.port}/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }.use { first ->
                    val error = assertFailsWith<NodeError> { NodeClient("http://127.0.0.1:${first.port}/", "phone", "Secret-Pass-1", 5000).blockCount() }
                    assertEquals(status, error.httpStatus)
                    assertEquals(1, first.requests.size)
                    assertTrue(first.requests[0].contains("Authorization: Basic "), "the first server gets the login")
                    Thread.sleep(150)
                    assertTrue(other.requests.isEmpty(), "HTTP $status: the request was repeated at another address: ${other.requests}")
                }
            }
        }
        println("AUDIT node: redirects 301, 302, 303, 307, 308 are not followed; the login stays with the first address")
    }

    @Test
    fun errorTextsOfTheNodeDoNotCarryTheLogin() {
        // whatever fails, the message of the error must not hold the user name or the password
        val secret = "Secret-Pass-1"
        val messages = ArrayList<String>()
        Server { "HTTP/1.1 500 Oops\r\nContent-Length: 9\r\nConnection: close\r\n\r\nnot json\n" }.use { s ->
            messages += assertFailsWith<NodeError> { NodeClient("http://127.0.0.1:${s.port}/", "phone", secret, 5000).blockCount() }.message.orEmpty()
        }
        Server { "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }.use { s ->
            messages += assertFailsWith<NodeError> { NodeClient("http://127.0.0.1:${s.port}/", "phone", secret, 5000).blockCount() }.message.orEmpty()
        }
        messages += assertFailsWith<NodeError> { NodeClient("http://127.0.0.1:1/", "phone", secret, 3000).blockCount() }.message.orEmpty()
        messages += assertFailsWith<IllegalArgumentException> { NodeClient("http://8.8.8.8/", "phone", secret) }.message.orEmpty()
        assertTrue(messages.none { secret in it || "phone" in it }, messages.toString())
    }

    @Test
    fun amountsAreReadExactly() {
        fun sat(text: String) = NodeClient.sats(Json.parseToJsonElement(text))
        assertEquals(1L, sat("0.00000001"))
        assertEquals(51_200_000_000L, sat("512.00000000"))
        assertEquals(2_100_000_000_000_000L, sat("21000000"))
        // Adapted after the fix of F-09: an amount above anything that can exist is refused at the boundary now.
        // The largest amount taken is two thousand million coins; before the fix the largest signed 64-bit number was taken.
        assertEquals(NodeClient.MAX_SAT, sat("2000000000"))
        // what cannot be a whole number of base units is refused, not rounded; so is what cannot exist
        for (bad in listOf("0.000000001", "2000000000.00000001", "92233720368.54775807", "92233720368.54775808", "-0.00000001", "1e30",
            "\"abc\"", "\"\"", "\"NaN\"", "\"Infinity\"", "true", "null")) {
            assertTrue(runCatching { sat(bad) }.isFailure, "taken as an amount: $bad")
        }
    }

    /**
     * An amount is a number written by the node. A number with an enormous negative exponent is still a short
     * text, but turning it into base units makes Java compute a power of ten with millions of digits.
     * Expected: such a text is refused at once. The limits here are generous.
     */
    @Test
    fun anAmountWithAHugeExponentIsRefusedAtOnce() {
        val timings = ArrayList<String>()
        var slowest = 0L
        for (exponent in listOf(100_000, 1_000_000, 4_000_000, 16_000_000)) {
            var took = -1L
            val worker = thread(isDaemon = true) {
                val start = System.nanoTime()
                runCatching { NodeClient.sats(Json.parseToJsonElement("1e-$exponent")) }
                took = (System.nanoTime() - start) / 1_000_000
            }
            worker.join(60_000)
            val shown = if (took < 0) "more than 60000" else took.toString()
            timings.add("1e-$exponent: $shown ms")
            slowest = maxOf(slowest, if (took < 0) 60_000 else took)
            if (took < 0 || took > 20_000) break
        }
        println("AUDIT node: time to refuse an amount with a huge exponent: $timings")
        assertTrue(slowest < 1_000, "a short text from the node keeps the wallet busy: $timings")
    }
}
