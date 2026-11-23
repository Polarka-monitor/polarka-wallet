package north.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The node answered, but with an error; or it could not be reached (httpStatus 0).
 * With httpStatus 0, [code] is [TIMEOUT] when the node was reached and did not answer in time.
 */
class NodeError(val httpStatus: Int, val code: Int?, message: String) : Exception(message) {
    companion object {
        const val TIMEOUT = -1
    }
}

/** One unspent output of a watched address. */
data class Utxo(val txid: String, val vout: Long, val amountSat: Long, val height: Int, val coinbase: Boolean, val script: String)

/**
 * What an address holds, as the node sees it at block height [tip].
 * A block reward counts as spendable once it is 100 blocks deep, the rule TrueNorth Core's own wallet shows.
 */
data class Balance(val tip: Int, val utxos: List<Utxo>) {
    val spendable: List<Utxo> get() = utxos.filter { !it.coinbase || it.height <= tip - MATURITY }
    val immature: List<Utxo> get() = utxos.filter { it.coinbase && it.height > tip - MATURITY }
    val spendableSat: Long get() = NodeClient.total(spendable.map { it.amountSat })
    val immatureSat: Long get() = NodeClient.total(immature.map { it.amountSat })
    val totalSat: Long get() = NodeClient.total(utxos.map { it.amountSat })

    companion object {
        const val MATURITY = 100
    }
}

data class ChainInfo(val chain: String, val blocks: Int, val headers: Int, val syncing: Boolean, val bestBlockHash: String)

data class Verdict(val allowed: Boolean, val vsize: Int?, val feeSat: Long?, val rejectReason: String?)

/**
 * A small JSON-RPC client for a TrueNorth Core node.
 *
 * It uses only the calls a wallet needs and nothing that touches a wallet on the node (the list is in the
 * tests). A node of one's own should give this user exactly those rights (-rpcwhitelist). With an empty
 * [user] no login is sent at all: that is a public node behind a gateway, which needs none.
 *
 * A password travels in every request, so the address must be https, or plain http only to this machine or
 * to a private network address (which includes a Tailscale address). Anything else is refused here.
 *
 * Nothing the node says is taken on trust beyond what it must be trusted for: amounts must be amounts that
 * can exist, ids must look like ids, an answer has a largest size, and the node's own words are cut short
 * before anybody sees them.
 */
class NodeClient(url: String, private val user: String, private val password: String, private val timeoutMs: Int = 60_000) {
    private val endpoint: URI = URI(url)
    private val scanLock: Any

    init {
        val host = endpoint.host ?: throw IllegalArgumentException("the node address has no host")
        when (endpoint.scheme) {
            "https" -> Unit
            "http" -> require(isPrivate(host)) { "plain http is only allowed to a private or Tailscale address; use https for $host" }
            else -> throw IllegalArgumentException("the node address must start with https:// or http://")
        }
        scanLock = LOCKS.computeIfAbsent(endpoint.toString()) { Any() }
    }

    private class Answer(val status: Int, val text: String, val retryAfter: Int?)

    private class TooLong : RuntimeException()

    private class Late : RuntimeException()

    /** Reads an answer to its end, as long as it is not too long and the time for the whole question ([until]) is not over. */
    private fun read(stream: InputStream?, until: Long): String {
        if (stream == null) return ""
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_ANSWER_BYTES) throw TooLong()
                out.write(buffer, 0, n)
                if (now() > until) throw Late()
            }
            return out.toByteArray().decodeToString()
        }
    }

    private fun late() = NodeError(0, NodeError.TIMEOUT, "the node did not answer in time")

    /**
     * One question to the node. Besides the time each read may wait, the whole question has a time of its own:
     * the time to connect and one read's time more. A node that sends its answer one byte at a time, each just
     * before the read gives up, is not waited for without end; that would hold a payment and the lock of the
     * app with it (ninth look, M-4). The clock and the reader are kept apart as in [ServerClient]: only one of
     * them ends a question. The connection is closed in every case; it is not kept for another question.
     */
    private fun once(method: String, params: JsonArray, readTimeoutMs: Int): Answer {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("1.0"))
            put("id", JsonPrimitive("north"))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString().encodeToByteArray()
        var asked = false
        val open = AtomicReference<HttpURLConnection?>()
        // who ended the question: nobody yet (0), its reader with the whole answer or with a failure (1), or the clock (2)
        val ended = AtomicInteger(0)
        val withinMs = timeoutMs.toLong() + readTimeoutMs
        val until = now() + withinMs
        val watch = WATCH.schedule({ if (ended.compareAndSet(0, 2)) runCatching { open.get()?.disconnect() } }, withinMs, TimeUnit.MILLISECONDS)
        try {
            val c = endpoint.toURL().openConnection() as HttpURLConnection
            open.set(c)
            if (ended.get() == 2) throw java.io.IOException("the time was over before the question was asked")
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false           // never carry the password to another address
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (user.isNotEmpty()) {
                c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$password".encodeToByteArray()))
            }
            c.outputStream.use { it.write(body) }
            asked = true
            val status = c.responseCode
            val text = read(if (status in 200..299) c.inputStream else c.errorStream, until)
            val retry = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            // the clock was first: the answer came too late to count
            if (!ended.compareAndSet(0, 1)) throw Late()
            return Answer(status, text, retry)
        } catch (e: TooLong) {
            throw NodeError(200, null, "the node sent an answer that is too long")
        } catch (e: Late) {
            throw late()
        } catch (e: SocketTimeoutException) {
            if (asked || ended.get() == 2) throw late()
            throw NodeError(0, null, "the node cannot be reached (${e.javaClass.simpleName})")
        } catch (e: java.io.IOException) {
            if (ended.get() == 2) throw late()
            throw NodeError(0, null, "the node cannot be reached (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            // a connection that is closed under its reader can fail in ways of its own
            if (ended.get() != 2) throw e
            throw late()
        } finally {
            ended.compareAndSet(0, 1)
            watch.cancel(false)
            runCatching { open.get()?.disconnect() }
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    /**
     * One call. A gateway that asks to slow down (429) or is busy (503), and a node that is still running an
     * earlier scan, are waited for a little while and asked again; everything else is answered at once.
     */
    fun call(method: String, params: JsonArray = JsonArray(emptyList()), readTimeoutMs: Int = timeoutMs): JsonElement {
        var waited = 0L
        while (true) {
            val answer = once(method, params, readTimeoutMs)
            val busy = answer.status == 429 || answer.status == 503 || "Scan already in progress" in answer.text.take(600)
            if (!busy || waited >= MAX_WAIT_MS) return result(method, answer)
            val pause = (answer.retryAfter ?: 2).coerceIn(1, 5) * 1000L
            try {
                Thread.sleep(pause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw NodeError(0, null, "the node cannot be reached (interrupted)")
            }
            waited += pause
        }
    }

    private fun result(method: String, answer: Answer): JsonElement {
        val status = answer.status
        when (status) {
            401 -> throw NodeError(status, null, "the node refused the user name or password")
            403 -> throw NodeError(status, null, "the node does not allow the call $method")
            429, 503 -> throw NodeError(status, null, "the node is busy")
        }
        val json = try {
            Json.parseToJsonElement(answer.text).jsonObject
        } catch (e: Exception) {
            throw NodeError(status, null, "the node sent an answer that is not JSON (HTTP $status)")
        }
        val error = json["error"]
        if (error != null && error !is JsonNull) {
            val e = error as? JsonObject ?: throw NodeError(status, null, "the node sent an error in an unknown form")
            val code = runCatching { e["code"]?.jsonPrimitive?.int }.getOrNull()
            throw NodeError(status, code, clean(runCatching { e["message"]?.jsonPrimitive?.content }.getOrNull() ?: "error"))
        }
        // A failure without the node's own error is no answer of the node: a gateway in front of it can send one
        // ({"message": "bad gateway"}). Taken as an empty result, it would say that the node does not hold a
        // payment or that a coin is spent (ninth look, M-4). An answer without a result is none either.
        if (status !in 200..299) throw NodeError(status, null, "the node or a gateway in front of it failed (HTTP $status)")
        return json["result"] ?: throw NodeError(status, null, "the node sent an answer without a result")
    }

    /** Runs [block] when no other scan of this app is running on the same node: the node does one scan at a time. */
    fun <T> scanning(block: () -> T): T = synchronized(scanLock) { block() }

    /** The time a scan may take: longer than an ordinary call. */
    val scanTimeoutMs: Int get() = maxOf(timeoutMs, SCAN_TIMEOUT_MS)

    fun blockCount(): Int = call("getblockcount").jsonPrimitive.int

    fun chainInfo(): ChainInfo = shaped("getblockchaininfo") {
        val r = call("getblockchaininfo").jsonObject
        ChainInfo(
            clean(r.text("chain"), 20), height(r["blocks"]!!), height(r["headers"]!!),
            r["initialblockdownload"]!!.jsonPrimitive.boolean, id(r.text("bestblockhash")),
        )
    }

    /** Everything unspent on these addresses. The node needs no wallet for it; it scans its set of unspent outputs. */
    fun balance(addresses: List<String>): Balance {
        require(addresses.isNotEmpty()) { "no address to look up" }
        require(addresses.all { a -> a.all { it.isLetterOrDigit() } }) { "an address contains a character that cannot be in an address" }
        return scanDescriptors(addresses.map { "addr($it)" }, 1)
    }

    /** Asks the node whether it would accept this transaction, without sending it. */
    fun testAccept(hex: String): Verdict = shaped("testmempoolaccept") {
        val r = call("testmempoolaccept", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(hex)) }) }).jsonArray[0].jsonObject
        val fees = r["fees"]?.jsonObject
        Verdict(
            r["allowed"]!!.jsonPrimitive.boolean, r["vsize"]?.jsonPrimitive?.int,
            fees?.get("base")?.let { sats(it) }, r["reject-reason"]?.jsonPrimitive?.content?.let { clean(it) },
        )
    }

    /** Sends a signed transaction to the network. Returns what the node answers, which should be the transaction's id. */
    fun broadcast(hex: String): String = shaped("sendrawtransaction") {
        clean(call("sendrawtransaction", buildJsonArray { add(JsonPrimitive(hex)) }).jsonPrimitive.content, 80)
    }

    /** Confirmations of a transaction the node knows (0 while it waits in the mempool), or null if it does not know it. */
    fun confirmations(txid: String): Int? = try {
        val r = call("getrawtransaction", buildJsonArray { add(JsonPrimitive(txid)); add(JsonPrimitive(1)) }).jsonObject
        r["confirmations"]?.jsonPrimitive?.int ?: 0
    } catch (e: NodeError) {
        if (e.code == -5) null else throw e
    }

    /** True if the node holds this transaction among those that wait for a block. */
    fun waiting(txid: String): Boolean {
        require(isId(txid)) { "this is not a transaction id" }
        val entry = try {
            call("getmempoolentry", buildJsonArray { add(JsonPrimitive(txid)) })
        } catch (e: NodeError) {
            if (e.code == -5) return false else throw e
        }
        // An entry, or "not held": the node's error -5, or a result of null as some stand-ins answer. A failure of a
        // gateway is refused before this (result()); any other shape is no answer.
        return when (entry) {
            is JsonObject -> true
            is JsonNull -> false
            else -> throw NodeError(200, null, "the node sent an answer in an unknown form (getmempoolentry)")
        }
    }

    /**
     * True if this output exists and nothing spends it, counting the transactions that wait for a block.
     * A coin that a waiting payment uses is still in the node's set of unspent outputs; this is how to tell.
     */
    fun unspent(txid: String, vout: Long): Boolean {
        require(isId(txid) && vout in 0..MAX_VOUT) { "this is not an output" }
        // an output that is there, or null for one that is spent or never was; anything else is no answer
        return when (call("gettxout", buildJsonArray { add(JsonPrimitive(txid)); add(JsonPrimitive(vout)); add(JsonPrimitive(true)) })) {
            is JsonObject -> true
            is JsonNull -> false
            else -> throw NodeError(200, null, "the node sent an answer in an unknown form (gettxout)")
        }
    }

    private fun JsonObject.text(name: String): String = this[name]!!.jsonPrimitive.content

    companion object {
        /** No amount and no sum of amounts can be larger: two thousand million coins, far above everything that will ever be mined. */
        const val MAX_SAT = 2_000_000_000L * 100_000_000L
        const val MAX_VOUT = 0xffffffffL
        const val MAX_ANSWER_BYTES = 16 * 1024 * 1024
        const val MAX_WAIT_MS = 45_000L
        const val SCAN_TIMEOUT_MS = 120_000
        private val LOCKS = ConcurrentHashMap<String, Any>()
        private val WATCH = ScheduledThreadPoolExecutor(1) { work -> Thread(work, "node-deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }

        /** An amount as the node prints it (a decimal number of coins) to base units, exactly; refused if it cannot exist. */
        fun sats(e: JsonElement): Long {
            val text = e.jsonPrimitive.content
            if (text.length > 40) throw NodeError(200, null, "the node sent an amount that cannot exist")
            val sat = try {
                BigDecimal(text).movePointRight(8).longValueExact()
            } catch (x: Exception) {
                throw NodeError(200, null, "the node sent an amount that cannot exist")
            }
            if (sat < 0 || sat > MAX_SAT) throw NodeError(200, null, "the node sent an amount that cannot exist")
            return sat
        }

        /** A sum of amounts, refused if it is more than can exist. */
        fun total(amounts: Iterable<Long>): Long {
            var sum = 0L
            for (a in amounts) {
                if (a < 0 || a > MAX_SAT) throw NodeError(200, null, "the node sent an amount that cannot exist")
                sum += a
                if (sum > MAX_SAT) throw NodeError(200, null, "the node sent amounts that cannot exist together")
            }
            return sum
        }

        fun isId(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

        /** A transaction id or block hash as the node writes it: 64 hexadecimal characters in lower case. */
        fun id(text: String): String = if (isId(text)) text else throw NodeError(200, null, "the node sent something that is not an id")

        fun height(e: JsonElement): Int {
            val h = e.jsonPrimitive.int
            if (h < 0 || h > 500_000_000) throw NodeError(200, null, "the node sent a height that cannot exist")
            return h
        }

        /** Words of the node, made safe to show: printable characters only, and not too many of them. */
        fun clean(text: String, max: Int = 200): String = text.filter { it >= ' ' && it != '\u007f' }.take(max)

        /** Unspent outputs as scantxoutset lists them. The same output twice, or anything malformed, refuses the whole answer. */
        fun utxos(list: JsonArray): List<Utxo> {
            val seen = HashSet<String>()
            return list.map { it.jsonObject }.map { u ->
                val txid = id(u["txid"]!!.jsonPrimitive.content)
                val vout = u["vout"]!!.jsonPrimitive.content.toLong()
                if (vout < 0 || vout > MAX_VOUT) throw NodeError(200, null, "the node sent an output number that cannot exist")
                if (!seen.add("$txid:$vout")) throw NodeError(200, null, "the node listed the same output twice")
                val script = u["scriptPubKey"]!!.jsonPrimitive.content
                if (script.length > 200 || !script.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw NodeError(200, null, "the node sent a script in an unknown form")
                Utxo(txid, vout, sats(u["amount"]!!), height(u["height"]!!), u["coinbase"]?.jsonPrimitive?.boolean ?: false, script)
            }
        }

        /** Loopback, the private ranges of RFC 1918, link-local, and 100.64.0.0/10 (Tailscale). Only literal addresses and "localhost". */
        fun isPrivate(host: String): Boolean {
            if (host == "localhost") return true
            if (!host.all { it.isDigit() || it == '.' } && !host.contains(':')) return false     // a name: never looked up here
            val a = try { InetAddress.getByName(host.trim('[', ']')) } catch (e: Exception) { return false }
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
        }
    }
}

/** Runs the reading of one answer; anything that is not in the expected form becomes one clear error. */
internal inline fun <T> shaped(what: String, block: () -> T): T = try {
    block()
} catch (e: NodeError) {
    throw e
} catch (e: Exception) {
    throw NodeError(200, null, "the node sent an answer in an unknown form ($what)")
}
