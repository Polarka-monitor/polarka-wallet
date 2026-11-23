package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A stand-in for faucet.py (polarka-agent) on 127.0.0.1, written for the tests of the personal edition after the
 * review of 0.7.0 (L5). Its challenges have faucet.py's form ("bits.expires.nonce.mark"), it checks the work with a
 * counter of its own (the digest read as a number, not byte by byte as the app counts), it pays an address once and
 * answers ALREADY after that, as faucet.py does within a day. [misbehave] makes it a faucet that does not keep to
 * the rules: what it then sends is the test's to choose, byte by byte if need be.
 */
internal class FaucetStandIn(@Volatile var bits: Int = 8) : AutoCloseable {
    /** Raw bytes for one connection: sent after [holdMs], the first [fastBytes] at once and each one after them [pauseMs] apart. */
    class Raw(val bytes: ByteArray, val pauseMs: Long = 0, val fastBytes: Int = 0, val holdMs: Long = 0)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool { work -> Thread(work, "faucet-standin").apply { isDaemon = true } }
    private val random = SecureRandom()
    private val given = ConcurrentHashMap<String, String>()          // challenge -> the address it was given for
    val url: String get() = "http://127.0.0.1:${server.localPort}"
    val txid = "5a".repeat(32)
    val paid = CopyOnWriteArrayList<String>()
    @Volatile var claims = 0
    @Volatile var challenges = 0
    @Volatile var expiresIn = 600L

    /** Given the path asked, what to send instead of the faucet's answer; null keeps to the rules. */
    @Volatile var misbehave: (path: String) -> Raw? = { null }

    init {
        pool.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { socket.use { runCatching { handle(it) } } }      // a test that ends cuts its connections off
            }
        }
    }

    /** SHA-256 of "challenge:answer" begins with [bits] zero bits: the digest as a number has no more than 256 - bits bits. */
    fun workDone(challenge: String, answer: String, bits: Int): Boolean =
        BigInteger(1, MessageDigest.getInstance("SHA-256").digest("$challenge:$answer".toByteArray())).bitLength() <= 256 - bits

    private fun handle(socket: Socket) {
        val (path, body) = read(socket.getInputStream()) ?: return
        val instead = misbehave(path)
        if (instead != null) {
            val out = socket.getOutputStream()
            if (instead.holdMs > 0) Thread.sleep(instead.holdMs)
            for ((i, b) in instead.bytes.withIndex()) {
                if (i >= instead.fastBytes && instead.pauseMs > 0) Thread.sleep(instead.pauseMs)
                runCatching { out.write(b.toInt()); out.flush() }.onFailure { return }
            }
            return
        }
        val asked = runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrNull()
        val address = asked?.get("address")?.jsonPrimitive?.content.orEmpty()
        when (path) {
            "/v1/challenge" -> {
                challenges++
                if (address in paid) return answer(socket, 400, """{"error":"ALREADY","message":"this address was paid within the last day"}""")
                val expires = System.currentTimeMillis() / 1000 + expiresIn
                val nonce = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val mark = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val challenge = "$bits.$expires.$nonce.$mark"
                given[challenge] = address
                answer(socket, 200, """{"challenge":"$challenge","bits":$bits,"expires":$expires,"work":"find an answer"}""")
            }
            "/v1/claim" -> {
                claims++
                val challenge = asked?.get("challenge")?.jsonPrimitive?.content.orEmpty()
                val reply = asked?.get("answer")?.jsonPrimitive?.content.orEmpty()
                val bitsOf = challenge.substringBefore('.').toIntOrNull() ?: -1
                when {
                    given.remove(challenge) != address -> answer(socket, 400, """{"error":"CHALLENGE","message":"not a challenge of this faucet"}""")
                    !Regex("[0-9a-f]{1,32}").matches(reply) || !workDone(challenge, reply, bitsOf) ->
                        answer(socket, 400, """{"error":"WORK","message":"the answer does not do the work asked for"}""")
                    address in paid -> answer(socket, 400, """{"error":"ALREADY","message":"this address was paid within the last day"}""")
                    else -> {
                        paid.add(address)
                        answer(socket, 200, """{"asked":"$address","amount":1,"transaction_id":"$txid"}""")
                    }
                }
            }
            else -> answer(socket, 404, """{"error":"INPUT","message":"there is /v1/challenge and /v1/claim"}""")
        }
    }

    private fun answer(socket: Socket, status: Int, json: String) {
        val body = json.toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching { socket.getOutputStream().apply { write(head.toByteArray() + body); flush() } }
    }

    /** The path and the body of one request, or null when it did not come whole. */
    private fun read(stream: InputStream): Pair<String, String>? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = stream.read()
            if (b < 0 || head.length > 16_384) return null
            head.append(b.toChar())
        }
        val lines = head.split("\r\n")
        val path = lines.first().split(" ").getOrNull(1) ?: return null
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = stream.read(body, at, length - at)
            if (n < 0) return null
            at += n
        }
        return path to body.decodeToString()
    }

    override fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    companion object {
        /** An answer of the faucet's in raw bytes, for [misbehave]. */
        fun http(status: Int, body: String, extra: String = ""): ByteArray =
            ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n$extra\r\n" + body).toByteArray()
    }
}
