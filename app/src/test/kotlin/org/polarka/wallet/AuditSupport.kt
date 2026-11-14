package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Chain
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/*
 * Helpers of the independent audit tests (Audit*Test.kt). Nothing here is part of the app.
 */

internal const val AUDIT_ALLOWED = "getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee," +
    "testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps"

/** A private TrueNorth Core on regtest, set up like the node for the phone: user "phone" with the same list of calls. */
internal class AuditNode private constructor(private val bin: File) : AutoCloseable {
    val password: String
    val port: Int
    private val dir: File = Files.createTempDirectory("north-audit-node-").toFile()
    private val process: Process

    init {
        val random = SecureRandom()
        password = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hash = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt.encodeToByteArray(), "HmacSHA256")) }
            .doFinal(password.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        port = ServerSocket(0).use { it.localPort }
        process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
            "-disablewallet=1", "-server=1", "-txindex=1", "-blockfilterindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
            "-rpcport=$port", "-rpcauth=phone:$salt\$$hash", "-rpcwhitelist=phone:$AUDIT_ALLOWED", "-rpcwhitelistdefault=0", "-printtoconsole=0",
        ).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        val end = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < end && runCatching { cli("getblockcount") }.isFailure) Thread.sleep(300)
    }

    fun cli(vararg args: String): String {
        val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$dir", "-chain=regtest", "-rpcconnect=127.0.0.1",
            "-rpcport=$port") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString().trim()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${args.first()}: $out" }
        return out
    }

    fun mine(blocks: Int, address: String): String = cli("generatetoaddress", blocks.toString(), address)

    fun mempool(): List<String> = Json.parseToJsonElement(cli("getrawmempool")).jsonArray.map { it.jsonPrimitive.content }

    /**
     * What an address holds in confirmed coins, in base units.
     *
     * Adapted after the third look: the node scans for one caller at a time, and the app under test may be
     * scanning at this very moment (it looks at the node by itself after a payment). The node then answers
     * "Scan already in progress"; the question is asked again instead of failing the test.
     */
    fun held(address: String): Long {
        var answer: Result<String> = runCatching { cli("scantxoutset", "start", "[\"addr($address)\"]") }
        var tries = 0
        while (answer.isFailure && "Scan already in progress" in answer.exceptionOrNull()?.message.orEmpty() && tries++ < 40) {
            Thread.sleep(150)
            answer = runCatching { cli("scantxoutset", "start", "[\"addr($address)\"]") }
        }
        val r = Json.parseToJsonElement(answer.getOrThrow()).jsonObject
        return BigDecimal(r["total_amount"]!!.jsonPrimitive.content).movePointRight(8).longValueExact()
    }

    fun setup(port: Int = this.port, addresses: List<String> = emptyList()): Setup =
        Setup("http://127.0.0.1:$port/", "phone", password, Chain.REGTEST, addresses)

    override fun close() {
        runCatching { cli("stop") }
        if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
        dir.deleteRecursively()
    }

    companion object {
        /**
         * Adapted after the retest: when TN_BIN does not name the folder with the official programs, the test
         * is reported as skipped. Before, it printed a line and was counted as passed.
         */
        fun startOrNull(): AuditNode? {
            val bin = System.getenv("TN_BIN")?.let { File(it) }
            if (bin == null || !File(bin, "truenorthd").canExecute()) {
                // a test that cannot run is reported as skipped, not as passed
                throw org.junit.AssumptionViolatedException("set TN_BIN to the folder with the official truenorthd and truenorth-cli")
            }
            return AuditNode(bin)
        }
    }
}

private class HttpRequest(val headers: Map<String, String>, val raw: ByteArray)

private fun readRequest(stream: InputStream): HttpRequest? {
    val input = BufferedInputStream(stream)
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val b = input.read()
        if (b < 0) return null
        head.append(b.toChar())
    }
    val headers = head.lines().drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    val raw = ByteArray(headers["content-length"]?.toInt() ?: 0)
    var n = 0
    while (n < raw.size) {
        val got = input.read(raw, n, raw.size - n)
        if (got < 0) break
        n += got
    }
    return HttpRequest(headers, raw)
}

private fun respond(socket: Socket, status: Int, body: String) {
    val out = body.encodeToByteArray()
    socket.getOutputStream().apply {
        write("HTTP/1.1 $status audit\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
        write(out)
        flush()
    }
}

/**
 * Stands between the app and the real node. It can change an answer ([rewrite]) or let the node do the work
 * and then lose the answer on the way back ([loseAnswerOf]), as a broken connection does.
 */
internal class AuditRelay(private val target: Int) : AutoCloseable {
    val methods = ArrayList<String>()
    @Volatile var rewrite: (method: String, answer: String) -> String = { _, answer -> answer }
    @Volatile var loseAnswerOf: (method: String) -> Boolean = { false }
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    private fun handle(socket: Socket) = socket.use { s ->
        val request = readRequest(s.getInputStream()) ?: return@use
        val method = Json.parseToJsonElement(request.raw.decodeToString()).jsonObject["method"]!!.jsonPrimitive.content
        synchronized(methods) { methods.add(method) }
        val c = URL("http://127.0.0.1:$target/").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        request.headers["authorization"]?.let { c.setRequestProperty("Authorization", it) }
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(request.raw) }
        val status = c.responseCode
        val answer = ((if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).decodeToString()
        if (loseAnswerOf(method)) return@use                // the node has done it; the phone never hears
        respond(s, status, rewrite(method, answer))
    }

    fun count(method: String): Int = synchronized(methods) { methods.count { it == method } }

    override fun close() = server.close()
}

/** A node that is not a node: every call is answered by [answer] with the text of the JSON "result". */
internal class AuditFakeNode(private val answer: (method: String, params: JsonArray) -> String) : AutoCloseable {
    val methods = ArrayList<String>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    private fun handle(socket: Socket) = socket.use { s ->
        val request = readRequest(s.getInputStream()) ?: return@use
        val call = Json.parseToJsonElement(request.raw.decodeToString()).jsonObject
        val method = call["method"]!!.jsonPrimitive.content
        synchronized(methods) { methods.add(method) }
        respond(s, 200, "{\"result\":" + answer(method, call["params"]!!.jsonArray) + ",\"error\":null,\"id\":\"north\"}")
    }

    fun setup(): Setup = Setup("http://127.0.0.1:$port/", "phone", "pw", Chain.REGTEST, emptyList())

    override fun close() = server.close()
}

internal class AuditSetups(var value: Setup?) : SetupStore {
    override fun load(): Setup? = value
    override fun save(setup: Setup) { value = setup }
    override fun clear() { value = null }
}

/** [failLoads] makes the next reads fail, as a damaged wallet.bin or a Keystore that does not answer would. */
internal class AuditWallets(@Volatile var value: WalletInfo? = null) : WalletStore {
    @Volatile var failLoads = 0
    override fun load(): WalletInfo? {
        if (failLoads > 0) {
            failLoads--
            throw IllegalArgumentException("the stored file has an unknown form")
        }
        return value
    }
    override fun save(info: WalletInfo) { value = info }
    override fun clear() { value = null }
}

internal class AuditKey : KeyHolder {
    private val fixed = SecretKeySpec(ByteArray(32) { (it * 3 + 1).toByte() }, "AES")
    private var present = false
    override fun key(create: Boolean): SecretKey? = if (present || create) fixed.also { present = true } else null
    override fun delete() { present = false }
}

/** The owner of the phone in a test: there or not there, and counted. */
internal class AuditOwner : Owner {
    @Volatile var unlocks = 0
    @Volatile var shows = 0
    @Volatile var there = true
    override suspend fun unlock(cipher: Cipher): Cipher {
        unlocks++
        if (!there) throw OwnerRefused("nobody there")
        return cipher
    }
    override suspend fun present(): Boolean {
        shows++
        return there
    }
}
