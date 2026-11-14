package north.node

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import north.core.Account
import north.core.Chain
import north.core.Coin
import north.core.Payment
import north.core.Qrh
import north.core.Spend
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeClientTest {
    @Test
    fun plainHttpOnlyToPrivateAddresses() {
        for (ok in listOf("http://127.0.0.1:9554/", "http://localhost:9554", "http://100.101.102.103:9554/", "http://192.168.1.5:9554",
            "http://10.0.0.7:1", "https://node.example.org:9443/", "https://8.8.8.8/")) NodeClient(ok, "u", "p")
        for (bad in listOf("http://8.8.8.8:9554/", "http://node.example.org/", "http://100.128.0.1/", "http://100.63.255.255/",
            "ftp://127.0.0.1/", "node.example.org")) {
            assertFailsWith<IllegalArgumentException>(bad) { NodeClient(bad, "u", "p") }
        }
        assertTrue(NodeClient.isPrivate("100.64.0.1") && NodeClient.isPrivate("100.127.255.254") && NodeClient.isPrivate("172.16.3.4"))
        assertFalse(NodeClient.isPrivate("172.32.0.1") || NodeClient.isPrivate("example.org") || NodeClient.isPrivate("1.1.1.1"))
    }

    /**
     * Against a real TrueNorth Core on regtest that is set up like the node for the phone: a user "phone" known
     * only by the hash of its password, allowed to make the wallet's calls and nothing else.
     * Runs when TN_BIN names the folder with the official programs.
     */
    @Test
    fun aRestrictedUserCanWatchAndSendAndNothingElse() {
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
        val allowed = "getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee," +
            "testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry"
        val dir = Files.createTempDirectory("north-node-test-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
            "-disablewallet=1", "-server=1", "-txindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1", "-rpcport=$port",
            "-rpcauth=phone:$salt\$$hash", "-rpcwhitelist=phone:$allowed", "-rpcwhitelistdefault=0", "-printtoconsole=0",
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
            assertEquals(0, node.blockCount())

            fun fresh() = Account.fromWords(MnemonicCode.toMnemonics(ByteArray(32).also { random.nextBytes(it) }), Chain.REGTEST)
            val wallet = fresh()
            val other = fresh()
            cli("generatetoaddress", "3", wallet.address(0, 0))       // rewards at heights 1, 2, 3
            cli("generatetoaddress", "99", other.address(0, 9))       // tip 102: the rewards at 1 and 2 are spendable
            var b = node.balance(listOf(wallet.address(0, 0), wallet.address(1, 0)))
            assertEquals(102, b.tip)
            assertEquals(3, b.utxos.size)
            assertEquals(listOf(1, 2), b.spendable.map { it.height }.sorted())
            assertEquals(listOf(3), b.immature.map { it.height })
            assertEquals(b.totalSat, b.spendableSat + b.immatureSat)
            assertTrue(b.utxos.all { it.coinbase && it.script == wallet.script(0, 0).joinToString("") { c -> "%02x".format(c) } })
            val info = node.chainInfo()
            assertEquals("regtest", info.chain)
            assertEquals(102, info.blocks)

            // spend the two mature rewards with a transaction built by the wallet core, through this client
            val coins = b.spendable.map { Coin(it.txid, it.vout, it.amountSat, 0, 0) }
            val payTo = Qrh.scriptForAddress(Chain.REGTEST, other.address(0, 0))
            val change = wallet.script(1, 0)
            val fee = 2L * Spend.vsize(coins.size, listOf(payTo, change))
            val pay = 300_00000000L
            val spend = Spend.sign(wallet, coins, listOf(Payment(payTo, pay), Payment(change, b.spendableSat - pay - fee)),
                ByteVector32(ByteArray(32).also { random.nextBytes(it) }))
            val verdict = node.testAccept(spend.hex)
            assertTrue(verdict.allowed, "refused: ${verdict.rejectReason}")
            assertEquals(spend.vsize, verdict.vsize)
            assertEquals(fee, verdict.feeSat)
            assertNull(node.confirmations(spend.txid))
            assertEquals(spend.txid, node.broadcast(spend.hex))
            assertEquals(0, node.confirmations(spend.txid))
            cli("generatetoaddress", "1", other.address(0, 9))
            assertEquals(1, node.confirmations(spend.txid))
            b = node.balance(listOf(wallet.address(0, 0), wallet.address(1, 0)))
            assertEquals(103, b.tip)
            assertEquals(b.spendableSat, spend.transaction.txOut[1].amount.toLong() + b.utxos.single { it.coinbase && it.height == 3 }.amountSat)
            assertEquals(0, b.immatureSat)                            // the reward at height 3 is now 100 blocks deep

            // a transaction that spends the same coins again is refused, and the reason is passed on
            val again = node.testAccept(spend.hex)
            assertFalse(again.allowed)
            assertTrue(again.rejectReason!!.isNotEmpty())

            // what the phone's user must NOT be able to do
            for (method in listOf("stop", "getpeerinfo", "getnewaddress", "generatetoaddress", "dumptxoutset", "setban", "logging")) {
                assertEquals(403, assertFailsWith<NodeError>(method) { node.call(method) }.httpStatus, method)
            }
            assertEquals(401, assertFailsWith<NodeError> { NodeClient("http://127.0.0.1:$port/", "phone", "wrong").blockCount() }.httpStatus)
            assertEquals(401, assertFailsWith<NodeError> { NodeClient("http://127.0.0.1:$port/", "miner", password).blockCount() }.httpStatus)
            assertEquals(0, assertFailsWith<NodeError> { NodeClient("http://127.0.0.1:1/", "phone", password, 3000).blockCount() }.httpStatus)
            assertEquals(103, cli("getblockcount").toInt())            // the node is still running: "stop" was really refused
            println("NODE CLIENT OK: balance, test, send and confirmations through a restricted user; 7 other calls refused")
        } finally {
            runCatching { cli("stop") }
            if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
            dir.deleteRecursively()
        }
    }
}
