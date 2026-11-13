package io.github.severka_monitor.northwatch

import fr.acinq.bitcoin.MnemonicCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Words
import north.node.NodeError
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Measurements for the security review. The engine talks to a real TrueNorth Core on regtest through a small
 * relay written here. The relay records every request and can change the node's answers, so the tests can see
 * what leaves the phone and what a lying node achieves. Runs when TN_BIN names the folder with the programs.
 */
class ReviewTest {
    /** Passes requests on to the node, keeps their bodies, and lets a test rewrite the answers. */
    private class Relay(private val target: Int) : AutoCloseable {
        val bodies = ArrayList<String>()
        @Volatile var rewrite: (method: String, answer: String) -> String = { _, answer -> answer }
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    runCatching { handle(socket) }
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
            val headers = head.lines().drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val raw = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var n = 0
            while (n < raw.size) {
                val got = input.read(raw, n, raw.size - n)
                if (got < 0) break
                n += got
            }
            val body = raw.decodeToString()
            synchronized(bodies) { bodies.add(body) }
            val method = Json.parseToJsonElement(body).jsonObject["method"]!!.jsonPrimitive.content
            val c = URL("http://127.0.0.1:$target/").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            headers["authorization"]?.let { c.setRequestProperty("Authorization", it) }
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(raw) }
            val status = c.responseCode
            val answer = ((if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).decodeToString()
            val out = rewrite(method, answer).encodeToByteArray()
            s.getOutputStream().apply {
                write("HTTP/1.1 $status relayed\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                write(out)
                flush()
            }
        }

        fun methods(): List<String> = synchronized(bodies) { bodies.map { Json.parseToJsonElement(it).jsonObject["method"]!!.jsonPrimitive.content } }

        override fun close() = server.close()
    }

    private class Chainside(val relay: Relay, val setup: Setup, val cli: (Array<out String>) -> String, val stop: () -> Unit)

    private fun withNode(block: (Chainside) -> Unit) {
        val bin = System.getenv("TN_BIN")?.let { File(it) }
        if (bin == null || !File(bin, "truenorthd").canExecute()) {
            println("SKIPPED: set TN_BIN to the folder with the official truenorthd and truenorth-cli")
            return
        }
        val random = SecureRandom()
        val password = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hash = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt.encodeToByteArray(), "HmacSHA256")) }
            .doFinal(password.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val dir = Files.createTempDirectory("north-review-test-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
            "-disablewallet=1", "-server=1", "-txindex=1", "-blockfilterindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
            "-rpcport=$port", "-rpcauth=phone:$salt\$$hash", "-rpcwhitelist=phone:$ALLOWED", "-rpcwhitelistdefault=0", "-printtoconsole=0",
        ).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        val cli = { args: Array<out String> ->
            val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$dir", "-chain=regtest", "-rpcconnect=127.0.0.1",
                "-rpcport=$port") + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${args.first()}: $out" }
            out
        }
        val relay = Relay(port)
        try {
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end && runCatching { cli(arrayOf("getblockcount")) }.isFailure) Thread.sleep(300)
            block(Chainside(relay, Setup("http://127.0.0.1:${relay.port}/", "phone", password, Chain.REGTEST, emptyList()), cli) {})
        } finally {
            relay.close()
            runCatching { cli(arrayOf("stop")) }
            if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
            dir.deleteRecursively()
        }
    }

    private fun secretsOf(words: List<String>): List<String> {
        val account = Account.fromWords(words, Chain.REGTEST)
        val keys = (0..1).flatMap { branch -> (0..9).map { account.privateKey(branch, it).value.toHex() } }
        // pairs of neighbouring words rather than single words: "north", "start", "range" and "method" are words of the
        // list and also appear in ordinary requests
        return words.windowed(2).map { it.joinToString(" ") } + words.joinToString(" ") + Format.hex(MnemonicCode.toSeed(words, "")) + account.masterXprv() + keys
    }

    @Test
    fun nothingSecretLeavesThePhone() = withNode { side ->
        val coin = 512_00000000L
        val words = Words.generate()
        val wallet = Account.fromWords(words, Chain.REGTEST)
        val paperWords = Words.generate()
        val paper = Account.fromWords(paperWords, Chain.REGTEST)
        val other = Account.fromWords(Words.generate(), Chain.REGTEST)
        side.cli(arrayOf("generatetoaddress", "2", wallet.address(0, 0)))
        side.cli(arrayOf("generatetoaddress", "2", paper.address(0, 0)))
        side.cli(arrayOf("generatetoaddress", "100", other.address(0, 9)))
        val setup = side.setup.copy(addresses = listOf(paper.address(0, 0)))
        var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub)
        val engine = Engine(setup)

        // everything the wallet does with the node, once
        engine.fetch(null)
        engine.fetch(info)
        val plan = engine.plan(info, other.address(0, 0), 100_00000000L, 2)
        engine.send(info.xpub, plan, words)
        info = info.copy(changeIndex = 1)
        side.cli(arrayOf("generatetoaddress", "1", other.address(0, 9)))
        engine.incomingSince(info, 100)
        val found = engine.paperLook(paperWords)
        val newPaper = Account.fromWords(Words.generate(), Chain.REGTEST)
        engine.paperSend(found, engine.paperPlan(found, wallet.address(0, 1), 1, 10_00000000L, newPaper.address(0, 0)), paperWords)
        side.cli(arrayOf("generatetoaddress", "1", other.address(0, 9)))
        assertEquals(2 * coin - 100_00000000L - plan.feeSat + 10_00000000L, engine.fetch(info.copy(receiveIndex = 2)).walletSat)

        val sent = synchronized(side.relay.bodies) { side.relay.bodies.joinToString("\n") }.lowercase()
        for (secret in secretsOf(words) + secretsOf(paperWords)) {
            assertTrue(secret.lowercase() !in sent, "a secret went to the node: ${secret.take(6)}...")
        }
        val methods = side.relay.methods()
        assertTrue(methods.toSet().all { it in ALLOWED.split(",") }, "a call outside the node's list: ${methods.toSet()}")
        // what does identify the wallet to its node: the public account key inside the descriptors, and addresses
        assertTrue(wallet.watchKey().xpub.lowercase() in sent)
        println("REVIEW OK: ${methods.size} requests, calls used: ${methods.toSortedSet()}; no word, seed, extended private key or private key in any of them")
    }

    private fun edit(answer: String, change: (JsonObject) -> JsonElement): String {
        val whole = Json.parseToJsonElement(answer).jsonObject
        val result = whole["result"] as? JsonObject ?: return answer
        return JsonObject(whole + ("result" to change(result))).toString()
    }

    private fun firstCoin(result: JsonObject, change: (JsonObject) -> JsonObject): JsonObject {
        val coins = result["unspents"]!!.jsonArray
        if (coins.isEmpty()) return result
        return JsonObject(result + ("unspents" to JsonArray(listOf<JsonElement>(change(coins[0].jsonObject)) + coins.drop(1))))
    }

    @Test
    fun aLyingNodeCannotMakeTheWalletLoseCoins() = withNode { side ->
        val coin = 512_00000000L
        val words = Words.generate()
        val wallet = Account.fromWords(words, Chain.REGTEST)
        val other = Account.fromWords(Words.generate(), Chain.REGTEST)
        side.cli(arrayOf("generatetoaddress", "1", wallet.address(0, 0)))
        side.cli(arrayOf("generatetoaddress", "100", other.address(0, 9)))
        val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub)
        val engine = Engine(side.setup)
        assertEquals(coin, engine.fetch(info).spendableSat)

        // 1. the node adds a coin that does not belong to the wallet: the whole answer is refused
        side.relay.rewrite = { method, answer ->
            if (method != "scantxoutset") answer
            else edit(answer) { r -> firstCoin(r) { c -> JsonObject(c + ("scriptPubKey" to JsonPrimitive(Format.hex(other.script(0, 0))))) } }
        }
        assertEquals(Problem.Kind.FOREIGN_OUTPUTS, assertFailsWith<EngineError> { engine.fetch(info) }.kind)

        // 2. the node says the coin is smaller than it is. A transaction built on that would give the difference
        //    away as a fee; but the signature covers the amounts, so an honest check refuses it and nothing is sent
        side.relay.rewrite = { method, answer ->
            if (method != "scantxoutset") answer
            else edit(answer) { r -> firstCoin(r) { c -> JsonObject(c + ("amount" to JsonPrimitive(12.0))) } }
        }
        val low = engine.plan(info, other.address(0, 0), 5_00000000L, 2)
        assertEquals(Problem.Kind.REJECTED, assertFailsWith<EngineError> { engine.send(info.xpub, low, words) }.kind)
        assertTrue("sendrawtransaction" !in side.relay.methods())

        // 3. the node also lies that the transaction is fine: it goes out, and every honest node refuses it
        side.relay.rewrite = { method, answer ->
            when (method) {
                "scantxoutset" -> edit(answer) { r -> firstCoin(r) { c -> JsonObject(c + ("amount" to JsonPrimitive(12.0))) } }
                "testmempoolaccept" -> """{"result":[{"allowed":true,"vsize":${low.vsize},"fees":{"base":${"%.8f".format(java.util.Locale.ROOT, low.feeSat / 1e8)}}}],"error":null,"id":"north"}"""
                else -> answer
            }
        }
        assertFailsWith<NodeError> { engine.send(info.xpub, engine.plan(info, other.address(0, 0), 5_00000000L, 2), words) }
        assertTrue("sendrawtransaction" in side.relay.methods())

        // 4. the node claims to be on another network: nothing is shown
        side.relay.rewrite = { method, answer -> if (method == "getblockchaininfo") answer.replace("\"chain\":\"regtest\"", "\"chain\":\"main\"") else answer }
        assertEquals(Problem.Kind.OTHER_CHAIN, assertFailsWith<EngineError> { engine.fetch(info) }.kind)

        // after all of that, with an honest node again: the coin is still there, untouched
        side.relay.rewrite = { _, answer -> answer }
        side.cli(arrayOf("generatetoaddress", "1", other.address(0, 9)))
        assertEquals(coin, engine.fetch(info).walletSat)
        println("REVIEW OK: foreign coin refused, understated amount cannot be spent, lying verdict changes nothing, wrong network refused; the coin is intact")
    }

    private companion object {
        const val ALLOWED = "getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee," +
            "testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps"
    }
}
