package north.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client for a wallet server with the Northbound API, version 1. A small server that is not one answers
 * here; it shows what the client sends and what it makes of answers that are right, wrong, late or untrue.
 * The shapes are those of the server's own sources (shared/src/lib.rs at v0.1.0-rc.5, and the limit on
 * requests of commit 846e444).
 */
class ServerClientTest {
    private class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private class Asked(val method: String, val path: String, val body: String)

    private class Fake(private val answer: (count: Int, asked: Asked) -> Reply) : AutoCloseable {
        val seen = ArrayList<Asked>()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val url: String get() = "http://127.0.0.1:${server.localPort}"

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
            val first = head.lines().first().split(' ')
            val headers = head.lines().drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val raw = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var n = 0
            while (n < raw.size) {
                val got = input.read(raw, n, raw.size - n)
                if (got < 0) break
                n += got
            }
            val asked = Asked(first[0], first[1], raw.decodeToString())
            val count = synchronized(seen) { seen.add(asked); seen.size }
            val reply = answer(count, asked)
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

    private val h1 = "11".repeat(32)
    private val h2 = "22".repeat(32)
    private fun txid(n: Int) = "%064x".format(n)
    private fun address(n: Int) = "rnorth1z" + n.toString().padStart(40, 'q')

    /** The index as the server reports it; by default at its node's tip. */
    private fun index(height: Int = 100, hash: String = h1, ready: Boolean = true, nodeHeight: Int? = height, nodeHash: String? = hash, waiting: Boolean = true): String =
        "{\"ready\":$ready,\"indexed_height\":$height,\"indexed_hash\":\"$hash\",\"observed_node_height\":${nodeHeight ?: "null"}," +
            "\"observed_node_hash\":${nodeHash?.let { "\"$it\"" } ?: "null"},\"last_node_contact\":1763000000,\"pending_available\":$waiting," +
            "\"pending_observed_at\":1763000000,\"max_age_seconds\":60}"

    private fun coin(n: Int, amount: String = "100000000", height: Int = 90, deep: Int = 11, more: String = ""): String =
        "{\"transaction_id\":\"${txid(n)}\",\"output_index\":0,\"amount\":$amount,\"script_pubkey\":\"5220${"ab".repeat(32)}\"," +
            "\"block_height\":$height,\"confirmations\":$deep,\"coinbase\":false$more}"

    private fun coins(list: String, index: String = index()) = Reply(200, "{\"utxos\":[$list],\"index\":$index}")

    private fun line(n: Int, height: Int? = 90, deep: Int = 11, receivedAt: String? = null): String =
        "{\"transaction_id\":\"${txid(n)}\",\"received_at\":${receivedAt?.let { "\"$it\"" } ?: "null"},\"sent_to\":null,\"received_amount\":5," +
            "\"sent_amount\":0,\"fee\":null,\"block_height\":${height ?: "null"},\"confirmations\":$deep,\"timestamp\":1763000000}"

    private fun page(list: String, cursor: String? = null, index: String = index()) =
        Reply(200, "{\"transactions\":[$list],\"next_cursor\":${cursor?.let { "\"$it\"" } ?: "null"},\"index\":$index}")

    private fun refused(block: () -> Unit): NodeError = assertFailsWith<NodeError> { block() }

    @Test
    fun theTipAndTheFeeAreReadAndOnlyWholeNumbersThatCanExistAreTaken() {
        Fake { _, asked ->
            when (asked.path) {
                "/v1/chain/tip" -> Reply(200, "{\"height\":24341}")
                else -> Reply(200, "{\"atomic_units_per_vbyte\":2,\"target_blocks\":6}")
            }
        }.use { fake ->
            val client = ServerClient(fake.url)
            assertEquals(24341, client.tipHeight())
            assertEquals(ServerFee(2, 6), client.feeEstimate())
            assertEquals(listOf("GET /v1/chain/tip", "GET /v1/fees/estimate"), fake.seen.map { it.method + " " + it.path })
            assertTrue(fake.seen.all { it.body.isEmpty() })
        }
        for (body in listOf("{\"height\":\"12\"}", "{\"height\":-1}", "{\"height\":1.5}", "{\"height\":1e3}", "{\"height\":null}", "{}", "[]", "not json", "{\"height\":900000000}")) {
            Fake { _, _ -> Reply(200, body) }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).tipHeight() }.httpStatus, body) }
        }
        Fake { _, _ -> Reply(200, "{\"atomic_units_per_vbyte\":0,\"target_blocks\":6}") }.use { fake -> refused { ServerClient(fake.url).feeEstimate() } }
    }

    @Test
    fun coinsAreAskedForAHundredAddressesAtATimeAndPutTogether() {
        Fake { count, _ -> coins(coin(count, amount = (count * 1000).toString())) }.use { fake ->
            val asked = (1..250).map { address(it) }
            val found = ServerClient(fake.url).utxos(asked + asked.take(3))          // an address named twice is asked about once
            assertEquals(100, found.tip)
            assertEquals(h1, found.tipHash)
            assertEquals(listOf(1000L, 2000L, 3000L), found.utxos.map { it.amountSat })
            assertEquals(Utxo(txid(1), 0, 1000, 90, false, "5220" + "ab".repeat(32)), found.utxos[0])
            assertTrue(fake.seen.all { it.method == "POST" && it.path == "/v1/utxos" })
            val sent = fake.seen.map { Json.parseToJsonElement(it.body).jsonObject["addresses"]!!.jsonArray.map { a -> a.jsonPrimitive.content } }
            assertEquals(listOf(100, 100, 50), sent.map { it.size })
            assertEquals(asked, sent.flatten())
        }
    }

    @Test
    fun partsReadAtDifferentBlocksAreNotPutTogether() {
        Fake { count, _ -> coins(coin(count), index(height = 99 + count, hash = if (count == 1) h1 else h2)) }.use { fake ->
            val error = refused { ServerClient(fake.url).utxos((1..150).map { address(it) }) }
            assertEquals(ServerClient.MOVED, error.code)
        }
        Fake { count, _ -> Reply(200, "{\"used_addresses\":[],\"index\":${index(hash = if (count == 1) h1 else h2)}}") }.use { fake ->
            assertEquals(ServerClient.MOVED, refused { ServerClient(fake.url).activity((1..150).map { address(it) }) }.code)
        }
    }

    @Test
    fun anIndexThatIsNotAtItsNodesTipIsNotBelieved() {
        val stale = listOf(
            index(ready = false), index(waiting = false), index(nodeHeight = 101), index(nodeHash = h2), index(nodeHeight = null), index(nodeHash = null),
            index().replace("\"indexed_height\":100", "\"indexed_height\":null"), index().replace("\"indexed_hash\":\"$h1\"", "\"indexed_hash\":null"),
        )
        for (index in stale) {
            Fake { _, _ -> coins(coin(1), index) }.use { fake ->
                assertEquals(503, refused { ServerClient(fake.url).utxos(listOf(address(1))) }.httpStatus, index)
            }
        }
        Fake { _, _ -> Reply(200, "{\"utxos\":[]}") }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).utxos(listOf(address(1))) }.httpStatus) }
    }

    @Test
    fun coinsThatCannotBeAreRefused() {
        val wrong = mapOf(
            "the same output twice" to coin(1) + "," + coin(1),
            "an amount that cannot exist" to coin(1, amount = "200000000000000001"),
            "an amount written as text" to coin(1, amount = "\"5\""),
            "an amount with a fraction" to coin(1, amount = "5.0"),
            "a negative amount" to coin(1, amount = "-5"),
            "a depth that does not fit the block" to coin(1, height = 90, deep = 12),
            "a block above the tip" to coin(1, height = 101, deep = 0),
            "a script that is not hexadecimal" to coin(1).replace("5220", "zz20"),
            "an id that is not an id" to coin(1).replace(txid(1), "xyz"),
            "no word on whether it is a block reward" to coin(1).replace(",\"coinbase\":false", ""),
            "amounts that cannot exist together" to coin(1, amount = "150000000000000000") + "," + coin(2, amount = "150000000000000000"),
        )
        for ((what, list) in wrong) {
            Fake { _, _ -> coins(list) }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).utxos(listOf(address(1))) }.httpStatus, what) }
        }
        val many = (1..10_001).joinToString(",") { coin(it) }
        Fake { _, _ -> coins(many) }.use { fake -> refused { ServerClient(fake.url).utxos(listOf(address(1))) } }
        // a block reward and a coin in the tip block are read as they are
        Fake { _, _ -> coins(coin(1, height = 100, deep = 1, more = "").replace("\"coinbase\":false", "\"coinbase\":true")) }.use { fake ->
            assertEquals(Utxo(txid(1), 0, 100000000, 100, true, "5220" + "ab".repeat(32)), ServerClient(fake.url).utxos(listOf(address(1))).utxos.single())
        }
    }

    @Test
    fun usedAddressesMustBeAmongThoseAskedAbout() {
        Fake { _, asked ->
            val named = Json.parseToJsonElement(asked.body).jsonObject["addresses"]!!.jsonArray.map { it.jsonPrimitive.content }
            Reply(200, "{\"used_addresses\":[\"${named.first()}\"],\"index\":${index()}}")
        }.use { fake ->
            val activity = ServerClient(fake.url).activity((1..120).map { address(it) })
            assertEquals(setOf(address(1), address(101)), activity.used)
            assertEquals(listOf(100, 20), fake.seen.map { Json.parseToJsonElement(it.body).jsonObject["addresses"]!!.jsonArray.size })
        }
        Fake { _, _ -> Reply(200, "{\"used_addresses\":[\"${address(7)}\"],\"index\":${index()}}") }.use { fake ->
            assertEquals(200, refused { ServerClient(fake.url).activity(listOf(address(1))) }.httpStatus)
        }
        assertFailsWith<IllegalArgumentException> { ServerClient("http://127.0.0.1:9").activity(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ServerClient("http://127.0.0.1:9").utxos(listOf("north1z\"},{\"x\":\"")) }
    }

    @Test
    fun historyIsReadPageByPageAndStartsAgainWhenTheIndexChanges() {
        val mine = listOf(address(1), address(2))
        Fake { count, _ ->
            when (count) {
                1 -> page(line(1, height = null, deep = 0) + "," + line(2, receivedAt = address(2)), cursor = "aa")
                else -> page(line(3, height = 80, deep = 21))
            }
        }.use { fake ->
            val history = ServerClient(fake.url).history(mine)
            assertEquals(listOf(txid(1), txid(2), txid(3)), history.moves.map { it.txid })
            assertEquals(ServerMove(txid(1), null, null, 5, 0, null, null, 0, 1763000000), history.moves[0])     // waiting for a block
            assertEquals(address(2), history.moves[1].receivedAt)
            assertTrue(history.complete)
            assertEquals(100, history.tip)
            val sent = fake.seen.map { Json.parseToJsonElement(it.body).jsonObject }
            assertEquals(listOf("200", "200"), sent.map { it["limit"]!!.jsonPrimitive.content })
            assertEquals(listOf(null, "aa"), sent.map { it["cursor"]?.jsonPrimitive?.content })
            assertEquals(mine, sent[0]["addresses"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        // the server says that its index changed (409), or the second page comes from another block: everything is read again
        for (change in listOf(Reply(409, "{\"message\":\"The index changed; restart history pagination.\"}"), page(line(9, height = 81, deep = 21), index = index(height = 101, hash = h2)))) {
            Fake { count, _ ->
                when (count) {
                    1 -> page(line(1), cursor = "aa")
                    2 -> change
                    3 -> page(line(1, deep = 12) + "," + line(4, height = 101, deep = 1), cursor = "bb", index = index(height = 101, hash = h2))
                    else -> page(line(3, height = 80, deep = 22), index = index(height = 101, hash = h2))
                }
            }.use { fake ->
                val history = ServerClient(fake.url).history(mine)
                assertEquals(listOf(txid(1), txid(4), txid(3)), history.moves.map { it.txid })
                assertEquals(101 to h2, history.tip to history.tipHash)
                assertEquals(4, fake.seen.size)
            }
        }
        Fake { count, _ -> if (count % 2 == 1) page(line(count), cursor = "aa") else Reply(409, "{\"message\":\"again\"}") }.use { fake ->
            assertEquals(409, refused { ServerClient(fake.url).history(mine) }.httpStatus)
            assertEquals(6, fake.seen.size)                                      // the first reading and two more
        }
        // a long history is cut after the page that reaches the number asked for, and says so
        Fake { count, _ -> page(line(count), cursor = "aa") }.use { fake ->
            val history = ServerClient(fake.url).history(mine, most = 3)
            assertEquals(3, history.moves.size)
            assertFalse(history.complete)
        }
        val wrong = mapOf(
            "an address that was not asked about" to page(line(1, receivedAt = address(9))),
            "the same transaction twice" to page(line(1) + "," + line(1)),
            "a waiting transaction with a depth" to page(line(1, height = null, deep = 3)),
            "a depth that does not fit the block" to page(line(1, height = 90, deep = 10)),
            "a further page after an empty one" to page("", cursor = "aa"),
            "a cursor that is too long" to page(line(1), cursor = "a".repeat(1025)),
        )
        for ((what, reply) in wrong) {
            Fake { _, _ -> reply }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).history(mine) }.httpStatus, what) }
        }
        assertFailsWith<IllegalArgumentException> { ServerClient("http://127.0.0.1:9").history((1..101).map { address(it) }) }
    }

    @Test
    fun aBusyServerIsWaitedForAndAskedAgainButNotForever() {
        val pauses = ArrayList<Long>()
        Fake { count, _ ->
            when (count) {
                1 -> Reply(429, "{\"message\":\"The server request rate limit was reached; retry in one second.\"}", mapOf("Retry-After" to "1"))
                2 -> Reply(503, "{\"message\":\"The index is busy; retry shortly.\"}")
                else -> Reply(200, "{\"height\":7}")
            }
        }.use { fake ->
            assertEquals(7, ServerClient(fake.url, pause = { pauses.add(it) }).tipHeight())
            assertEquals(listOf(1000L, 2250L), pauses)                             // what was asked for, then a little longer
            assertEquals(3, fake.seen.size)
        }
        pauses.clear()
        Fake { _, _ -> Reply(429, "{\"message\":\"slow down\"}", mapOf("Retry-After" to "600")) }.use { fake ->
            val error = refused { ServerClient(fake.url, maxWaitMs = 12_000, pause = { pauses.add(it) }).tipHeight() }
            assertEquals(429, error.httpStatus)
            assertEquals("slow down", error.message)
            assertTrue(pauses.all { it in 5000L..9000L }, pauses.toString())      // never longer than a few seconds at a time
            assertTrue(pauses.sum() in 12_000L..21_000L, pauses.toString())
            assertEquals(pauses.size + 1, fake.seen.size)
        }
    }

    @Test
    fun aRefusalIsPassedOnAndAnErrorAtSendingSaysNothingAboutTheTransaction() {
        val hex = "02" + "ab".repeat(100)
        Fake { _, asked ->
            when (asked.path) {
                "/v1/transactions/validate" -> Reply(200, "{\"allowed\":false,\"rejection_reason\":\"bad-txns-inputs-missingorspent\\u0007\"}")
                "/v1/transactions/broadcast" -> Reply(502, "{\"message\":\"The blockchain node is unavailable or returned an invalid response.\"}")
                else -> Reply(200, "{\"known\":true}")
            }
        }.use { fake ->
            val client = ServerClient(fake.url)
            assertEquals(ServerVerdict(false, "bad-txns-inputs-missingorspent"), client.validate(hex))
            val error = refused { client.broadcast(hex) }
            assertEquals(502, error.httpStatus)
            assertNull(error.code)
            assertTrue(client.known(txid(5)))
            assertEquals(listOf("{\"transaction_hex\":\"$hex\"}", "{\"transaction_hex\":\"$hex\"}", "{\"transaction_id\":\"${txid(5)}\"}"), fake.seen.map { it.body })
        }
        Fake { _, _ -> Reply(200, "{\"allowed\":true,\"rejection_reason\":null}") }.use { fake -> assertEquals(ServerVerdict(true, null), ServerClient(fake.url).validate(hex)) }
        Fake { _, _ -> Reply(200, "{\"transaction_id\":\"${txid(5)}\"}") }.use { fake -> assertEquals(txid(5), ServerClient(fake.url).broadcast(hex)) }
        Fake { _, _ -> Reply(200, "{\"transaction_id\":\"done\"}") }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).broadcast(hex) }.httpStatus) }
        Fake { _, _ -> Reply(200, "{\"known\":\"true\"}") }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).known(txid(5)) }.httpStatus) }
        for (bad in listOf("", "0", "zz", "AB", "00 11")) assertFailsWith<IllegalArgumentException> { ServerClient("http://127.0.0.1:9").validate(bad) }
        assertFailsWith<IllegalArgumentException> { ServerClient("http://127.0.0.1:9").known("abc") }
    }

    @Test
    fun onlyAServerAddressIsTakenAndTheQuestionsGoNowhereElse() {
        ServerClient("https://wallet.example.org")
        ServerClient("https://203.0.113.7/")
        for (bad in listOf("http://wallet.example.org", "http://203.0.113.7", "ftp://127.0.0.1", "https://example.org/api", "https://example.org/?a=1", "https://user@example.org", "wallet")) {
            assertFailsWith<IllegalArgumentException>(bad) { ServerClient(bad) }
        }
        // a server that sends the wallet elsewhere is not followed
        Fake { _, _ -> Reply(200, "{\"height\":1}") }.use { other ->
            Fake { _, _ -> Reply(307, "", mapOf("Location" to other.url + "/v1/chain/tip")) }.use { fake ->
                assertEquals(307, refused { ServerClient(fake.url).tipHeight() }.httpStatus)
                assertEquals(1, fake.seen.size)
            }
            assertEquals(0, other.seen.size)
        }
        // nothing listens: the server cannot be reached, which is not an answer
        val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(0, refused { ServerClient("http://127.0.0.1:$nobody", timeoutMs = 3000).tipHeight() }.httpStatus)
    }
}
