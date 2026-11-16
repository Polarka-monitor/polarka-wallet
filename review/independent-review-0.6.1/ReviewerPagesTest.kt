package north.node

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reviewer's test (0.6.1). Not part of the author's suite.
 *
 * A server that hands out one coin (or one line of history) per page, each with a cursor to the next, is
 * followed for as many pages as it likes: the only bound is the number of coins (20,000) or lines (2,000 per
 * part). Nothing bounds the number of requests or the time of one read as a whole. This test ASSERTS THE
 * FAULT: it passes while a server can make one read of coins take more than 20,000 requests.
 */
class ReviewerPagesTest {
    private val h1 = "11".repeat(32)
    private fun txid(n: Int) = "%064x".format(n)
    private val index = "{\"ready\":true,\"indexed_height\":100,\"indexed_hash\":\"$h1\",\"observed_node_height\":100," +
        "\"observed_node_hash\":\"$h1\",\"last_node_contact\":1763000000,\"pending_available\":true," +
        "\"pending_observed_at\":1763000000,\"pending_revision\":7,\"max_age_seconds\":60}"

    private fun serve(answer: (Int, String) -> String): Pair<HttpServer, AtomicInteger> {
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val out = answer(count.incrementAndGet(), body).encodeToByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        return server to count
    }

    @Test
    fun oneCoinToAPageIsFollowedForMoreThanTwentyThousandRequests() {
        val (server, count) = serve { n, _ ->
            val coin = "{\"transaction_id\":\"${txid(n)}\",\"output_index\":0,\"amount\":1,\"script_pubkey\":\"5220${"ab".repeat(32)}\"," +
                "\"block_height\":90,\"confirmations\":11,\"coinbase\":false}"
            "{\"utxos\":[$coin],\"next_cursor\":\"c$n\",\"index\":$index}"
        }
        try {
            val started = System.nanoTime()
            val e = runCatching { ServerClient("http://127.0.0.1:${server.address.port}").utxos(listOf("rnorth1z" + "q".repeat(40))) }.exceptionOrNull()
            val ms = (System.nanoTime() - started) / 1_000_000
            println("REVIEWER one coin per page: ${count.get()} requests, $ms ms, ended with ${e?.javaClass?.simpleName} code=${(e as? NodeError)?.code}")
            assertEquals(ServerClient.TOO_MANY, (e as NodeError).code)
            assertTrue(count.get() > 20_000, "requests: ${count.get()}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun oneLineToAPageIsFollowedForTwoThousandRequestsPerPart() {
        val (server, count) = serve { n, _ ->
            val line = "{\"transaction_id\":\"${txid(n)}\",\"net_amount\":5,\"network_fee\":1,\"block_height\":90,\"confirmations\":11,\"timestamp\":1763000000}"
            "{\"transactions\":[$line],\"next_cursor\":\"c$n\",\"index\":$index}"
        }
        try {
            val h = ServerClient("http://127.0.0.1:${server.address.port}").history(listOf("rnorth1z" + "q".repeat(40)))
            println("REVIEWER one line per page: ${count.get()} requests, complete=${h.complete}")
            assertEquals(2000, count.get())
        } finally {
            server.stop(0)
        }
    }
}
