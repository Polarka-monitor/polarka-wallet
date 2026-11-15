package north.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** The coins on some addresses, as the server's index shows them at block [tip]. */
data class ServerCoins(val tip: Int, val tipHash: String, val utxos: List<Utxo>)

/** Which of the addresses that were asked about have ever been paid to, in a block or by a transaction that waits for one. */
data class ServerActivity(val tip: Int, val tipHash: String, val used: Set<String>)

/**
 * One transaction as the server sums it up against the addresses it was asked about.
 *
 * [receivedSat] is what its outputs pay to those addresses. With no input from them it is an incoming payment
 * and [receivedAt] names the first address paid. With every input from them, [sentSat] is what goes elsewhere
 * and [feeSat] is known. With inputs of both kinds, [sentSat] is the own input less what came back, never
 * below zero, and the fee is not known. A transaction that waits for a block has no [height].
 */
data class ServerMove(
    val txid: String, val receivedAt: String?, val sentTo: String?, val receivedSat: Long, val sentSat: Long, val feeSat: Long?,
    val height: Int?, val confirmations: Int, val time: Long?,
)

/** [complete] is false when the history was longer than was asked for; the newest lines are there. */
data class ServerHistory(val tip: Int, val tipHash: String, val moves: List<ServerMove>, val complete: Boolean)

data class ServerFee(val satPerVb: Long, val targetBlocks: Int)

data class ServerVerdict(val allowed: Boolean, val reason: String?)

/**
 * A client for a wallet server that speaks the Northbound API, version 1 (github.com/nova9/northbound).
 *
 * Such a server stands in front of a TrueNorth Core node and keeps an index of addresses. The wallet names
 * its addresses to it and gets coins and history back; it hands over signed transactions. No secret goes to
 * the server and none is needed to use it. What the server learns is every address it is asked about, and
 * where the questions come from.
 *
 * The address must be https, or plain http to this machine or a private network address. Redirects are not
 * followed. As with a node, nothing is taken on trust beyond what must be: numbers are whole numbers that
 * can exist, ids look like ids, lists and answers have a largest size, a coin's depth must fit its block,
 * and an index that is not at its node's tip is not believed. An error is a [NodeError] with the HTTP status.
 *
 * The server answers at most a hundred addresses in one request. Coins and activity are asked for in parts
 * and put together, which is exact; all parts must come from the same block, or the answer is refused with
 * [MOVED]. History cannot be put together from parts (each line is summed up against the addresses of its
 * own request), so [history] takes at most a hundred addresses.
 *
 * A server that asks to slow down (429) or is busy (503) is waited for and asked again, for [maxWaitMs] at most.
 *
 * One question, from the wait for its turn to the last byte of its answer and with the waiting for a busy
 * server, takes [deadlineMs] at most. The timeouts of a connection do not see to that: a server that keeps sending a
 * little is never late for them. When the time is over the connection is closed and the question fails as
 * one that was not answered in time.
 */
class ServerClient(
    url: String, private val timeoutMs: Int = 30_000, private val maxWaitMs: Long = MAX_WAIT_MS,
    private val pause: (Long) -> Unit = { Thread.sleep(it) }, private val deadlineMs: Long = DEADLINE_MS,
) {
    private val base: String
    private val lock: ReentrantLock

    init {
        val uri = URI(url.trim())
        val host = uri.host ?: throw IllegalArgumentException("the server address has no host")
        when (uri.scheme) {
            "https" -> Unit
            "http" -> require(NodeClient.isPrivate(host)) { "plain http is only allowed to a private address; use https for $host" }
            else -> throw IllegalArgumentException("the server address must start with https:// or http://")
        }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) { "the server address must be an address and nothing else" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "the server address must not have a path" }
        base = url.trim().trimEnd('/')
        lock = LOCKS.computeIfAbsent(base) { ReentrantLock(true) }      // in the order of asking: nobody is passed over
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

    private fun late() = NodeError(0, NodeError.TIMEOUT, "the server did not answer in time")

    private fun once(path: String, body: String?, withinMs: Long): Answer {
        if (withinMs <= 0) throw late()
        var asked = false
        val open = AtomicReference<HttpURLConnection?>()
        // Who ended the question: nobody yet (0), its reader with the whole answer or with a failure (1), or
        // the clock (2). Only one of them does. So the clock never closes a connection whose answer was read
        // to its end, which the next question may already be using.
        val ended = AtomicInteger(0)
        val until = now() + withinMs
        // When the time for the whole question is over, the connection is closed under whatever waits on it. An
        // answer that is being read is given up by its reader as well, between two reads: closing a connection
        // from outside does not end every read at once.
        val watch = WATCH.schedule({ if (ended.compareAndSet(0, 2)) runCatching { open.get()?.disconnect() } }, withinMs, TimeUnit.MILLISECONDS)
        try {
            val c = URI(base + path).toURL().openConnection() as HttpURLConnection
            open.set(c)
            if (ended.get() == 2) throw java.io.IOException("the time was over before the question was asked")
            c.requestMethod = if (body == null) "GET" else "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false           // the wallet's addresses go to the server that was named, and nowhere else
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.encodeToByteArray()) }
            } else {
                c.connect()
            }
            asked = true
            val status = c.responseCode
            val text = read(if (status in 200..299) c.inputStream else c.errorStream, until)
            val retry = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            // the clock was first: the answer came too late to count, and its connection is being closed
            if (!ended.compareAndSet(0, 1)) throw Late()
            // The answer was read to its end, so the connection is left open for the next question. A look asks
            // several, and with a server far away a new connection costs twice what a question costs.
            return Answer(status, text, retry)
        } catch (e: TooLong) {
            open.get()?.disconnect()
            throw NodeError(200, null, "the server sent an answer that is too long")
        } catch (e: Late) {
            open.get()?.disconnect()
            throw late()
        } catch (e: SocketTimeoutException) {
            open.get()?.disconnect()
            if (asked || ended.get() == 2) throw late()
            throw NodeError(0, null, "the server cannot be reached (${e.javaClass.simpleName})")
        } catch (e: java.io.IOException) {
            open.get()?.disconnect()
            if (ended.get() == 2) throw late()
            throw NodeError(0, null, "the server cannot be reached (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            // a connection that is closed under its reader can fail in ways of its own
            if (ended.get() != 2) throw e
            open.get()?.disconnect()
            throw late()
        } finally {
            ended.compareAndSet(0, 1)
            watch.cancel(false)
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    /**
     * One question at a time to one server: the requests of all its users share one budget. [withinMs] is the
     * time the question may take as a whole, the wait for its turn included.
     */
    private fun call(path: String, body: String? = null, withinMs: Long = deadlineMs): JsonObject {
        val until = now() + withinMs
        // The wait for its turn is part of the question's time. Behind a slow question it would otherwise wait
        // for as long as that one may take, whatever time it was given itself.
        val mine = try {
            lock.tryLock(maxOf(0L, withinMs), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NodeError(0, null, "the server cannot be reached (interrupted)")
        }
        if (!mine) throw late()
        try {
            return asked(path, body, until)
        } finally {
            lock.unlock()
        }
    }

    private fun asked(path: String, body: String?, until: Long): JsonObject {
        var waited = 0L
        while (true) {
            val answer = once(path, body, until - now())
            val busy = answer.status == 429 || answer.status == 503
            if (!busy || waited >= maxWaitMs) return parsed(answer)
            // what the server asks for, and a little longer each time
            val ms = (answer.retryAfter ?: if (answer.status == 429) 1 else 2).coerceIn(1, 5) * 1000L + waited / 4
            // no time left to wait and ask once more: "busy" is the answer
            if (now() + ms >= until) return parsed(answer)
            try {
                pause(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw NodeError(0, null, "the server cannot be reached (interrupted)")
            }
            waited += ms
        }
    }

    private fun parsed(answer: Answer): JsonObject {
        val json = try { Json.parseToJsonElement(answer.text) as? JsonObject } catch (e: Exception) { null }
        if (answer.status in 200..299) return json ?: throw NodeError(answer.status, null, "the server sent an answer that is not JSON (HTTP ${answer.status})")
        val said = (json?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val fallback = if (answer.status == 429 || answer.status == 503) "the server is busy" else "the server answered HTTP ${answer.status}"
        throw NodeError(answer.status, null, NodeClient.clean(said?.ifBlank { null } ?: fallback))
    }

    // ------------------------------------------------------------------------------------------ reading an answer

    private fun unknown(what: String) = NodeError(200, null, "the server sent an answer in an unknown form ($what)")

    private fun wholeOf(p: JsonPrimitive, name: String, max: Long): Long {
        val text = p.content
        if (p.isString || text.isEmpty() || text.length > 19 || !text.all { it in '0'..'9' }) throw unknown(name)
        val value = text.toLongOrNull() ?: throw unknown(name)
        if (value > max) throw NodeError(200, null, "the server sent a number that cannot exist ($name)")
        return value
    }

    /** A whole number that is not negative, written as a number. Anything else refuses the answer. */
    private fun JsonObject.whole(name: String, max: Long): Long = wholeOf(this[name] as? JsonPrimitive ?: throw unknown(name), name, max)

    private fun JsonObject.wholeOrNull(name: String, max: Long): Long? = when (val e = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> wholeOf(e, name, max)
        else -> throw unknown(name)
    }

    private fun JsonObject.flag(name: String): Boolean {
        val p = this[name] as? JsonPrimitive ?: throw unknown(name)
        if (p.isString) throw unknown(name)
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> throw unknown(name)
        }
    }

    private fun JsonObject.textOrNull(name: String, max: Int): String? = when (val e = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString && e.content.length <= max) e.content else throw unknown(name)
        else -> throw unknown(name)
    }

    private fun JsonObject.text(name: String, max: Int): String = textOrNull(name, max) ?: throw unknown(name)

    private fun JsonObject.list(name: String, max: Int): JsonArray {
        val list = this[name] as? JsonArray ?: throw unknown(name)
        if (list.size > max) throw NodeError(200, null, "the server sent a list that is too long ($name)")
        return list
    }

    private fun id(text: String): String = if (NodeClient.isId(text)) text else throw NodeError(200, null, "the server sent something that is not an id")

    /**
     * The block the answer was read at. An index that is not ready, does not know the waiting transactions,
     * or is not at the tip its own node reports, is refused: what it shows may be out of date.
     */
    private fun moment(answer: JsonObject): Pair<Int, String> {
        val index = answer["index"] as? JsonObject ?: throw unknown("index")
        val height = index.wholeOrNull("indexed_height", MAX_HEIGHT)?.toInt()
        val hash = index.textOrNull("indexed_hash", 64)?.let { id(it) }
        val nodeHeight = index.wholeOrNull("observed_node_height", MAX_HEIGHT)?.toInt()
        val nodeHash = index.textOrNull("observed_node_hash", 64)?.let { id(it) }
        val current = index.flag("ready") && index.flag("pending_available") && height != null && hash != null && height == nodeHeight && hash == nodeHash
        if (!current) throw NodeError(503, null, "the server's index is not up to date")
        return height!! to hash!!
    }

    private fun same(before: Pair<Int, String>?, now: Pair<Int, String>): Pair<Int, String> {
        if (before != null && before != now) throw NodeError(200, MOVED, "a block arrived between two parts of one question to the server")
        return now
    }

    // ------------------------------------------------------------------------------------------ asking

    private fun parts(addresses: List<String>): List<List<String>> {
        require(addresses.isNotEmpty()) { "no address to ask about" }
        require(addresses.all { a -> a.length in 8..MAX_ADDRESS && a.all { it.isLetterOrDigit() && it.code < 128 } }) { "this is not an address" }
        return addresses.distinct().chunked(MAX_ADDRESSES)
    }

    private fun naming(addresses: List<String>, more: Map<String, JsonPrimitive> = emptyMap()): String = buildJsonObject {
        put("addresses", buildJsonArray { addresses.forEach { add(JsonPrimitive(it)) } })
        more.forEach { (name, value) -> put(name, value) }
    }.toString()

    private fun carrying(hex: String): String {
        require(hex.length in 2..MAX_HEX && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "this is not a transaction" }
        return buildJsonObject { put("transaction_hex", JsonPrimitive(hex)) }.toString()
    }

    /** The height of the node behind the server. Good for seeing whether a server answers at all. */
    fun tipHeight(): Int = call("/v1/chain/tip").whole("height", MAX_HEIGHT).toInt()

    /** The fee rate the server would use. A wallet takes it as a hint and keeps its own upper bound. */
    fun feeEstimate(): ServerFee {
        val answer = call("/v1/fees/estimate")
        val rate = answer.whole("atomic_units_per_vbyte", MAX_RATE)
        if (rate < 1) throw NodeError(200, null, "the server sent a number that cannot exist (atomic_units_per_vbyte)")
        return ServerFee(rate, answer.whole("target_blocks", 10_000).toInt())
    }

    /** Which of these addresses were ever paid to. Any number of addresses; the answer is for one block. */
    fun activity(addresses: List<String>): ServerActivity {
        val used = HashSet<String>()
        var at: Pair<Int, String>? = null
        for (part in parts(addresses)) {
            val answer = call("/v1/addresses/activity", naming(part))
            at = same(at, moment(answer))
            val named = part.toSet()
            for (e in answer.list("used_addresses", part.size)) {
                val address = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw unknown("used_addresses")
                if (address !in named) throw NodeError(200, null, "the server named an address it was not asked about")
                used.add(address)
            }
        }
        return ServerActivity(at!!.first, at.second, used)
    }

    /**
     * The coins on these addresses that are in a block and that nothing spends, not even a transaction that
     * waits for a block. Any number of addresses; the answer is for one block. Whose the scripts are is for
     * the caller to check: the server is trusted for what exists, not for what belongs to the wallet.
     */
    fun utxos(addresses: List<String>): ServerCoins {
        val coins = ArrayList<Utxo>()
        val seen = HashSet<String>()
        var at: Pair<Int, String>? = null
        for (part in parts(addresses)) {
            val answer = call("/v1/utxos", naming(part))
            val now = moment(answer)
            at = same(at, now)
            for (e in answer.list("utxos", MAX_UTXOS)) {
                val u = e as? JsonObject ?: throw unknown("utxos")
                val txid = id(u.text("transaction_id", 64))
                val vout = u.whole("output_index", NodeClient.MAX_VOUT)
                if (!seen.add("$txid:$vout")) throw NodeError(200, null, "the server listed the same output twice")
                val script = u.text("script_pubkey", 200)
                if (script.isEmpty() || !script.all { it in '0'..'9' || it in 'a'..'f' }) throw unknown("script_pubkey")
                val height = u.whole("block_height", MAX_HEIGHT).toInt()
                val deep = u.whole("confirmations", MAX_HEIGHT + 1)
                if (height > now.first || deep != (now.first - height + 1).toLong()) {
                    throw NodeError(200, null, "the server sent a coin whose depth does not fit its block")
                }
                coins.add(Utxo(txid, vout, u.whole("amount", NodeClient.MAX_SAT), height, u.flag("coinbase"), script))
            }
        }
        try {
            NodeClient.total(coins.map { it.amountSat })        // together they must be an amount that can exist
        } catch (e: NodeError) {
            throw NodeError(200, null, "the server sent amounts that cannot exist together")
        }
        return ServerCoins(at!!.first, at.second, coins)
    }

    private fun move(t: JsonObject, tip: Int, named: Set<String>): ServerMove {
        val txid = id(t.text("transaction_id", 64))
        val receivedAt = t.textOrNull("received_at", MAX_ADDRESS)
        if (receivedAt != null && receivedAt !in named) throw NodeError(200, null, "the server named an address it was not asked about")
        val sentTo = t.textOrNull("sent_to", MAX_ADDRESS)?.let { NodeClient.clean(it, MAX_ADDRESS) }
        val height = t.wholeOrNull("block_height", MAX_HEIGHT)?.toInt()
        val deep = t.whole("confirmations", MAX_HEIGHT + 1)
        val fits = if (height == null) deep == 0L else height <= tip && deep == (tip - height + 1).toLong()
        if (!fits) throw NodeError(200, null, "the server sent a transaction whose depth does not fit its block")
        return ServerMove(
            txid, receivedAt, sentTo, t.whole("received_amount", NodeClient.MAX_SAT), t.whole("sent_amount", NodeClient.MAX_SAT),
            t.wholeOrNull("fee", NodeClient.MAX_SAT), height, deep.toInt(), t.wholeOrNull("timestamp", MAX_TIME),
        )
    }

    /**
     * The history of these addresses, newest first, the waiting transactions before the ones in blocks. At
     * most a hundred addresses, because each line is summed up against the addresses of the request it came
     * from. It is read page by page; when a block or a waiting transaction arrives in between, the server
     * says so and the reading starts again, so that all lines belong to one moment. At most [most] lines are
     * read (rounded up to a page).
     */
    fun history(addresses: List<String>, most: Int = 2000): ServerHistory {
        val asked = parts(addresses)
        require(asked.size == 1) { "history is exact for at most $MAX_ADDRESSES addresses in one request" }
        require(most > 0) { "nothing to read" }
        val named = asked[0].toSet()
        var restarts = 0
        while (true) {
            val moves = ArrayList<ServerMove>()
            val seen = HashSet<String>()
            var at: Pair<Int, String>? = null
            var cursor: String? = null
            var changed = false
            var complete = true
            while (true) {
                val more = HashMap<String, JsonPrimitive>()
                more["limit"] = JsonPrimitive(PAGE)
                if (cursor != null) more["cursor"] = JsonPrimitive(cursor)
                val answer = try {
                    call("/v1/transactions", naming(asked[0], more))
                } catch (e: NodeError) {
                    if (e.httpStatus != 409) throw e
                    changed = true
                    break
                }
                val now = moment(answer)
                if (at != null && at != now) {
                    changed = true
                    break
                }
                at = now
                val page = answer.list("transactions", PAGE)
                for (e in page) {
                    val line = move(e as? JsonObject ?: throw unknown("transactions"), now.first, named)
                    if (!seen.add(line.txid)) throw NodeError(200, null, "the server listed the same transaction twice")
                    moves.add(line)
                }
                cursor = answer.textOrNull("next_cursor", MAX_CURSOR)
                if (cursor == null) break
                if (page.isEmpty()) throw unknown("next_cursor")        // a page with nothing on it and another one after it would never end
                if (moves.size >= most) {
                    complete = false
                    break
                }
            }
            if (!changed) return ServerHistory(at!!.first, at.second, moves, complete)
            if (++restarts > MAX_RESTARTS) throw NodeError(409, null, "the server's history kept changing while it was read")
        }
    }

    /** Asks whether the node behind the server would take this transaction. The server sees the whole signed transaction. */
    fun validate(hex: String, withinMs: Long = deadlineMs): ServerVerdict {
        val answer = call("/v1/transactions/validate", carrying(hex), withinMs)
        return ServerVerdict(answer.flag("allowed"), answer.textOrNull("rejection_reason", 2000)?.let { NodeClient.clean(it) })
    }

    /**
     * Hands a signed transaction to the network. Returns the id the server answers with. Every error of the
     * node behind the server comes back as 502, a refusal as well as a node that is down: an error here says
     * nothing about what became of the transaction, and [known] has to be asked.
     */
    fun broadcast(hex: String, withinMs: Long = deadlineMs): String =
        id(call("/v1/transactions/broadcast", carrying(hex), withinMs).text("transaction_id", 64))

    /** True if the server or its node knows this transaction, in a block or waiting for one. */
    fun known(txid: String, withinMs: Long = deadlineMs): Boolean {
        require(NodeClient.isId(txid)) { "this is not a transaction id" }
        return call("/v1/transactions/status", buildJsonObject { put("transaction_id", JsonPrimitive(txid)) }.toString(), withinMs).flag("known")
    }

    companion object {
        /** The most addresses the server takes in one request. */
        const val MAX_ADDRESSES = 100
        /** The longest text that can be an address. */
        const val MAX_ADDRESS = 100
        /** Lines of history asked for at a time; the server gives at most this many. */
        const val PAGE = 200
        /** The server refuses to list more coins than this for one request. */
        const val MAX_UTXOS = 10_000
        const val MAX_HEX = 800_000
        const val MAX_CURSOR = 1024
        const val MAX_HEIGHT = 500_000_000L
        const val MAX_RATE = 1_000_000L
        /** Seconds since 1970 up to the year 3000. */
        const val MAX_TIME = 32_503_680_000L
        const val MAX_RESTARTS = 2
        const val MAX_WAIT_MS = 20_000L
        /** The longest one question may take as a whole. An answer of the largest size still fits over a slow line. */
        const val DEADLINE_MS = 90_000L
        const val MAX_ANSWER_BYTES = 8 * 1024 * 1024
        /** [NodeError.code] when the parts of one answer were read at different blocks. Asking again helps. */
        const val MOVED = -2
        private val LOCKS = ConcurrentHashMap<String, ReentrantLock>()
        /** Closes connections whose time is over. One thread for all clients, and it does not keep a program alive. */
        private val WATCH = ScheduledThreadPoolExecutor(1) { work -> Thread(work, "server-deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
}
