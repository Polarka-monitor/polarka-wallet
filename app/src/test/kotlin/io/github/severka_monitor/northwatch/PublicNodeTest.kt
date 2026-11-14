package io.github.severka_monitor.northwatch

import north.core.Account
import north.core.Chain
import north.core.Words
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wallet with a public node: the real gateway (gateway.py of the polarka-node repository) in front of a
 * private TrueNorth Core on regtest. A recorder between the app and the gateway shows what the app sends.
 *
 * The point of the public mode is that the node never gets the key that shows the whole wallet. The test
 * fails if that key, or any descriptor made of it, appears in a request.
 *
 * It needs python3 and the gateway: POLARKA_GATEWAY names the file, or it lies next to this repository in
 * ../polarka-node/gateway.py. Without it the test reports that it was skipped.
 */
class PublicNodeTest {
    private class Recorder(private val target: Int) : AutoCloseable {
        val bodies = ArrayList<String>()
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
            val input = BufferedInputStream(s.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return@use
                head.append(b.toChar())
            }
            check("authorization:" !in head.toString().lowercase()) { "a login was sent to a public node" }
            val length = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val raw = ByteArray(length)
            var n = 0
            while (n < raw.size) {
                val got = input.read(raw, n, raw.size - n)
                if (got < 0) break
                n += got
            }
            synchronized(bodies) { bodies.add(raw.decodeToString()) }
            val c = URL("http://127.0.0.1:$target/").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(raw) }
            val status = c.responseCode
            val answer = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            s.getOutputStream().apply {
                write("HTTP/1.1 $status x\r\nContent-Type: application/json\r\nContent-Length: ${answer.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                write(answer)
                flush()
            }
        }

        override fun close() = server.close()
    }

    private fun gatewayFile(): File? {
        val named = System.getenv("POLARKA_GATEWAY")?.let { File(it) }
        val found = listOfNotNull(named, File("../../polarka-node/gateway.py"), File("../polarka-node/gateway.py")).firstOrNull { it.isFile }
        val python = runCatching { ProcessBuilder("python3", "--version").start().waitFor(20, TimeUnit.SECONDS) }.getOrDefault(false)
        if (found == null || !python) {
            println("SKIPPED: this test needs python3 and gateway.py (set POLARKA_GATEWAY to the file)")
            return null
        }
        return found
    }

    @Test
    fun aWalletWorksThroughThePublicGatewayAndItsAccountKeyStaysInThePhone() {
        val gatewayFile = gatewayFile() ?: return
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val work = Files.createTempDirectory("north-public-test-").toFile()
            val auth = File(work, "auth").apply { writeText("phone:${node.password}\n") }
            val gatewayPort = ServerSocket(0).use { it.localPort }
            val gateway = ProcessBuilder(
                "python3", gatewayFile.path, "--listen", "127.0.0.1:$gatewayPort", "--upstream", "127.0.0.1:${node.port}",
                "--auth-file", auth.path, "--hrp", "rnorth",
                // generous limits: this test is about what the app sends; the limits have their own test next to the gateway
                "--burst", "2000", "--per-second", "100", "--scan-burst", "200", "--scans-per-second", "20",
            ).redirectErrorStream(true).redirectOutput(File(work, "gateway.log")).start()
            try {
                val end = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < end && runCatching { Socket("127.0.0.1", gatewayPort).close() }.isFailure) Thread.sleep(100)
                Recorder(gatewayPort).use { recorder ->
                    val coin = 512_00000000L
                    val words = Words.generate()
                    val wallet = Account.fromWords(words, Chain.REGTEST)
                    val shop = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
                    val miner = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
                    node.mine(2, wallet.address(0, 0))
                    node.mine(100, miner)
                    val setup = Setup("http://127.0.0.1:${recorder.port}/", "", "", Chain.REGTEST, listOf(miner))
                    check(setup.isPublic)
                    val engine = Engine(setup)
                    var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub)

                    var seen = engine.fetch(info)
                    assertEquals(2 * coin, seen.spendableSat)
                    assertEquals(100 * coin, seen.watchedSat)
                    assertEquals(2, seen.moves.size)
                    val plan = engine.plan(info, shop, 100_00000000L, 2)
                    val txid = engine.send(info.xpub, plan, words)
                    assertEquals(listOf(txid), node.mempool())
                    info = info.copy(changeIndex = 1, pending = listOf(Pending(txid, plan.sentSat, plan.feeSat, plan.inputs.map { it.txid + ":" + it.vout }.toSet())))
                    seen = engine.fetch(info)
                    assertEquals(setOf(txid), seen.held)
                    assertEquals(coin, seen.spendableSat, "the coin of the waiting payment is tied up, the other one is free")
                    node.mine(1, miner)
                    seen = engine.fetch(info)
                    assertEquals(2 * coin - 100_00000000L - plan.feeSat, seen.walletSat)
                    assertEquals(100_00000000L, node.held(shop))
                    assertEquals(plan.feeSat, seen.moves.single { it.txid == txid }.feeSat)
                    assertEquals(1 to 1, seen.nextReceive to seen.nextChange)
                    val found = engine.paperLook(words)
                    assertEquals(seen.walletSat, found.coins.sumOf { it.amountSat })
                    assertEquals(0, engine.incomingSince(info, seen.tip).second.size)

                    val sent = synchronized(recorder.bodies) { recorder.bodies.joinToString("\n") }
                    assertTrue(wallet.watchKey().xpub !in sent, "the account key of the wallet went to a public node")
                    assertTrue("qrh(" !in sent && "/*" !in sent, "a descriptor with a key went to a public node")
                    assertTrue("addr(" + wallet.address(0, 0) + ")" in sent)
                    println("PUBLIC NODE OK: ${recorder.bodies.size} requests through the gateway; balance, history, payment and paper look work; the account key was not sent")
                }
            } finally {
                gateway.destroy()
                gateway.waitFor(10, TimeUnit.SECONDS)
                work.deleteRecursively()
            }
        }
    }
}
