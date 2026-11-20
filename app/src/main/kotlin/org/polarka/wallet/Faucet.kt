package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Polarka's faucet: a first coin for a new wallet. The faucet hands out a challenge, the phone does a little work
 * for it (SHA-256 of "challenge:answer" must begin with so many zero bits; some seconds) and the faucet pays a small
 * amount to the address. Only in the personal edition of the app.
 */
object Faucet {
    const val URL_DEFAULT = "https://faucet.polarka.org"
    const val MOST_BITS = 24                        // more work than this is not done, whatever the faucet asks

    class Refused(val reason: String) : Exception(reason)

    /** Where the work stands, for the screen. */
    fun interface Progress { fun at(tried: Long) }

    fun leadingZeroBits(digest: ByteArray): Int {
        var bits = 0
        for (b in digest) {
            val v = b.toInt() and 0xff
            if (v == 0) { bits += 8; continue }
            return bits + Integer.numberOfLeadingZeros(v) - 24
        }
        return bits
    }

    /** The answer of the work, or null when it was not found before the challenge ran out. */
    fun solve(challenge: String, bits: Int, until: Long, now: () -> Long = System::currentTimeMillis, progress: Progress? = null): String? {
        val sha = MessageDigest.getInstance("SHA-256")
        val prefix = "$challenge:".toByteArray()
        val most = 1L shl (bits + 4)
        var i = 0L
        while (i < most) {
            if (i % 65536 == 0L) {
                if (now() / 1000 > until) return null
                progress?.at(i)
            }
            val answer = java.lang.Long.toHexString(i)
            sha.update(prefix)
            if (leadingZeroBits(sha.digest(answer.toByteArray())) >= bits) return answer
            i++
        }
        return null
    }

    private fun post(base: String, path: String, body: JsonObject): JsonObject {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream ?: throw Refused("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            stream.use { s ->
                val buf = ByteArray(4096)
                while (out.size() < 65536) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            val answer = Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())) as? JsonObject ?: throw Refused("OTHER")
            (answer["error"] as? JsonPrimitive)?.content?.let { e -> throw Refused(if (e in KNOWN) e else "OTHER") }
            return answer
        } catch (e: Refused) {
            throw e
        } catch (e: Exception) {
            throw Refused("UNREACHABLE")
        } finally {
            c.disconnect()
        }
    }

    private val KNOWN = setOf("ADDRESS", "ALREADY", "CHALLENGE", "WORK", "TOO_OFTEN", "EMPTY", "LATER", "NOT_NOW", "INPUT", "FAILED")

    /** Challenge, work, claim. Returns the id of the faucet's transaction. Runs for seconds: call it off the main thread. */
    fun ask(address: String, base: String = URL_DEFAULT, progress: Progress? = null): String {
        val c = post(base, "/v1/challenge", buildJsonObject { put("address", JsonPrimitive(address)) })
        val bits = (c["bits"] as? JsonPrimitive)?.intOrNull
        val challenge = (c["challenge"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val until = (c["expires"] as? JsonPrimitive)?.longOrNull
        if (bits == null || bits !in 0..MOST_BITS || challenge == null || challenge.length > 200 || until == null) throw Refused("WORK")
        val answer = solve(challenge, bits, until, progress = progress) ?: throw Refused("WORK")
        val got = post(base, "/v1/claim", buildJsonObject {
            put("address", JsonPrimitive(address)); put("challenge", JsonPrimitive(challenge)); put("answer", JsonPrimitive(answer))
        })
        val txid = (got["transaction_id"] as? JsonPrimitive)?.content
        if (txid == null || !Regex("[0-9a-f]{64}").matches(txid)) throw Refused("OTHER")
        return txid
    }
}
