package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Polarka's faucet: a first coin for a new wallet. Only in the personal edition of the app: this file is in the
 * source set of that edition alone, so the public package holds none of it.
 *
 * The faucet hands out a challenge, the phone does some work for it (SHA-256 of "challenge:answer" must begin with
 * so many zero bits) and the faucet pays a small amount to the address. The phone keeps the rules of faucet.py's
 * own asker in polarka-agent: no more work than [MOST_BITS] asks, no redirect followed, no answer waited for
 * without end, and of a refusal only the faucet's word from a fixed list is shown, never a text the faucet sends.
 */
object Faucet {
    const val URL_DEFAULT = "https://faucet.polarka.org"

    /** More work than this is not done, whatever the faucet asks: 2^24 tries expected, sixteen times that at most. */
    const val MOST_BITS = 24

    /** The longest one question to the faucet may take as a whole, however slowly its bytes come (as faucet.py's asker). */
    const val QUESTION_MS = 60_000L

    /** A challenge of faucet.py can be answered for this long. One that says it lives longer is worked on no longer than this. */
    const val CHALLENGE_LIFE_S = 600L

    /** The longest answer read. */
    const val MOST_ANSWER = 65_536

    /** The faucet's words for a refusal, as faucet.py names them. Anything else that it says is shown as OTHER. */
    val WORDS = setOf("ADDRESS", "ALREADY", "CHALLENGE", "WORK", "TOO_OFTEN", "EMPTY", "LATER", "NOT_NOW", "INPUT", "FAILED")

    /**
     * Why there is no coin. With [byFaucet] the faucet said so, in one of its [WORDS]. Otherwise it is what the phone
     * found by itself: SLOW (no whole answer in time), UNREACHABLE, OTHER (an answer that cannot be read), TOO_MUCH
     * (more work asked than the phone does), RAN_OUT (the challenge's time was over by this phone's clock),
     * NO_ANSWER (none found within sixteen times the work expected), STOPPED (by the owner).
     */
    class Refused(val reason: String, val byFaucet: Boolean = false) : Exception(reason)

    /** One asking of the faucet, which its owner can stop: the work ends at its next look, and an open connection is closed. */
    class Asking {
        @Volatile var stopped = false
            private set
        private val open = AtomicReference<HttpURLConnection?>()

        fun stop() {
            stopped = true
            runCatching { open.get()?.disconnect() }
        }

        internal fun opened(c: HttpURLConnection?) {
            open.set(c)
            if (stopped) runCatching { c?.disconnect() }
        }
    }

    /** Closes connections whose time is over. One thread for every asking, and it does not keep a program alive. */
    private val WATCH = ScheduledThreadPoolExecutor(1) { work -> Thread(work, "faucet-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    fun leadingZeroBits(digest: ByteArray): Int {
        var bits = 0
        for (b in digest) {
            val v = b.toInt() and 0xff
            if (v == 0) { bits += 8; continue }
            return bits + Integer.numberOfLeadingZeros(v) - 24
        }
        return bits
    }

    /**
     * The answer of the work: the first number, in lowercase hex without leading zeros, for which SHA-256 of
     * "challenge:answer" begins with [bits] zero bits. The same answer as faucet.py's own `solve`, which tries the
     * numbers in the same order. Null when none was found within sixteen times the work expected, when the time ran
     * out ([until], in seconds since 1970), or when [stop] says so. Time and [stop] are looked at every 65,536 tries.
     */
    fun solve(challenge: String, bits: Int, until: Long, now: () -> Long = System::currentTimeMillis, stop: () -> Boolean = { false }): String? {
        val sha = MessageDigest.getInstance("SHA-256")
        val prefix = "$challenge:".toByteArray()
        val most = 1L shl (bits + 4)
        var i = 0L
        while (i < most) {
            if (i % 65536 == 0L && (now() / 1000 > until || stop())) return null
            val answer = java.lang.Long.toHexString(i)
            sha.update(prefix)
            if (leadingZeroBits(sha.digest(answer.toByteArray())) >= bits) return answer
            i++
        }
        return null
    }

    /** One question: the faucet's answer as a JSON object. The whole of it, connecting included, takes [withinMs] at most. */
    private fun post(base: String, path: String, body: JsonObject, asking: Asking, withinMs: Long): JsonObject {
        if (asking.stopped) throw Refused("STOPPED")
        val start = System.nanoTime()
        fun late() = (System.nanoTime() - start) / 1_000_000 >= withinMs
        val c = try {
            URI(base.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw Refused("UNREACHABLE")
        }
        asking.opened(c)
        // When the time for the whole question is over, the connection is closed under whatever waits on it, and the
        // reader gives the answer up between two reads: a byte every few seconds does not keep the button busy.
        val watch = WATCH.schedule({ runCatching { c.disconnect() } }, withinMs, TimeUnit.MILLISECONDS)
        try {
            c.instanceFollowRedirects = false           // the faucet asked is the one that answers
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            val stream = (if (status in 200..299) c.inputStream else c.errorStream) ?: throw Refused("OTHER")
            val out = ByteArrayOutputStream()
            stream.use { s ->
                val buf = ByteArray(4096)
                while (true) {
                    if (late()) throw Refused("SLOW")
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MOST_ANSWER) throw Refused("OTHER")
                }
            }
            if (late()) throw Refused("SLOW")
            val answer = runCatching { Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())) }.getOrNull() as? JsonObject
                ?: throw Refused("OTHER")
            if ("error" in answer) {
                val word = (answer["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                throw if (word != null && word in WORDS) Refused(word, byFaucet = true) else Refused("OTHER")
            }
            return answer
        } catch (e: Refused) {
            throw if (asking.stopped) Refused("STOPPED") else e
        } catch (e: Exception) {
            throw Refused(if (asking.stopped) "STOPPED" else if (late()) "SLOW" else "UNREACHABLE")
        } finally {
            watch.cancel(false)
            asking.opened(null)
            c.disconnect()
        }
    }

    /**
     * Challenge, work, claim. Returns the id of the faucet's transaction: the faucet's word that it paid, which the
     * balance does not take from it; the coin shows there once it is in a block. Takes seconds, at most minutes:
     * call it off the main thread. [now] and [withinMs] are for the tests.
     */
    fun ask(
        address: String, base: String = URL_DEFAULT, asking: Asking = Asking(),
        now: () -> Long = System::currentTimeMillis, withinMs: Long = QUESTION_MS,
    ): String {
        val c = post(base, "/v1/challenge", buildJsonObject { put("address", JsonPrimitive(address)) }, asking, withinMs)
        val bits = (c["bits"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val challenge = (c["challenge"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val expires = (c["expires"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (bits == null || challenge.isNullOrEmpty() || challenge.length > 200 || expires == null) throw Refused("OTHER")
        if (bits !in 0..MOST_BITS) throw Refused("TOO_MUCH")
        // the work ends when the challenge does, and never later than a challenge of faucet.py can be answered
        val until = minOf(expires, now() / 1000 + CHALLENGE_LIFE_S)
        if (now() / 1000 >= until) throw Refused("RAN_OUT")
        val answer = solve(challenge, bits, until, now) { asking.stopped }
            ?: throw Refused(if (asking.stopped) "STOPPED" else if (now() / 1000 > until) "RAN_OUT" else "NO_ANSWER")
        val got = post(base, "/v1/claim", buildJsonObject {
            put("address", JsonPrimitive(address)); put("challenge", JsonPrimitive(challenge)); put("answer", JsonPrimitive(answer))
        }, asking, withinMs)
        val txid = (got["transaction_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (txid == null || !Regex("[0-9a-f]{64}").matches(txid)) throw Refused("OTHER")
        return txid
    }
}
