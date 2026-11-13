package io.github.severka_monitor.northwatch

import north.core.Account
import north.core.Chain
import north.core.Words
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wallet's engine against a real TrueNorth Core on regtest, through a node account set up like the one
 * for the phone. Runs when TN_BIN names the folder with the official programs.
 */
class EngineTest {
    private class PlainKey : KeyHolder {
        private val fixed = SecretKeySpec(ByteArray(32) { (it * 5 + 3).toByte() }, "AES")
        private var present = false
        override fun key(create: Boolean): SecretKey? = if (present || create) fixed.also { present = true } else null
        override fun delete() { present = false }
    }

    @Test
    fun theWalletFileAndTheWordVaultKeepWhatTheyAreGiven() {
        val dir = Files.createTempDirectory("north-engine-test-").toFile()
        try {
            val words = Words.generate()
            val info = WalletInfo(Chain.MAIN, Account.fromWords(words, Chain.MAIN).watchKey().xpub, 3, 1, "18.6", "0.02", "USD", false)
            assertEquals(info, WalletInfo.parse(info.toJson()))
            assertEquals(23, info.window)
            assertFailsWith<Exception> { WalletInfo.parse(info.copy(chain = Chain.REGTEST).toJson()) }      // the key is of another network
            val store = SealedWalletStore(File(dir, "wallet.bin"), PlainKey())
            assertNull(store.load())
            store.save(info)
            assertEquals(info, store.load())

            val vault = WordVault(SealedBytes(File(dir, "words.bin"), PlainKey(), "north-wallet-words/1"))
            assertFalse(vault.exists())
            vault.store(vault.encryptor(), words)
            assertTrue(vault.exists())
            assertEquals(words, vault.open(vault.decryptor()))
            val raw = File(dir, "words.bin").readBytes().decodeToString()
            assertTrue(words.none { it.length > 4 && raw.contains(it) }, "the words must not be readable in the file")
            // a file sealed for one purpose cannot be opened as another
            val other = SealedBytes(File(dir, "words.bin"), PlainKey().also { it.key(true) }, Setup.FORMAT)
            assertFailsWith<Exception> { other.read() }
            vault.destroy()
            assertFalse(vault.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aWalletIsFetchedPaidFromAndAPaperAddressIsEmptied() {
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
        val allowed = "getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee," +
            "testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps"
        val dir = Files.createTempDirectory("north-engine-test-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
            "-disablewallet=1", "-server=1", "-txindex=1", "-blockfilterindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
            "-rpcport=$port", "-rpcauth=phone:$salt\$$hash", "-rpcwhitelist=phone:$allowed", "-rpcwhitelistdefault=0", "-printtoconsole=0",
        ).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        fun cli(vararg args: String): String {
            val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$dir", "-chain=regtest", "-rpcconnect=127.0.0.1",
                "-rpcport=$port") + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${args.first()}: $out" }
            return out
        }
        try {
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end && runCatching { cli("getblockcount") }.isFailure) Thread.sleep(300)

            val words = Words.generate()                    // the phone's own wallet
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val paperWords = Words.generate()               // a paper address, watched by the phone
            val paper = Account.fromWords(paperWords, Chain.REGTEST)
            val other = Account.fromWords(Words.generate(), Chain.REGTEST)
            val coin = 512_00000000L
            cli("generatetoaddress", "2", wallet.address(0, 0))
            cli("generatetoaddress", "3", paper.address(0, 0))
            cli("generatetoaddress", "125", other.address(0, 9))                 // tip 130

            val setup = Setup("http://127.0.0.1:$port/", "phone", password, Chain.REGTEST, listOf(paper.address(0, 0)))
            var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub)
            val engine = Engine(setup)

            // without a wallet the engine still shows the watched address
            val watchOnly = engine.fetch(null)
            assertEquals(0, watchOnly.walletSat)
            assertEquals(3 * coin, watchOnly.watchedSat)
            assertEquals(3, watchOnly.watchedMoves.size)

            var seen = engine.fetch(info)
            assertEquals(130, seen.tip)
            assertEquals(2 * coin, seen.spendableSat)
            assertEquals(0, seen.immatureSat)
            assertEquals(3 * coin, seen.watchedSat)
            assertEquals(5 * coin, seen.totalSat)
            assertEquals(listOf(1, 2), seen.moves.map { it.height })
            assertEquals(listOf(3, 4, 5), seen.watchedMoves.map { it.height })
            assertTrue(seen.hashps!! > 0 && seen.secondsPerBlock != null)

            // a payment: planned from a fresh look, refused with wrong words, sent with the right ones
            val plan = engine.plan(info, other.address(0, 0), 100_00000000L, 2)
            assertEquals(Problem.Kind.WRONG_WORDS, assertFailsWith<EngineError> { engine.send(info.xpub, plan, paperWords) }.kind)
            assertEquals(Problem.Kind.BAD_WORDS, assertFailsWith<EngineError> { engine.send(info.xpub, plan, words.dropLast(1) + "zzzz") }.kind)
            val txid = engine.send(info.xpub, plan, words)
            info = info.copy(changeIndex = info.changeIndex + 1)
            cli("generatetoaddress", "1", other.address(0, 9))                   // tip 131
            seen = engine.fetch(info)
            assertEquals(2 * coin - 100_00000000L - plan.feeSat, seen.walletSat)
            assertEquals(txid, seen.moves.last().txid)
            assertEquals(plan.feeSat, seen.moves.last().feeSat)
            assertEquals(seen.walletSat, seen.moves.sumOf { it.netSat })
            assertEquals(Problem.Kind.BAD_ADDRESS, assertFailsWith<EngineError> { engine.plan(info, "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx", 1000, 2) }.kind)
            assertEquals(Problem.Kind.NOT_ENOUGH, Problem.of(assertFailsWith<Exception> { engine.plan(info, other.address(0, 0), 5000 * coin, 2) }).kind)

            // the paper address: a small part to the phone, the rest to a new paper address, in one transaction
            val found = engine.paperLook(paperWords)
            assertEquals(paper.address(0, 0), found.address)
            assertEquals(3 * coin, found.spendableSat)
            val newPaper = Account.fromWords(Words.generate(), Chain.REGTEST)
            val split = engine.paperPlan(found, wallet.address(0, 1), 1, keepSat = 10_00000000L, restTo = newPaper.address(0, 0))
            assertEquals(Problem.Kind.WRONG_WORDS, assertFailsWith<EngineError> { engine.paperSend(found, split, words) }.kind)
            engine.paperSend(found, split, paperWords)
            cli("generatetoaddress", "1", other.address(0, 9))                   // tip 132
            info = info.copy(receiveIndex = 2)
            seen = engine.fetch(info)
            assertEquals(2 * coin - 100_00000000L - plan.feeSat + 10_00000000L, seen.walletSat)
            assertEquals(0, seen.watchedSat)                                     // the old paper address is empty
            assertEquals(seen.walletSat, seen.moves.sumOf { it.netSat })
            assertEquals(0, seen.watchedMoves.sumOf { it.netSat })
            val rest = Engine(setup.copy(addresses = listOf(newPaper.address(0, 0)))).fetch(null)
            assertEquals(3 * coin - 10_00000000L - split.feeSat, rest.watchedSat)

            // what the notifier asks: nothing new at the tip; then a reward for the wallet and one for the watched address
            val mark = engine.fetch(info).tip
            assertEquals(mark to 0, engine.incomingSince(info, mark).let { it.first to it.second.size })
            assertEquals(0, engine.incomingSince(info, -1).second.size)                       // the first look reports nothing
            cli("generatetoaddress", "1", wallet.address(0, 5))
            cli("generatetoaddress", "1", paper.address(0, 0))
            val (reached, arrived) = engine.incomingSince(info, mark)
            assertEquals(mark + 2, reached)
            assertEquals(listOf(true to coin, false to coin), arrived.map { it.own to it.move.receivedSat })
            assertTrue(arrived.all { it.move.coinbase })
            // the wallet's own payment brings change back; that is not news
            val small = engine.plan(info, other.address(0, 2), 1_00000000L, 1)
            engine.send(info.xpub, small, words)
            info = info.copy(changeIndex = info.changeIndex + 1)
            cli("generatetoaddress", "1", other.address(0, 9))
            assertEquals(0, engine.incomingSince(info, reached).second.size)

            // emptying the phone's wallet: everything that is ripe goes, the fresh reward has to wait
            val all = engine.plan(info, other.address(0, 1), null, 1)
            engine.send(info.xpub, all, words)
            cli("generatetoaddress", "1", other.address(0, 9))
            assertEquals(coin, engine.fetch(info).let { it.walletSat + it.spendableSat })     // only the unripe reward is left
            println("ENGINE OK: fetch, payment, wrong words refused, paper address split to phone and new paper, sweep")
        } finally {
            runCatching { cli("stop") }
            if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
            dir.deleteRecursively()
        }
    }
}
