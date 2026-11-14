package org.polarka.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import north.core.Account
import north.core.Chain
import north.core.Spend
import north.core.Words
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real activity and the real screens, run on the desktop by Robolectric (no phone, no emulator).
 * What this cannot show: how the screens look, the Android Keystore and the fingerprint dialog, the file
 * picker, and the native library for phones. Those need a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")          // a wide and very tall screen: every tab and every card of a page is on it
class ScreenTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private val mainAddress = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"

    private class MemorySetups(var value: Setup?) : SetupStore {
        var saves = 0
        var clears = 0
        override fun load(): Setup? = value
        override fun save(setup: Setup) { value = setup; saves++ }
        override fun clear() { value = null; clears++ }
    }

    private class MemoryWallets(var value: WalletInfo? = null) : WalletStore {
        override fun load(): WalletInfo? = value
        override fun save(info: WalletInfo) { value = info }
        override fun clear() { value = null }
    }

    private class PlainKey : KeyHolder {
        private val fixed = SecretKeySpec(ByteArray(32) { (it * 3 + 1).toByte() }, "AES")
        private var present = false
        override fun key(create: Boolean): SecretKey? = if (present || create) fixed.also { present = true } else null
        override fun delete() { present = false }
    }

    /** An owner who always unlocks, and counts how often they were asked. */
    private class WillingOwner : Owner {
        var unlocks = 0
        var shows = 0
        var there = true
        override suspend fun unlock(cipher: Cipher): Cipher { unlocks++; return cipher }
        override suspend fun present(): Boolean { shows++; return there }
    }

    private lateinit var dir: File
    private val wallets = MemoryWallets()
    private val owner = WillingOwner()

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("north-screen-test-").toFile()
        Stores.wallet = { wallets }
        Stores.words = { WordVault(SealedBytes(File(dir, "words.bin"), PlainKey(), "north-wallet-words/1")) }
        MainActivity.testOwner = owner
    }

    @After
    fun restore() {
        Stores.open = Stores.default
        Stores.wallet = Stores.defaultWallet
        Stores.words = Stores.defaultWords
        MainActivity.testOwner = null
        dir.deleteRecursively()
    }

    private fun shown(tag: String) = screen.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, millis: Long = 30_000) = screen.waitUntil(millis) { shown(tag) }
    private fun textAnywhere(part: String) = screen.onAllNodes(hasText(part, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun open(section: Section) = screen.onNodeWithTag("tab-" + section.name).performClick()
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test
    fun setupFromTheClipboardThenForget() {
        val store = MemorySetups(null)
        Stores.open = { store }
        val password = "Hidden-Pass-7731"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("paste")
            clipboard.setPrimaryClip(ClipData.newPlainText("x", "hello there"))
            screen.onNodeWithTag("paste").performClick()
            waitFor("setup-error")
            assertNull(store.value)

            val json = { url: String ->
                """{"format":"north-watch-setup/1","node":{"url":"$url","user":"phone","password":"$password"},"chain":"main","addresses":["$mainAddress"]}"""
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("x", json("http://100.101.102.103:9554/")))
            screen.onNodeWithTag("paste").performClick()
            screen.waitUntil(5_000) { textAnywhere("https://") }
            assertTrue(clipboard.hasPrimaryClip(), "a refused text stays in the clipboard")

            clipboard.setPrimaryClip(ClipData.newPlainText("x", json("https://127.0.0.1:1/")))
            screen.onNodeWithTag("paste").performClick()
            waitFor("save")
            assertTrue(textAnywhere("https://127.0.0.1:1/") && textAnywhere(mainAddress))
            assertTrue(!textAnywhere(password) && !textAnywhere("Hidden"), "the password must not be on the screen")
            assertTrue(clipboard.primaryClip?.let { it.itemCount == 0 || it.getItemAt(0).text.isNullOrEmpty() } ?: true, "clipboard cleared")
            assertEquals(0, store.saves)

            // saving stores it and asks the node; nothing listens there, so the overview says it cannot be reached
            screen.onNodeWithTag("save").performClick()
            waitFor("problem")
            assertEquals(1, store.saves)
            assertEquals(password, store.value!!.password)
            assertTrue(shown("refresh") && !shown("total") && !textAnywhere(password))

            // removing the setup needs a second tap and brings the first screen back
            open(Section.MORE)
            waitFor("forget")
            screen.onNodeWithTag("forget").performClick()
            waitFor("forget-yes")
            assertEquals(0, store.clears)
            screen.onNodeWithTag("forget-yes").performClick()
            waitFor("paste")
            assertEquals(1, store.clears)
        }
        println("SCREEN OK: setup from the clipboard, refusals, save, unreachable node, forget")
    }

    @Test
    fun theSelfTestPasses() {
        Stores.open = { MemorySetups(null) }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("selftest")
            screen.onNodeWithTag("selftest").performClick()
            waitFor("selftest-result", 60_000)
            assertTrue(textAnywhere("TrueNorth Core"), "the self-test must report that it passed")
        }
        println("SCREEN OK: self-test")
    }

    /**
     * The whole wallet through its screens, with a real TrueNorth Core on regtest set up like the node for the
     * phone: a new wallet with its words, receiving, paying, history, mining, emptying a paper address, the lock.
     * Runs when TN_BIN names the folder with the official programs.
     */
    @Test
    fun aWalletIsMadeUsedAndLocked() {
        val bin = System.getenv("TN_BIN")?.let { File(it) }
        if (bin == null || !File(bin, "truenorthd").canExecute()) {
            // a test that cannot run is reported as skipped, not as passed
            throw org.junit.AssumptionViolatedException("set TN_BIN to the folder with the official truenorthd and truenorth-cli")
        }
        val random = SecureRandom()
        val password = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hash = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt.encodeToByteArray(), "HmacSHA256")) }
            .doFinal(password.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val allowed = "getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee," +
            "testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps"
        val data = Files.createTempDirectory("north-screen-node-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            File(bin, "truenorthd").path, "-datadir=$data", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
            "-disablewallet=1", "-server=1", "-txindex=1", "-blockfilterindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
            "-rpcport=$port", "-rpcauth=phone:$salt\$$hash", "-rpcwhitelist=phone:$allowed", "-rpcwhitelistdefault=0", "-printtoconsole=0",
        ).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        fun cli(vararg args: String): String {
            val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$data", "-chain=regtest", "-rpcconnect=127.0.0.1",
                "-rpcport=$port") + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${args.first()}: $out" }
            return out
        }
        try {
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end && runCatching { cli("getblockcount") }.isFailure) Thread.sleep(300)
            val coin = 512_00000000L
            val paperWords = Words.generate()
            val paper = Account.fromWords(paperWords, Chain.REGTEST)
            val other = Account.fromWords(Words.generate(), Chain.REGTEST)
            cli("generatetoaddress", "3", paper.address(0, 0))
            cli("generatetoaddress", "100", other.address(0, 9))                   // tip 103
            Stores.open = { MemorySetups(Setup("http://127.0.0.1:$port/", "phone", password, Chain.REGTEST, listOf(paper.address(0, 0)))) }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var words = emptyList<String>()
                fun refresh(height: String) {
                    screen.onNodeWithTag("refresh").performClick()
                    screen.waitUntil(30_000) { shown("height") && textOf("height") == height }
                }

                // watching only: the paper address is shown, and the app offers to make a wallet
                waitFor("watched-total")
                screen.onNodeWithTag("watched-total").assertTextContains(Format.coins(3 * coin))
                screen.onNodeWithTag("wallet-new").performClick()

                // new words are shown once; three of them are asked back before anything is stored
                waitFor("word-0")
                scenario.onActivity { words = it.model.state.newWords!!.words }
                assertEquals(24, words.size)
                assertTrue(textAnywhere(words[0]) && textAnywhere(words[23]))
                assertNull(wallets.value)
                screen.onNodeWithTag("written").performClick()
                screen.onNodeWithTag("new-next").performClick()
                waitFor("check-0")
                val asked = (1..24).filter { n -> screen.onAllNodes(hasText("Word number $n")).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(3, asked.size, "three words are asked: $asked")
                asked.forEachIndexed { k, n -> screen.onNodeWithTag("check-$k").performTextInput(words[n - 1]) }
                screen.onNodeWithTag("new-save").assertIsEnabled()
                screen.onNodeWithTag("new-save").performClick()
                screen.waitUntil(30_000) { wallets.value != null || shown("wallet-problem") }
                var why = ""
                scenario.onActivity { why = it.model.state.walletProblem.toString() }
                assertNotNull(wallets.value, "the wallet was not stored: $why")
                assertEquals(1, owner.unlocks)
                val wallet = Account.fromWords(words, Chain.REGTEST)
                assertEquals(wallet.watchKey().xpub, wallets.value!!.xpub)
                assertTrue(File(dir, "words.bin").exists())
                assertTrue(words.none { it.length > 4 && File(dir, "words.bin").readBytes().decodeToString().contains(it) })

                // receiving: the first address is the wallet's key 0/0
                open(Section.RECEIVE)
                waitFor("receive-address")
                assertEquals(wallet.address(0, 0), textOf("receive-address"))
                cli("generatetoaddress", "2", wallet.address(0, 0))
                cli("generatetoaddress", "100", other.address(0, 9))               // tip 205
                open(Section.OVERVIEW)
                refresh("205")
                screen.onNodeWithTag("wallet-spendable").assertTextContains(Format.coins(2 * coin))
                screen.onNodeWithTag("total").assertTextContains(Format.coins(5 * coin) + " NORTH")

                // paying 100 NORTH: checked first, then signed after the owner unlocks the words
                open(Section.SEND)
                waitFor("send-to")
                screen.onNodeWithTag("send-to").performTextInput(other.address(0, 0))
                screen.onNodeWithTag("send-amount").performTextInput("100")
                screen.onNodeWithTag("send-check").performClick()
                waitFor("send-go")
                val fee = 2L * Spend.vsize(1, listOf(other.script(0, 0), wallet.script(1, 0)))
                screen.onNodeWithTag("plan-amount").assertTextContains(Format.coins(100_00000000L))
                screen.onNodeWithTag("plan-fee").assertTextContains(Format.coins(fee))
                screen.onNodeWithTag("send-go").performClick()
                waitFor("sent-txid")
                assertEquals(2, owner.unlocks)
                assertEquals(1, wallets.value!!.changeIndex)
                cli("generatetoaddress", "1", other.address(0, 9))                 // tip 206
                open(Section.OVERVIEW)
                refresh("206")
                screen.onNodeWithTag("wallet-spendable").assertTextContains(Format.coins(2 * coin - 100_00000000L - fee))

                // history and mining
                open(Section.HISTORY)
                screen.waitUntil(10_000) { textAnywhere("paid") }
                assertTrue(textAnywhere("-" + Format.coins(100_00000000L + fee)))
                open(Section.MINING)
                waitFor("mining-blocks")
                assertEquals("5", textOf("mining-blocks"))

                // the paper address is emptied into the wallet with its words typed once
                open(Section.MORE)
                waitFor("paper-words")
                screen.onNodeWithTag("paper-words").performTextInput(paperWords.joinToString(" "))
                screen.onNodeWithTag("paper-look").performClick()
                waitFor("paper-spendable")
                screen.onNodeWithTag("paper-spendable").assertTextContains(Format.coins(3 * coin))
                screen.onNodeWithTag("paper-check").performClick()
                waitFor("paper-go")
                screen.onNodeWithTag("paper-go").performClick()
                waitFor("paper-txid")
                assertEquals(2, owner.unlocks)                                      // the paper's own words sign; the phone's words stay locked
                cli("generatetoaddress", "1", other.address(0, 9))                 // tip 207
                open(Section.OVERVIEW)
                refresh("207")
                screen.onNodeWithTag("watched-total").assertTextContains(Format.coins(0))
                val paperFee = 2L * Spend.vsize(3, listOf(wallet.script(0, 0)))
                screen.onNodeWithTag("total").assertTextContains(Format.coins(5 * coin - 100_00000000L - fee - paperFee) + " NORTH")

                // the lock: after it, nothing of the wallet is on the screen until the owner shows up
                owner.there = false
                scenario.onActivity { it.model.lock() }
                waitFor("unlock")
                assertTrue(!shown("total") && !shown("tab-SEND"))
                screen.onNodeWithTag("unlock").performClick()                       // somebody else: it stays locked
                screen.waitUntil(10_000) { owner.shows >= 2 }
                assertTrue(shown("unlock") && !shown("total"))
                owner.there = true
                screen.onNodeWithTag("unlock").performClick()
                screen.waitUntil(10_000) { shown("total") }
            }
            println("SCREEN OK: new wallet with words, receive, pay, history, mining, paper address emptied, lock")
        } finally {
            runCatching { cli("stop") }
            if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
            data.deleteRecursively()
        }
    }
}
