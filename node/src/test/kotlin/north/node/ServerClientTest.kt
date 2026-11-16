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
 * The shapes are those of the server's own sources (shared/src/lib.rs at v0.1.0-rc.5, the limit on requests
 * of commit 846e444, and the pages of coins and the signed history lines of the commits d72a5e1, e3376a1 and
 * 66fa1d9 of 15 November 2026).
 */
class ServerClientTest {
    private class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private class Asked(val method: String, val path: String, val body: String)

    /** What a server does with the connection behind an answer: says that it closes it, keeps it open, or drops it without a word. */
    private enum class Line { SAYS_CLOSE, KEPT, DROPPED }

    private class Fake(private val line: Line = Line.SAYS_CLOSE, private val answer: (count: Int, asked: Asked) -> Reply) : AutoCloseable {
        val seen = ArrayList<Asked>()
        val connections = java.util.concurrent.atomic.AtomicInteger()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val url: String get() = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    connections.incrementAndGet()
                    thread(isDaemon = true) { runCatching { handle(socket) } }
                }
            }
        }

        private fun handle(socket: Socket) = socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            var open: Boolean
            do {
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
                // a client that asks for the connection to be closed gets that, as from a real server
                open = line == Line.KEPT && headers["connection"]?.lowercase() != "close"
                s.getOutputStream().apply {
                    write(("HTTP/1.1 ${reply.status} x\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\n" +
                        (if (line == Line.SAYS_CLOSE || (line == Line.KEPT && !open)) "Connection: close\r\n" else "") +
                        reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").encodeToByteArray())
                    write(out)
                    flush()
                }
            } while (open)
        }

        override fun close() = server.close()
    }

    private val h1 = "11".repeat(32)
    private val h2 = "22".repeat(32)
    private fun txid(n: Int) = "%064x".format(n)
    private fun address(n: Int) = "rnorth1z" + n.toString().padStart(40, 'q')

    /** The index as the server reports it; by default at its node's tip, and with a number for its picture of the waiting transactions. */
    private fun index(
        height: Int = 100, hash: String = h1, ready: Boolean = true, nodeHeight: Int? = height, nodeHash: String? = hash, waiting: Boolean = true,
        revision: Int? = 7,
    ): String =
        "{\"ready\":$ready,\"indexed_height\":$height,\"indexed_hash\":\"$hash\",\"observed_node_height\":${nodeHeight ?: "null"}," +
            "\"observed_node_hash\":${nodeHash?.let { "\"$it\"" } ?: "null"},\"last_node_contact\":1763000000,\"pending_available\":$waiting," +
            "\"pending_observed_at\":1763000000,${revision?.let { "\"pending_revision\":$it," } ?: ""}\"max_age_seconds\":60}"

    private fun coin(n: Int, amount: String = "100000000", height: Int = 90, deep: Int = 11, more: String = ""): String =
        "{\"transaction_id\":\"${txid(n)}\",\"output_index\":0,\"amount\":$amount,\"script_pubkey\":\"5220${"ab".repeat(32)}\"," +
            "\"block_height\":$height,\"confirmations\":$deep,\"coinbase\":false$more}"

    private fun coins(list: String, index: String = index(), cursor: String? = null) =
        Reply(200, "{\"utxos\":[$list],\"next_cursor\":${cursor?.let { "\"$it\"" } ?: "null"},\"index\":$index}")

    /** A line of history: what the transaction did to the addresses asked about, and the fee of the whole transaction. */
    private fun line(n: Int, height: Int? = 90, deep: Int = 11, net: String = "5", fee: String = "1000"): String =
        "{\"transaction_id\":\"${txid(n)}\",\"net_amount\":$net,\"network_fee\":$fee,\"block_height\":${height ?: "null"},\"confirmations\":$deep,\"timestamp\":1763000000}"

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
    fun partsReadFromDifferentPicturesAreNotPutTogether() {
        // coins: a block between the two parts starts the reading again, and what was read before is not kept
        Fake { count, _ ->
            when (count) {
                1 -> coins(coin(1))
                2 -> coins(coin(2), index(height = 101, hash = h2))
                3 -> coins(coin(3, deep = 12), index(height = 101, hash = h2))
                else -> coins(coin(4, deep = 12), index(height = 101, hash = h2))
            }
        }.use { fake ->
            val found = ServerClient(fake.url).utxos((1..150).map { address(it) })
            assertEquals(listOf(txid(3), txid(4)), found.utxos.map { it.txid })
            assertEquals(101 to h2, found.tip to found.tipHash)
            assertEquals(4, fake.seen.size)
        }
        // the same block with other transactions waiting is another picture as well
        Fake { count, _ -> coins(coin(count), index(revision = if (count == 1) 7 else if (count == 2) 8 else 9)) }.use { fake ->
            val found = ServerClient(fake.url).utxos((1..150).map { address(it) })
            assertEquals(listOf(txid(3), txid(4)), found.utxos.map { it.txid })
            assertEquals(9L, found.revision)
        }
        // a chain that never holds still is given up after the first reading and two more
        Fake { count, _ -> coins(coin(count, height = 90, deep = 10 + count), index(height = 99 + count, hash = if (count % 2 == 1) h1 else h2)) }.use { fake ->
            assertEquals(409, refused { ServerClient(fake.url).utxos((1..150).map { address(it) }) }.httpStatus)
            assertEquals(6, fake.seen.size)
        }
        // activity is read once: parts from different pictures are refused, and the one who asked asks again
        Fake { count, _ -> Reply(200, "{\"used_addresses\":[],\"index\":${index(hash = if (count == 1) h1 else h2)}}") }.use { fake ->
            assertEquals(ServerClient.MOVED, refused { ServerClient(fake.url).activity((1..150).map { address(it) }) }.code)
        }
        Fake { count, _ -> Reply(200, "{\"used_addresses\":[],\"index\":${index(revision = count)}}") }.use { fake ->
            assertEquals(ServerClient.MOVED, refused { ServerClient(fake.url).activity((1..150).map { address(it) }) }.code)
        }
        // a server that does not number its pictures is compared by the block alone
        Fake { count, _ -> coins(coin(count), index(revision = null)) }.use { fake ->
            val found = ServerClient(fake.url).utxos((1..150).map { address(it) })
            assertEquals(2, found.utxos.size)
            assertNull(found.revision)
        }
    }

    @Test
    fun coinsComeInPagesAndAreReadToTheLastOne() {
        Fake { count, _ ->
            when (count) {
                1 -> coins(coin(1) + "," + coin(2), cursor = "aa")
                2 -> coins(coin(3), cursor = "bb")
                3 -> coins(coin(4))
                else -> coins(coin(5))
            }
        }.use { fake ->
            val found = ServerClient(fake.url).utxos((1..150).map { address(it) })
            assertEquals((1..5).map { txid(it) }, found.utxos.map { it.txid })
            assertEquals(7L, found.revision)
            val sent = fake.seen.map { Json.parseToJsonElement(it.body).jsonObject }
            // the first page of each part is asked for without a cursor, every further one with the cursor of the page before
            assertEquals(listOf(null, "aa", "bb", null), sent.map { it["cursor"]?.jsonPrimitive?.content })
            assertEquals(listOf(100, 100, 100, 50), sent.map { it["addresses"]!!.jsonArray.size })
            // how many coins go on a page is left to the server
            assertTrue(sent.none { "limit" in it })
        }
        // the server says that its index changed (409), or a page comes from another picture: all pages are read again
        val changes = listOf(
            Reply(409, "{\"message\":\"The index changed; restart pagination.\"}"),
            coins(coin(9, deep = 12), index(height = 101, hash = h2)),
            coins(coin(9), index(revision = 8)),
        )
        for (change in changes) {
            Fake { count, _ ->
                when (count) {
                    1 -> coins(coin(1) + "," + coin(2), cursor = "aa")
                    2 -> change
                    3 -> coins(coin(2) + "," + coin(3), cursor = "cc")
                    else -> coins(coin(4))
                }
            }.use { fake ->
                val found = ServerClient(fake.url).utxos(listOf(address(1)))
                assertEquals(listOf(txid(2), txid(3), txid(4)), found.utxos.map { it.txid })      // coin 1 was read before the change and is not kept
                assertEquals(listOf(null, "aa", null, "cc"), fake.seen.map { Json.parseToJsonElement(it.body).jsonObject["cursor"]?.jsonPrimitive?.content })
            }
        }
        Fake { count, _ -> if (count % 2 == 1) coins(coin(count), cursor = "aa") else Reply(409, "{\"message\":\"again\"}") }.use { fake ->
            assertEquals(409, refused { ServerClient(fake.url).utxos(listOf(address(1))) }.httpStatus)
            assertEquals(6, fake.seen.size)                                      // the first reading and two more
        }
        // more coins than are read for one question
        Fake { count, _ -> coins(coin(2 * count) + "," + coin(2 * count + 1), cursor = "aa") }.use { fake ->
            assertEquals(ServerClient.TOO_MANY, refused { ServerClient(fake.url).utxos(listOf(address(1)), most = 5) }.code)
            assertEquals(3, fake.seen.size)
        }
        Fake { count, _ -> coins(coin(2 * count) + "," + coin(2 * count + 1), cursor = if (count < 3) "aa" else null) }.use { fake ->
            assertEquals(6, ServerClient(fake.url).utxos(listOf(address(1)), most = 6).utxos.size)       // exactly as many as are read
        }
        val wrong = mapOf(
            "a further page after an empty one" to coins("", cursor = "aa"),
            "a cursor that is too long" to coins(coin(1), cursor = "a".repeat(1025)),
            "a cursor that is not text" to Reply(200, "{\"utxos\":[${coin(1)}],\"next_cursor\":5,\"index\":${index()}}"),
        )
        for ((what, reply) in wrong) {
            Fake { _, _ -> reply }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).utxos(listOf(address(1))) }.httpStatus, what) }
        }
        // the same coin on two pages
        Fake { count, _ -> if (count == 1) coins(coin(1), cursor = "aa") else coins(coin(1)) }.use { fake ->
            assertEquals(200, refused { ServerClient(fake.url).utxos(listOf(address(1))) }.httpStatus)
        }
        // an answer of the earlier form, without a word about further pages, is one page and the last
        Fake { _, _ -> Reply(200, "{\"utxos\":[${coin(1)}],\"index\":${index(revision = null)}}") }.use { fake ->
            assertEquals(1, ServerClient(fake.url).utxos(listOf(address(1))).utxos.size)
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
                1 -> page(line(1, height = null, deep = 0, net = "-7") + "," + line(2, fee = "null"), cursor = "aa")
                else -> page(line(3, height = 80, deep = 21))
            }
        }.use { fake ->
            val history = ServerClient(fake.url).history(mine)
            assertEquals(listOf(txid(1), txid(2), txid(3)), history.moves.map { it.txid })
            assertEquals(ServerMove(txid(1), -7, 1000, null, 0, 1763000000), history.moves[0])      // waiting for a block, and a loss
            assertEquals(ServerMove(txid(2), 5, null, 90, 11, 1763000000), history.moves[1])        // a block reward: it has no fee
            assertTrue(history.complete)
            assertEquals(100, history.tip)
            assertEquals(7L, history.revision)
            val sent = fake.seen.map { Json.parseToJsonElement(it.body).jsonObject }
            assertEquals(listOf("200", "200"), sent.map { it["limit"]!!.jsonPrimitive.content })
            assertEquals(listOf(null, "aa"), sent.map { it["cursor"]?.jsonPrimitive?.content })
            assertEquals(mine, sent[0]["addresses"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        // the server says that its index changed (409), or the second page comes from another block or from other waiting transactions: everything is read again
        val changes = listOf(
            Reply(409, "{\"message\":\"The index changed; restart pagination.\"}"), page(line(9, height = 81, deep = 21), index = index(height = 101, hash = h2)),
            page(line(9), index = index(revision = 8)),
        )
        for (change in changes) {
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
            "a line of the earlier form" to page("{\"transaction_id\":\"${txid(1)}\",\"received_at\":null,\"sent_to\":null,\"received_amount\":5,\"sent_amount\":0,\"fee\":null,\"block_height\":90,\"confirmations\":11,\"timestamp\":1763000000}"),
            "an amount written as text" to page(line(1, net = "\"5\"")),
            "an amount with a fraction" to page(line(1, net = "5.5")),
            "an amount with an exponent" to page(line(1, net = "1e3")),
            "an amount with a plus before it" to page(line(1, net = "+5")),
            "no amount" to page(line(1, net = "null")),
            "a gain that cannot exist" to page(line(1, net = "200000000000000001")),
            "a loss that cannot exist" to page(line(1, net = "-200000000000000001")),
            "a fee below zero" to page(line(1, fee = "-1")),
            "a fee that cannot exist" to page(line(1, fee = "200000000000000001")),
            "a fee written as text" to page(line(1, fee = "\"1\"")),
            "a block reward that takes away" to page(line(1, net = "-5", fee = "null")),
            "a block reward that waits for a block" to page(line(1, height = null, deep = 0, fee = "null")),
            "the same transaction twice" to page(line(1) + "," + line(1)),
            "a waiting transaction with a depth" to page(line(1, height = null, deep = 3)),
            "a depth that does not fit the block" to page(line(1, height = 90, deep = 10)),
            "a further page after an empty one" to page("", cursor = "aa"),
            "a cursor that is too long" to page(line(1), cursor = "a".repeat(1025)),
        )
        for ((what, reply) in wrong) {
            Fake { _, _ -> reply }.use { fake -> assertEquals(200, refused { ServerClient(fake.url).history(mine) }.httpStatus, what) }
        }
        // the largest gain and the largest loss that can exist, and a line that leaves the addresses as they were
        Fake { _, _ -> page(line(1, net = "-200000000000000000") + "," + line(2, net = "200000000000000000") + "," + line(3, net = "0", fee = "0")) }.use { fake ->
            assertEquals(listOf(-200000000000000000, 200000000000000000, 0), ServerClient(fake.url).history(mine).moves.map { it.netSat })
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

    @Test
    fun theConnectionIsLeftOpenForTheNextQuestion() {
        val answers = { _: Int, asked: Asked -> if (asked.method == "POST") Reply(200, "{\"known\":true}") else Reply(200, "{\"height\":7}") }
        Fake(Line.KEPT, answers).use { fake ->
            val client = ServerClient(fake.url)
            assertEquals(7, client.tipHeight())
            assertTrue(client.known(txid(5)))
            assertEquals(7, client.tipHeight())
            assertEquals(3 to 1, fake.seen.size to fake.connections.get())
        }
        // a server that drops the connection behind every answer without saying so is asked again on a new one, each question once
        Fake(Line.DROPPED, answers).use { fake ->
            val client = ServerClient(fake.url)
            assertEquals(7, client.tipHeight())
            assertTrue(client.known(txid(5)))
            assertEquals(7, client.tipHeight())
            assertEquals(listOf("GET", "POST", "GET"), fake.seen.map { it.method })
        }
        // an answer that is given up half read ends the connection: the next question goes out on a new one and gets its own answer
        Fake(Line.KEPT) { count, _ -> if (count == 1) Reply(200, "x".repeat(2 * ServerClient.MAX_ANSWER_BYTES)) else Reply(200, "{\"height\":7}") }.use { fake ->
            val client = ServerClient(fake.url)
            refused { client.tipHeight() }
            assertEquals(7, client.tipHeight())
            assertEquals(2 to 2, fake.seen.size to fake.connections.get())
        }
    }

    /**
     * The sixth look of the reviewer, L3. A server that sends its answer a byte at a time is never late for the
     * timeout of a read. The question as a whole has a limit of its own: when that is over, the question fails
     * as one that was not answered in time, and the next question gets its own answer.
     */
    @Test
    fun aQuestionDoesNotWaitForItsTurnLongerThanItsTime() {
        // The tip is answered after two seconds, everything else at once.
        Fake { _, asked ->
            if (asked.path == "/v1/chain/tip") {
                Thread.sleep(2000)
                Reply(200, "{\"height\":7}")
            } else Reply(200, "{\"known\":true}")
        }.use { fake ->
            val slow = ServerClient(fake.url)
            val quick = ServerClient(fake.url)          // another client of the same server: the two share its turn
            val tip = java.util.concurrent.atomic.AtomicReference<Result<Int>>()
            val asker = thread { tip.set(runCatching { slow.tipHeight() }) }
            Thread.sleep(300)                           // by now the slow question has its turn
            val started = System.nanoTime()
            val e = assertFailsWith<NodeError> { quick.known(txid(5), 500) }
            val took = (System.nanoTime() - started) / 1_000_000
            assertEquals(0 to NodeError.TIMEOUT, e.httpStatus to e.code, e.message)
            assertTrue(took in 400..1300, "the question waited $took ms for its turn")
            assertEquals(listOf("/v1/chain/tip"), synchronized(fake.seen) { fake.seen.map { it.path } }, "a question that never had its turn was sent")
            // one that is given time enough waits for its turn and is answered
            assertTrue(quick.known(txid(5), 4000))
            asker.join(5000)
            assertEquals(7, tip.get().getOrThrow())
            assertEquals(2, synchronized(fake.seen) { fake.seen.size })
        }
    }

    @Test
    fun anAnswerThatComesALittleAtATimeIsNotWaitedForWithoutEnd() {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val slow = java.util.concurrent.atomic.AtomicBoolean(true)
        val slowHead = java.util.concurrent.atomic.AtomicBoolean(false)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    runCatching {
                        socket.use { s ->
                            val input = BufferedInputStream(s.getInputStream())
                            val asked = StringBuilder()
                            while (!asked.endsWith("\r\n\r\n")) {
                                val b = input.read()
                                if (b < 0) return@use
                                asked.append(b.toChar())
                            }
                            val body = "{\"height\":7}".encodeToByteArray()
                            val head = "HTTP/1.1 200 x\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray()
                            val out = s.getOutputStream()
                            for (b in head) {
                                if (slowHead.get()) Thread.sleep(20)
                                out.write(b.toInt())
                                if (slowHead.get()) out.flush()
                            }
                            out.flush()
                            for (b in body) {
                                if (slow.get()) Thread.sleep(150)
                                out.write(b.toInt())
                                out.flush()
                            }
                        }
                    }
                }
            }
        }
        server.use {
            val url = "http://127.0.0.1:${server.localPort}"
            fun timed(what: () -> Unit): Long {
                val started = System.nanoTime()
                val e = assertFailsWith<NodeError> { what() }
                assertEquals(0 to NodeError.TIMEOUT, e.httpStatus to e.code, e.message)
                return (System.nanoTime() - started) / 1_000_000
            }
            // every read gets a byte well within its second; the whole answer would take almost two seconds
            val client = ServerClient(url, timeoutMs = 1000, deadlineMs = 600)
            val body = timed { client.tipHeight() }
            assertTrue(body in 500..1400, "the question took $body ms")
            // one question may be given less time than the client gives by itself
            val patient = ServerClient(url, timeoutMs = 1000)
            val single = timed { patient.known(txid(5), 400) }
            assertTrue(single in 300..1200, "the question took $single ms")
            // the same while the beginning of the answer is still on its way (about 80 bytes, 20 ms each)
            slow.set(false)
            slowHead.set(true)
            val head = timed { client.tipHeight() }
            assertTrue(head in 500..1400, "the question took $head ms")
            // without the delay the same server is read to its end
            slowHead.set(false)
            assertEquals(7, client.tipHeight())
            // and no time at all is no question at all
            timed { patient.known(txid(5), 0) }
        }
        // A busy server asks for a second. A question that has less than a second left is not asked again: "busy" is its answer.
        Fake { _, _ -> Reply(429, "{\"message\":\"slow down\"}", mapOf("Retry-After" to "1")) }.use { fake ->
            val waits = ArrayList<Long>()
            val client = ServerClient(fake.url, pause = { waits.add(it) })
            assertEquals(429, assertFailsWith<NodeError> { client.known(txid(5), 600) }.httpStatus)
            assertEquals(1 to emptyList<Long>(), fake.seen.size to waits)
            // with time enough it is waited for as before, until waiting has taken as long as the client allows
            assertEquals(429, assertFailsWith<NodeError> { client.known(txid(5)) }.httpStatus)
            assertTrue(waits.isNotEmpty() && fake.seen.size > 2)
        }
    }

    /**
     * The reviewer's look at 0.6.1, R1. A list that was cut by a change of the server's picture in every one of its
     * readings is refused in the way that makes the one who asked ask again; before, it failed like any error.
     */
    @Test
    fun aListThatKeptChangingIsRefusedAsOneToAskForAgain() {
        Fake { count, _ -> if (count % 2 == 1) coins(coin(count), cursor = "aa") else Reply(409, "{\"message\":\"again\"}") }.use { fake ->
            val error = refused { ServerClient(fake.url).utxos(listOf(address(1))) }
            assertEquals(409, error.httpStatus)
            assertEquals(ServerClient.MOVED, error.code)
        }
        Fake { count, _ -> if (count % 2 == 1) page(line(count), cursor = "aa") else Reply(409, "{\"message\":\"again\"}") }.use { fake ->
            val error = refused { ServerClient(fake.url).history(listOf(address(1))) }
            assertEquals(409, error.httpStatus)
            assertEquals(ServerClient.MOVED, error.code)
        }
        // a refusal of another kind is passed on as it is, and nothing is asked a second time
        Fake { _, _ -> Reply(400, "{\"message\":\"invalid UTXO cursor\"}") }.use { fake ->
            val error = refused { ServerClient(fake.url).utxos(listOf(address(1))) }
            assertEquals(400, error.httpStatus)
            assertNull(error.code)
            assertEquals(1, fake.seen.size)
        }
    }

    /**
     * The reviewer's look at 0.6.1, R2. A server that hands out one coin or one line to a page, each with a cursor
     * to the next, was followed for as many pages as it liked: more than twenty thousand questions for one list.
     */
    @Test
    fun aServerThatHandsOutALittleAtATimeIsNotFollowedWithoutEnd() {
        // coins: the real server gives a thousand to a page; two hundred pages are followed, and no more
        Fake { count, _ -> coins(coin(count), cursor = "aa") }.use { fake ->
            val error = refused { ServerClient(fake.url).utxos(listOf(address(1))) }
            assertEquals(200, error.httpStatus)
            assertNull(error.code)
            assertTrue("more pages than the app reads" in error.message.orEmpty(), error.message)
            assertEquals(ServerClient.MAX_COIN_PAGES + 1, fake.seen.size)
        }
        // every part of the addresses has a page of its own beside those
        Fake { count, _ -> coins(coin(count), cursor = if (count <= ServerClient.MAX_COIN_PAGES) "aa" else null) }.use { fake ->
            val found = ServerClient(fake.url).utxos((1..150).map { address(it) })
            assertEquals(ServerClient.MAX_COIN_PAGES + 2, found.utxos.size)
            assertEquals(ServerClient.MAX_COIN_PAGES + 2, fake.seen.size)
        }
        // History: an honest server fills every page but the last, so two thousand lines take ten pages. Twenty-two
        // are followed; then the history ends where it is and says that it is cut short.
        Fake { count, _ -> page(line(count), cursor = "aa") }.use { fake ->
            val history = ServerClient(fake.url).history(listOf(address(1)))
            assertFalse(history.complete)
            assertEquals(22, history.moves.size)
            assertEquals(22, fake.seen.size)
        }
        // a history that comes in full pages is read as far as before
        Fake { count, _ -> page((1..ServerClient.PAGE).joinToString(",") { line(1000 * count + it) }, cursor = "aa") }.use { fake ->
            val history = ServerClient(fake.url).history(listOf(address(1)))
            assertEquals(2000, history.moves.size)
            assertFalse(history.complete)
            assertEquals(10, fake.seen.size)
        }
    }

    /** The same finding: a list in pages is many questions, and the reading as a whole has a time limit of its own. */
    @Test
    fun aListInPagesIsNotReadForLongerThanItsTime() {
        // every page comes after a third of a second, and the whole reading may take one second
        Fake { count, _ -> Thread.sleep(350); coins(coin(count), cursor = "aa") }.use { fake ->
            val started = System.nanoTime()
            val error = refused { ServerClient(fake.url, readMs = 1_000).utxos(listOf(address(1))) }
            val took = (System.nanoTime() - started) / 1_000_000
            assertEquals(0, error.httpStatus)
            assertEquals(NodeError.TIMEOUT, error.code)
            assertTrue(took in 900..3_000, "took $took ms")
            assertTrue(fake.seen.size in 2..4, "pages asked for: ${fake.seen.size}")
        }
        Fake { count, _ -> Thread.sleep(350); page(line(count), cursor = "aa") }.use { fake ->
            val error = refused { ServerClient(fake.url, readMs = 1_000).history(listOf(address(1))) }
            assertEquals(0, error.httpStatus)
            assertEquals(NodeError.TIMEOUT, error.code)
        }
        // New starts are in the same time: a list that is cut by a change again and again is not read three times as
        // long. When the time runs out in a reading that a change has cut, the picture that kept moving is named as
        // the reason, not a server that is late (the reviewer's second look, R5).
        Fake { count, _ -> Thread.sleep(350); if (count % 2 == 1) coins(coin(count), cursor = "aa") else Reply(409, "{\"message\":\"again\"}") }.use { fake ->
            val started = System.nanoTime()
            val error = refused { ServerClient(fake.url, readMs = 1_000).utxos(listOf(address(1))) }
            assertEquals(409, error.httpStatus)
            assertEquals(ServerClient.MOVED, error.code)
            assertTrue("until the time to read them was over" in error.message.orEmpty(), error.message)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
        Fake { count, _ -> Thread.sleep(350); if (count % 2 == 1) page(line(count), cursor = "aa") else Reply(409, "{\"message\":\"again\"}") }.use { fake ->
            val error = refused { ServerClient(fake.url, readMs = 1_000).history(listOf(address(1))) }
            assertEquals(409, error.httpStatus)
            assertEquals(ServerClient.MOVED, error.code)
            assertTrue("until the time to read it was over" in error.message.orEmpty(), error.message)
        }
        // a reading that is quick has all the time it needs
        Fake { count, _ -> coins(coin(count), cursor = if (count < 5) "aa" else null) }.use { fake ->
            assertEquals(5, ServerClient(fake.url, readMs = 10_000).utxos(listOf(address(1))).utxos.size)
        }
    }

    /**
     * The reviewer's look at 0.6.1, N1. The real server answers 503 for a moment whenever it takes a new picture of
     * the waiting transactions, and after that it refuses the cursor of the picture before (409). A reading that
     * meets both in its middle waits, asks again, starts from the first page and ends in the new picture.
     */
    @Test
    fun aReadingThatMeetsABusyServerAndThenANewPictureStartsAgain() {
        val pauses = ArrayList<Long>()
        Fake { count, _ ->
            when (count) {
                1 -> coins(coin(1) + "," + coin(2), cursor = "aa")
                2 -> Reply(503, "{\"message\":\"index is synchronizing or stale; retry shortly\"}")
                3 -> Reply(409, "{\"message\":\"The index changed; restart pagination.\"}")
                4 -> coins(coin(2) + "," + coin(3), index(revision = 8), cursor = "bb")
                else -> coins(coin(4), index(revision = 8))
            }
        }.use { fake ->
            val found = ServerClient(fake.url, pause = { pauses.add(it) }).utxos(listOf(address(1)))
            assertEquals(listOf(txid(2), txid(3), txid(4)), found.utxos.map { it.txid })     // coin 1 was read in the picture before and is not kept
            assertEquals(8L, found.revision)
            assertEquals(listOf(2000L), pauses)                                    // the busy server was waited for once
            assertEquals(listOf(null, "aa", "aa", null, "bb"), fake.seen.map { Json.parseToJsonElement(it.body).jsonObject["cursor"]?.jsonPrimitive?.content })
        }
        Fake { count, _ ->
            when (count) {
                1 -> page(line(1), cursor = "aa")
                2 -> Reply(503, "{\"message\":\"index is synchronizing or stale; retry shortly\"}")
                3 -> Reply(409, "{\"message\":\"The index changed; restart pagination.\"}")
                4 -> page(line(5, height = null, deep = 0) + "," + line(1), cursor = "bb", index = index(revision = 8))
                else -> page(line(3, height = 80, deep = 21), index = index(revision = 8))
            }
        }.use { fake ->
            val history = ServerClient(fake.url, pause = { pauses.add(it) }).history(listOf(address(1)))
            assertEquals(listOf(txid(5), txid(1), txid(3)), history.moves.map { it.txid })
            assertEquals(8L, history.revision)
            assertEquals(5, fake.seen.size)
        }
    }
}
