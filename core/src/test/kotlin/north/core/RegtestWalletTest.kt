package north.core

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
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wallet as a whole against TrueNorth Core on regtest: the node has no wallet and gets only the two
 * public descriptors. It must find every coin of the wallet, accept a payment planned and signed here,
 * and accept the sweep that empties the wallet. Runs when TN_BIN names the folder with the official programs.
 */
class RegtestWalletTest {
    private class Node(private val bin: File) : AutoCloseable {
        val dir: File = Files.createTempDirectory("north-wallet-test-").toFile()
        private val port = ServerSocket(0).use { it.localPort }
        private val process: Process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0",
            "-fixedseeds=0", "-disablewallet=1", "-server=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
            "-rpcport=$port", "-printtoconsole=0", "-txindex=1", "-blockfilterindex=1",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

        init {
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end && runCatching { cli("getblockcount") }.isFailure) Thread.sleep(300)
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
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val window = 20

    /** What the phone will do: ask the node about the two descriptors, then place every coin on its own key. */
    private fun scan(node: Node, watch: WatchKey): Pair<Int, List<Unspent>> {
        val request = (0..1).joinToString(",", "[", "]") { """{"desc":"${watch.descriptor(it)}","range":[0,${window - 1}]}""" }
        val answer = node.json("scantxoutset", "start", request).jsonObject
        assertTrue(answer["success"]!!.jsonPrimitive.boolean)
        val place = HashMap<String, Pair<Int, Int>>()
        for (branch in 0..1) for (index in 0 until window) place[hex(watch.script(branch, index))] = branch to index
        val coins = answer["unspents"]!!.jsonArray.map { it.jsonObject }.map { u ->
            val (branch, index) = place[u["scriptPubKey"]!!.jsonPrimitive.content] ?: error("the node returned a coin that is not ours")
            Unspent(u["txid"]!!.jsonPrimitive.content, u["vout"]!!.jsonPrimitive.content.toLong(), sats(u["amount"]!!), branch, index,
                u["height"]!!.jsonPrimitive.int, u["coinbase"]?.jsonPrimitive?.boolean ?: false)
        }
        return answer["height"]!!.jsonPrimitive.int to coins
    }

    private fun accepted(node: Node, plan: Plan, signed: SignedSpend) {
        val verdict = node.json("testmempoolaccept", """["${signed.hex}"]""").jsonArray[0].jsonObject
        assertTrue(verdict["allowed"]!!.jsonPrimitive.boolean, "TrueNorth Core refuses the transaction: $verdict")
        assertEquals(plan.vsize, verdict["vsize"]!!.jsonPrimitive.int)
        assertEquals(plan.feeSat, sats(verdict["fees"]!!.jsonObject["base"]!!))
        assertEquals(signed.txid, node.cli("sendrawtransaction", signed.hex))
    }

    @Test
    fun theNodeFindsTheWalletAndAcceptsItsPaymentAndItsSweep() {
        val bin = System.getenv("TN_BIN")?.let { File(it) }
        if (bin == null || !File(bin, "truenorthd").canExecute()) {
            // a test that cannot run is reported as skipped, not as passed
            throw org.opentest4j.TestAbortedException("set TN_BIN to the folder with the official truenorthd and truenorth-cli")
        }
        val wallet = Account.fromWords(Words.generate(), Chain.REGTEST)
        val watch = wallet.watchKey()
        val other = Account.fromWords(Words.generate(), Chain.REGTEST)
        Node(bin).use { node ->
            // rewards on three different keys of the wallet, one of them on the change branch
            node.cli("generatetoaddress", "2", wallet.address(0, 0))
            node.cli("generatetoaddress", "1", wallet.address(0, 7))
            node.cli("generatetoaddress", "1", wallet.address(1, 3))
            node.cli("generatetoaddress", "99", other.address(0, 9))            // tip 103: the reward of block 4 is not ripe yet
            var (tip, coins) = scan(node, watch)
            assertEquals(103, tip)
            assertEquals(setOf(0 to 0, 0 to 7, 1 to 3), coins.map { it.branch to it.index }.toSet())
            assertEquals(listOf(1, 2, 3), coins.filter { it.spendableAt(tip) }.map { it.height }.sorted())

            // history: the node names the blocks that touch the wallet
            val blocks = node.json("scanblocks", "start",
                (0..1).joinToString(",", "[", "]") { """{"desc":"${watch.descriptor(it)}","range":[0,${window - 1}]}""" }, "0").jsonObject
            // A block filter never misses a block that touches the wallet. It can, rarely, name one that does not
            // (about once in a hundred runs of this test, with fresh keys each time), so the count is not exact.
            val named = blocks["relevant_blocks"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val own = (1..4).map { node.cli("getblockhash", it.toString()) }
            assertTrue(named.containsAll(own) && named.size <= 6, "the four blocks of the wallet are among the ${named.size} named")

            // a payment of 700 NORTH at 2 base units per virtual byte; the rest returns to change key 4
            val payTo = other.script(0, 0)
            val plan = Planner.pay(coins.filter { it.spendableAt(tip) }, payTo, 700_00000000L, 2, watch.script(1, 4))
            assertEquals(2, plan.inputs.size)
            accepted(node, plan, plan.sign(wallet))
            node.cli("generatetoaddress", "1", other.address(0, 9))            // tip 104: all four rewards are ripe
            scan(node, watch).let { tip = it.first; coins = it.second }
            assertEquals(plan.changeSat, coins.single { it.branch == 1 && it.index == 4 }.amountSat)
            assertEquals(4 * 512_00000000L - 700_00000000L - plan.feeSat, coins.sumOf { it.amountSat })

            // the sweep: everything that is left goes to one address of the other wallet
            val sweep = Planner.sweep(coins.filter { it.spendableAt(tip) }, other.script(0, 1), 1)
            assertEquals(coins.size, sweep.inputs.size)
            accepted(node, sweep, sweep.sign(wallet))
            node.cli("generatetoaddress", "1", other.address(0, 9))
            assertEquals(0, scan(node, watch).second.size)
            val theirs = node.json("scantxoutset", "start", """["addr(${other.address(0, 0)})","addr(${other.address(0, 1)})"]""").jsonObject
            assertEquals(700_00000000L + sweep.payments[0].amountSat, sats(theirs["total_amount"]!!))
            println("WALLET OK: found by descriptors, payment ${plan.vsize} vB fee ${plan.feeSat}, sweep of ${sweep.inputs.size} coins ${sweep.vsize} vB fee ${sweep.feeSat}")
        }
    }
}
