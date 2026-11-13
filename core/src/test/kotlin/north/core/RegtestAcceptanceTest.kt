package north.core

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real judge: TrueNorth Core itself. A wallet with fresh random words receives block rewards on a regtest
 * node, and a transaction built and signed by this code must be accepted, mined and found on the chain.
 *
 * Runs when the environment variable TN_BIN names the folder with the official truenorthd and truenorth-cli
 * (v30.5.4); without it the test reports that it was skipped.
 */
class RegtestAcceptanceTest {
    private class Node(private val bin: File) : AutoCloseable {
        val dir: File = Files.createTempDirectory("north-wallet-test-").toFile()
        private val port = ServerSocket(0).use { it.localPort }
        private val process: Process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0",
            "-fixedseeds=0", "-disablewallet=1", "-server=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
            "-rpcport=$port", "-printtoconsole=0", "-txindex=1",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

        init {
            val cookie = File(dir, "regtest/.cookie")
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end) {
                if (cookie.exists() && runCatching { cli("getblockcount") }.isSuccess) break
                Thread.sleep(300)
            }
        }

        fun cli(vararg args: String): String {
            val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$dir", "-chain=regtest",
                "-rpcconnect=127.0.0.1", "-rpcport=$port") + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${args.first()}: $out" }
            return out
        }

        fun json(vararg args: String): JsonElement = Json.parseToJsonElement(cli(*args))

        override fun close() {
            runCatching { cli("stop") }
            if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
            dir.deleteRecursively()
        }
    }

    private fun sats(e: JsonElement): Long = BigDecimal(e.jsonPrimitive.content).movePointRight(8).longValueExact()

    @Test
    fun coreAcceptsAndMinesATransactionBuiltHere() {
        val bin = System.getenv("TN_BIN")?.let { File(it) }
        if (bin == null || !File(bin, "truenorthd").canExecute()) {
            println("SKIPPED: set TN_BIN to the folder with the official truenorthd and truenorth-cli")
            return
        }
        val random = SecureRandom()
        fun freshWords() = MnemonicCode.toMnemonics(ByteArray(32).also { random.nextBytes(it) })
        val wallet = Account.fromWords(freshWords(), Chain.REGTEST)
        val other = Account.fromWords(freshWords(), Chain.REGTEST)

        Node(bin).use { node ->
            // rewards: two blocks to receive address 0, one to receive address 1, then 100 blocks elsewhere
            node.cli("generatetoaddress", "2", wallet.address(0, 0))
            node.cli("generatetoaddress", "1", wallet.address(0, 1))
            node.cli("generatetoaddress", "100", other.address(0, 7))
            val scan = node.json("scantxoutset", "start",
                """["addr(${wallet.address(0, 0)})","addr(${wallet.address(0, 1)})"]""").jsonObject
            val coins = scan["unspents"]!!.jsonArray.map { it.jsonObject }.map { u ->
                val script = u["scriptPubKey"]!!.jsonPrimitive.content
                val index = if (script == wallet.script(0, 0).joinToString("") { "%02x".format(it) }) 0 else 1
                Coin(u["txid"]!!.jsonPrimitive.content, u["vout"]!!.jsonPrimitive.content.toLong(), sats(u["amount"]!!), 0, index)
            }
            assertEquals(3, coins.size, "three rewards are expected on the two addresses")
            val total = coins.sumOf { it.amountSat }

            // pay 700 NORTH to the other wallet, the rest back to our change address 0, at 2 sat per vbyte
            val payTo = Qrh.scriptForAddress(Chain.REGTEST, other.address(0, 0))
            val change = wallet.script(1, 0)
            val vsize = Spend.vsize(coins.size, listOf(payTo, change))
            val fee = 2L * vsize
            val pay = 700_00000000L
            val aux = ByteVector32(ByteArray(32).also { random.nextBytes(it) })
            val spend = Spend.sign(wallet, coins, listOf(Payment(payTo, pay), Payment(change, total - pay - fee)), aux)
            assertEquals(vsize, spend.vsize, "the size known before signing is the size after signing")
            assertEquals(fee, spend.feeSat)

            val verdict = node.json("testmempoolaccept", """["${spend.hex}"]""").jsonArray[0].jsonObject
            assertTrue(verdict["allowed"]!!.jsonPrimitive.boolean, "TrueNorth Core refuses the transaction: $verdict")
            assertEquals(vsize, verdict["vsize"]!!.jsonPrimitive.int)
            assertEquals(fee, sats(verdict["fees"]!!.jsonObject["base"]!!))
            assertEquals(spend.txid, node.cli("sendrawtransaction", spend.hex))
            val block = Json.parseToJsonElement(node.cli("generatetoaddress", "1", other.address(0, 8))).jsonArray[0].jsonPrimitive.content
            val mined = node.json("getrawtransaction", spend.txid, "1", block).jsonObject
            assertEquals(1, mined["confirmations"]!!.jsonPrimitive.int)

            // the chain now shows the payment and the change where this code said they would be
            val after = node.json("scantxoutset", "start",
                """["addr(${other.address(0, 0)})","addr(${wallet.address(1, 0)})","addr(${wallet.address(0, 0)})","addr(${wallet.address(0, 1)})"]""").jsonObject
            val byScript = after["unspents"]!!.jsonArray.map { it.jsonObject }.associate {
                it["scriptPubKey"]!!.jsonPrimitive.content to sats(it["amount"]!!)
            }
            fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
            assertEquals(mapOf(hex(payTo) to pay, hex(change) to total - pay - fee), byScript)
            println("ACCEPTED: ${coins.size} inputs, $vsize vB, fee $fee sat, txid ${spend.txid}, mined in block ${block.take(16)}...")
        }
    }
}
