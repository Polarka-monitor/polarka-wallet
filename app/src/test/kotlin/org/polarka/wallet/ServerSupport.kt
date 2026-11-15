package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/*
 * Helpers for the tests of the wallet over a Northbound wallet server. Nothing here is part of the app.
 */

/** A private TrueNorth Core on regtest for a wallet server to stand on. No account for a phone: only the server talks to it. */
internal class ServerNode private constructor(private val bin: File) : AutoCloseable {
    val port: Int = ServerSocket(0).use { it.localPort }
    private val dir: File = Files.createTempDirectory("polarka-server-node-").toFile()
    private val process: Process = ProcessBuilder(
        File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
        "-disablewallet=1", "-server=1", "-txindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1", "-rpcport=$port", "-printtoconsole=0",
    ).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()

    /** The node's own login for programs on the same machine; it is written anew at every start. */
    val cookie: File get() = File(dir, "regtest/.cookie")

    init {
        // The node answers a second or two after its start. If its process has ended, or two minutes are over,
        // it is not waited for any longer: the test then fails here and says what the node left behind, and not
        // at its first use of a node that is not there.
        val end = System.currentTimeMillis() + 120_000
        var failure: Throwable?
        while (true) {
            failure = runCatching { cli("getblockcount") }.exceptionOrNull()
            if (failure == null || !process.isAlive || System.currentTimeMillis() >= end) break
            Thread.sleep(300)
        }
        if (failure != null) {
            val alive = process.isAlive
            val log = runCatching { File(dir, "regtest/debug.log").readLines().takeLast(6).joinToString(" | ") }.getOrDefault("it left no log")
            process.destroyForcibly()
            dir.deleteRecursively()
            throw IllegalStateException("the test node on port $port did not come up (its process was ${if (alive) "still running" else "gone"}): ${failure.message}. Its log ends: $log")
        }
    }

    fun cli(vararg args: String): String {
        val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$dir", "-chain=regtest", "-rpcconnect=127.0.0.1",
            "-rpcport=$port") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString().trim()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${args.first()}: $out" }
        return out
    }

    fun mine(blocks: Int, address: String): String = cli("generatetoaddress", blocks.toString(), address)

    fun bestHash(): String = cli("getbestblockhash")

    fun mempool(): List<String> = Json.parseToJsonElement(cli("getrawmempool")).jsonArray.map { it.jsonPrimitive.content }

    override fun close() {
        runCatching { cli("stop") }
        if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
        dir.deleteRecursively()
    }

    companion object {
        /** A test that cannot run is reported as skipped, not as passed. */
        fun start(): ServerNode {
            val bin = System.getenv("TN_BIN")?.let { File(it) }
            if (bin == null || !File(bin, "truenorthd").canExecute()) {
                throw org.junit.AssumptionViolatedException("set TN_BIN to the folder with the official truenorthd and truenorth-cli")
            }
            // The port is chosen a moment before the node takes it, and something else on the machine can take
            // it in between. A node that did not come up is therefore started again, and that is said aloud.
            var last: IllegalStateException? = null
            repeat(3) { attempt ->
                try {
                    return ServerNode(bin)
                } catch (e: IllegalStateException) {
                    last = e
                    System.err.println("TEST NODE, attempt ${attempt + 1}: ${e.message}")
                }
            }
            throw last!!
        }
    }
}

/**
 * The stand-in for a Northbound wallet server (review/standin/northbound_standin.py) on top of a test node.
 * It is a program of its own, written in Python from the real server's sources; the same program serves the
 * emulators. The real server's code is not run anywhere in these tests.
 */
internal class StandIn private constructor(private val process: Process, val port: Int) : AutoCloseable {
    val url: String get() = "http://127.0.0.1:$port"

    /** A request as it is, for what the client under test does not let through. Returns the status and the body. */
    fun post(path: String, json: String): Pair<Int, String> {
        val c = URL(url + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.encodeToByteArray()) }
        val status = c.responseCode
        val text = ((if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).decodeToString()
        return status to text
    }

    /** The stand-in's switches: {"stale":true} (the index is behind), {"busy":2} (the next two requests get 429), {"hold":true} (nothing is passed on). */
    fun set(json: String) {
        check(post("/__standin/set", json).first == 200) { "the stand-in did not take $json" }
    }

    override fun close() {
        runCatching { process.outputStream.close() }            // it stops when its standard input closes
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        fun start(node: ServerNode): StandIn {
            val script = generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "review/standin/northbound_standin.py") }.firstOrNull { it.isFile }
                ?: throw IllegalStateException("review/standin/northbound_standin.py was not found above the working folder")
            val python = System.getenv("PYTHON3") ?: "python3"
            val process = try {
                ProcessBuilder(
                    python, "-B", script.path, "--rpc-url", "http://127.0.0.1:${node.port}", "--cookie", node.cookie.path,
                    "--port", "0", "--exit-with-stdin",
                ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            } catch (e: java.io.IOException) {
                throw org.junit.AssumptionViolatedException("python3 is needed for the stand-in server (PYTHON3 can name it)")
            }
            val line = process.inputStream.bufferedReader().readLine()          // "listening PORT", or nothing if it did not start
            val port = line?.removePrefix("listening ")?.trim()?.toIntOrNull()
            if (port == null) {
                process.destroyForcibly()
                throw IllegalStateException("the stand-in server did not start: $line")
            }
            return StandIn(process, port)
        }
    }
}

/**
 * Stands between the wallet and a wallet server. It keeps the paths that were asked for, can do something
 * right after the server has answered and before the wallet hears of it ([after]), and can change an answer
 * on its way back ([rewrite]): a server that is slow, and a server that lies. It can also answer by itself
 * without passing the question on ([instead]), and pass the question on and then lose the answer ([lost]).
 */
internal class ServerRelay(private val target: String) : AutoCloseable {
    val paths = ArrayList<String>()
    @Volatile var after: (path: String, count: Int) -> Unit = { _, _ -> }
    @Volatile var rewrite: (path: String, answer: String) -> String = { _, answer -> answer }
    /** A status and a body to answer with in place of the server, which is then not asked at all. */
    @Volatile var instead: (path: String, body: String) -> Pair<Int, String>? = { _, _ -> null }
    /** A status to answer with after the server was asked and has answered: the wallet does not get what the server said. */
    @Volatile var lost: (path: String, count: Int) -> Int? = { _, _ -> null }
    private val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    val url: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                kotlin.concurrent.thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    private fun handle(socket: java.net.Socket) = socket.use { s ->
        val input = java.io.BufferedInputStream(s.getInputStream())
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
        val path = first[1]
        val count = synchronized(paths) { paths.add(path); paths.count { it == path } }
        var status: Int
        var answer: String
        var retry: String? = null
        val made = instead(path, raw.decodeToString())
        if (made != null) {
            status = made.first
            answer = made.second
        } else {
            val c = URL(target + path).openConnection() as HttpURLConnection
            c.requestMethod = first[0]
            if (first[0] == "POST") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", headers["content-type"] ?: "application/json")
                c.outputStream.use { it.write(raw) }
            }
            status = c.responseCode
            answer = ((if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).decodeToString()
            retry = c.getHeaderField("Retry-After")
            val gone = lost(path, count)
            if (gone != null) {
                status = gone
                answer = "{\"message\":\"the answer was lost on the way\"}"
            }
        }
        after(path, count)
        val out = rewrite(path, answer).encodeToByteArray()
        s.getOutputStream().apply {
            write(("HTTP/1.1 $status relayed\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n" +
                (if (retry != null) "Retry-After: $retry\r\n" else "") + "\r\n").encodeToByteArray())
            write(out)
            flush()
        }
    }

    override fun close() = server.close()
}
