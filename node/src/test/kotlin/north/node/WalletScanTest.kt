package north.node

import north.core.Account
import north.core.Chain
import north.core.Planner
import north.core.Unspent
import north.core.Words
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A whole wallet seen through the node account that the phone uses: found by its two public descriptors,
 * its history rebuilt from the blocks the node names, and the sum of the history equal to the balance.
 * Runs when TN_BIN names the folder with the official programs.
 */
class WalletScanTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun balanceAndHistoryOfAWalletThroughTheRestrictedUser() {
        val bin = System.getenv("TN_BIN")?.let { File(it) }
        if (bin == null || !File(bin, "truenorthd").canExecute()) {
            // a test that cannot run is reported as skipped, not as passed
            throw org.opentest4j.TestAbortedException("set TN_BIN to the folder with the official truenorthd and truenorth-cli")
        }
        val random = SecureRandom()
        val password = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hash = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt.encodeToByteArray(), "HmacSHA256")) }
            .doFinal(password.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        // the list of the real node for the phone, as set on 13 November 2026
        val allowed = "getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee," +
            "testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps"
        val dir = Files.createTempDirectory("north-scan-test-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
            "-disablewallet=1", "-server=1", "-txindex=1", "-blockfilterindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
            "-rpcport=$port", "-rpcauth=phone:$salt\$$hash", "-rpcwhitelist=phone:$allowed", "-rpcwhitelistdefault=0", "-printtoconsole=0",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        fun cli(vararg args: String): String {
            val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$dir", "-chain=regtest", "-rpcconnect=127.0.0.1",
                "-rpcport=$port") + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${args.first()}: $out" }
            return out
        }
        try {
            val node = NodeClient("http://127.0.0.1:$port/", "phone", password)
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end && runCatching { node.blockCount() }.isFailure) Thread.sleep(300)

            val wallet = Account.fromWords(Words.generate(), Chain.REGTEST)
            val watch = wallet.watchKey()
            val other = Account.fromWords(Words.generate(), Chain.REGTEST)
            val window = 20
            val descriptors = listOf(watch.descriptor(0), watch.descriptor(1))
            val place = HashMap<String, Pair<Int, Int>>()
            for (branch in 0..1) for (index in 0 until window) place[hex(watch.script(branch, index))] = branch to index

            cli("generatetoaddress", "2", wallet.address(0, 0))
            cli("generatetoaddress", "1", wallet.address(0, 7))
            cli("generatetoaddress", "1", wallet.address(1, 3))
            cli("generatetoaddress", "99", other.address(0, 9))           // tip 103
            var seen = node.scanDescriptors(descriptors, window)
            assertEquals(103, seen.tip)
            assertEquals(4, seen.utxos.size)
            assertEquals(3 * 512_00000000L, seen.spendableSat)
            assertEquals(512_00000000L, seen.immatureSat)
            assertTrue(seen.utxos.all { it.script in place })

            // a payment planned from what the node reported, signed here, sent through the same restricted user
            val coins = seen.spendable.map { u -> place.getValue(u.script).let { Unspent(u.txid, u.vout, u.amountSat, it.first, it.second, u.height, u.coinbase) } }
            val plan = Planner.pay(coins, other.script(0, 0), 700_00000000L, 2, watch.script(1, 4))
            val signed = plan.sign(wallet)
            val verdict = node.testAccept(signed.hex)
            assertTrue(verdict.allowed, "refused: ${verdict.rejectReason}")
            assertEquals(plan.feeSat, verdict.feeSat)
            assertEquals(signed.txid, node.broadcast(signed.hex))
            cli("generatetoaddress", "1", other.address(0, 9))            // tip 104
            seen = node.scanDescriptors(descriptors, window)
            assertEquals(4 * 512_00000000L - 700_00000000L - plan.feeSat, seen.totalSat)

            // history: the node names the blocks, the phone reads them and adds up what was the wallet's
            val named = node.relevantBlocks(descriptors, window)
            assertEquals(104, named.toHeight)
            val moves = History.movements(named.hashes.map { node.block(it) }, place.keys)
            assertEquals(listOf(1, 2, 3, 4, 104), moves.map { it.height })
            assertTrue(moves.take(4).all { it.coinbase && it.receivedSat == 512_00000000L && it.spentSat == 0L && it.feeSat == null })
            val payment = moves.last()
            assertEquals(signed.txid, payment.txid)
            assertEquals(plan.feeSat, payment.feeSat)
            assertEquals(-(700_00000000L + plan.feeSat), payment.netSat)
            assertEquals(plan.changeSat, payment.receivedSat)
            assertEquals(seen.totalSat, moves.sumOf { it.netSat }, "the history adds up to the balance")
            // asking only for the newer part still finds the payment's block
            assertTrue(node.relevantBlocks(descriptors, window, 100).hashes.size == 1)

            // a single watched address (as the cold address in the app) goes the same way, without key numbers
            // (the payment may or may not have spent that address's reward: the coins are chosen by amount, then by id)
            val single = listOf("addr(${wallet.address(0, 7)})")
            val script7 = hex(watch.script(0, 7))
            val onIt = node.scanDescriptors(single, 1).totalSat
            assertEquals(seen.utxos.filter { it.script == script7 }.sumOf { it.amountSat }, onIt)
            val alone = History.movements(node.relevantBlocks(single, 1).hashes.map { node.block(it) }, setOf(script7))
            assertEquals(3, alone.first().height)
            assertEquals(512_00000000L, alone.first().receivedSat)
            assertEquals(onIt, alone.sumOf { it.netSat })

            assertTrue(node.networkHashps() > 0.0)
            for (method in listOf("stop", "getpeerinfo", "getnewaddress", "generatetoaddress")) {
                assertEquals(403, assertFailsWith<NodeError>(method) { node.call(method) }.httpStatus, method)
            }
            println("WALLET SCAN OK: 4 coins by descriptors, payment sent, history of ${moves.size} movements adds up to the balance")
        } finally {
            runCatching { cli("stop") }
            if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
            dir.deleteRecursively()
        }
    }
}
