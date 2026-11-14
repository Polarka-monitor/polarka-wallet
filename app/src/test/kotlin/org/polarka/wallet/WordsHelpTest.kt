package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import north.core.Account
import north.core.Chain
import north.core.Words
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.5.1, after the first restore on a real phone: typed recovery words that are not valid are answered with
 * what is wrong, what was typed stays in the field, and a paper can be checked in full against the wallet in
 * the phone without anything being stored or the stored words being opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class WordsHelpTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-words-help-").toFile()
        Stores.open = { setups }
        Stores.wallet = { wallets }
        Stores.words = { WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1")) }
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
    private fun waitFor(tag: String, millis: Long = 60_000) = screen.waitUntil(millis) { shown(tag) }
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun typedIn(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    private fun says(tag: String, part: String) = screen.waitUntil(15_000) { screen.waitForIdle(); shown(tag) && part in textOf(tag) }

    /** A node with an empty chain: enough for the app to open and to look. */
    private fun emptyNode() = AuditFakeNode { method, _ ->
        when (method) {
            "getblockchaininfo" -> "{\"chain\":\"regtest\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false,\"bestblockhash\":\"${"ab".repeat(32)}\"}"
            "scanblocks" -> "{\"from_height\":0,\"to_height\":1,\"relevant_blocks\":[],\"completed\":true}"
            "scantxoutset" -> "{\"success\":true,\"height\":1,\"unspents\":[]}"
            "getnetworkhashps" -> "1"
            else -> "null"
        }
    }

    @Test
    fun wordsThatAreNotValidAreAnsweredWithWhatIsWrongAndStayInTheField() {
        emptyNode().use { fake ->
            setups.value = fake.setup()
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("tab-MORE")
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("restore-words")
            val words = Words.generate()

            // two slips of the finger: both are named by their place, as they were typed
            val slipped = words.toMutableList().also { it[4] = it[4] + "x"; it[20] = "zzz" }.joinToString(" ")
            screen.onNodeWithTag("restore-words").performTextInput(slipped)
            screen.onNodeWithTag("restore-go").performClick()
            says("wallet-problem", "Not on the list of recovery words: 5 (${words[4]}x), 21 (zzz).")
            assertEquals(slipped, typedIn("restore-words"), "what was typed is gone")

            // one word short
            screen.onNodeWithTag("restore-words").performTextReplacement(words.dropLast(1).joinToString(" "))
            screen.onNodeWithTag("restore-go").performClick()
            says("wallet-problem", "Words typed: 23.")

            // every word on the list and twelve of them, but they do not fit each other
            val twelve = List(12) { "abandon" }.joinToString(" ")
            screen.onNodeWithTag("restore-words").performTextReplacement(twelve)
            screen.onNodeWithTag("restore-go").performClick()
            says("wallet-problem", "together they are not a valid set of recovery words")
            assertEquals(twelve, typedIn("restore-words"))
            assertNull(wallets.value, "a wallet was stored from words that are not valid")
            assertEquals(0, owner.unlocks, "the owner was asked although nothing could be stored")
            assertFalse(File(dir, "words.bin").exists())

            // the app was away for a while: what was typed and what was said about it are gone
            screen.runOnIdle { model.lock() }
            screen.waitUntil(15_000) { screen.waitForIdle(); !shown("wallet-problem") && typedIn("restore-words").isEmpty() }
            assertNull(model.state.walletProblem)

            // put right, in capitals and with two spaces between the words: the wallet is stored and the field is gone
            screen.onNodeWithTag("restore-words").performTextReplacement(words.joinToString("  ").uppercase())
            screen.onNodeWithTag("restore-go").performClick()
            screen.waitUntil(30_000) { screen.waitForIdle(); wallets.value != null }
            assertEquals(Account.fromWords(words, Chain.REGTEST).watchKey().xpub, wallets.value!!.xpub)
            assertEquals(1, owner.unlocks)
            screen.waitUntil(15_000) { screen.waitForIdle(); !shown("restore-words") }
            assertEquals(Notice.WALLET_READY, model.state.notice)       // the paper was just typed in full
        }
    }

    @Test
    fun aWalletThatWasJustMadeIsFollowedByTheAdviceToCheckThePaperInFull() {
        emptyNode().use { fake ->
            setups.value = fake.setup()
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("tab-MORE")
            screen.runOnIdle { model.startNewWallet() }
            screen.waitUntil(15_000) { screen.waitForIdle(); model.state.newWords != null }
            val words = model.state.newWords!!.words
            screen.runOnIdle { model.confirmNewWallet(owner) }
            screen.waitUntil(30_000) { screen.waitForIdle(); wallets.value != null && model.state.wallet != null }
            assertEquals(Notice.WALLET_MADE, model.state.notice)
            says("notice", "check your paper in full")

            // and the check it points to accepts the words that were shown
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("backup-words")
            screen.onNodeWithTag("backup-words").performTextInput(words.joinToString(" "))
            screen.onNodeWithTag("backup-go").performClick()
            says("backup-result", "They match")
            assertEquals(1, owner.unlocks, "the check of the paper opened the stored words")
        }
    }

    @Test
    fun aPaperIsCheckedInFullAgainstTheWalletInThePhone() {
        emptyNode().use { fake ->
            val words = Words.generate()
            val wallet = WalletInfo(Chain.REGTEST, Account.fromWords(words, Chain.REGTEST).watchKey().xpub, lockOnOpen = false)
            setups.value = fake.setup()
            wallets.value = wallet
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("tab-MORE")
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("backup-words")

            // the words of this wallet: the app says so and empties the field
            screen.onNodeWithTag("backup-words").performTextInput(words.joinToString(" "))
            screen.onNodeWithTag("backup-go").performClick()
            says("backup-result", "They match")
            screen.waitUntil(15_000) { screen.waitForIdle(); typedIn("backup-words").isEmpty() }

            // valid words of another wallet: the app says so, and what was typed stays
            val other = Words.generate().joinToString(" ")
            screen.onNodeWithTag("backup-words").performTextInput(other)
            screen.waitUntil(15_000) { screen.waitForIdle(); !shown("backup-result") }       // typing takes the earlier answer away
            screen.onNodeWithTag("backup-go").performClick()
            says("backup-result", "of another wallet")
            assertEquals(other, typedIn("backup-words"))

            // the right paper with one word copied wrongly
            val slipped = words.toMutableList().also { it[11] = "qqq" }.joinToString(" ")
            screen.onNodeWithTag("backup-words").performTextReplacement(slipped)
            screen.onNodeWithTag("backup-go").performClick()
            says("backup-problem", "Not on the list of recovery words: 12 (qqq).")
            assertFalse(shown("backup-result"))
            assertEquals(slipped, typedIn("backup-words"))

            // nothing was stored or changed, and the owner was not asked: the stored words stayed closed
            assertEquals(wallet.xpub, wallets.value!!.xpub)
            assertEquals(wallet.xpub, model.state.wallet!!.xpub)
            assertEquals(0, owner.unlocks + owner.shows, "the owner was asked for a comparison that needs no stored words")
            assertFalse(File(dir, "words.bin").exists(), "the words that were checked were stored")

            // the same help where the words of a paper address are typed
            val paper = Words.generate().toMutableList().also { it[0] = "paperr" }.joinToString(" ")
            screen.onNodeWithTag("paper-words").performTextInput(paper)
            screen.onNodeWithTag("paper-look").performClick()
            says("paper-problem", "Not on the list of recovery words: 1 (paperr).")
            assertEquals(paper, typedIn("paper-words"))
        }
    }

    @Test
    fun typedWordsAndWhatWasSaidAboutThemGoWhenTheAppWasAwayOrThePageIsLeft() {
        emptyNode().use { fake ->
            val words = Words.generate()
            val wallet = WalletInfo(Chain.REGTEST, Account.fromWords(words, Chain.REGTEST).watchKey().xpub, lockOnOpen = false)
            setups.value = fake.setup()
            wallets.value = wallet
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("tab-MORE")
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("backup-words")
            val slipped = words.toMutableList().also { it[2] = "qqq" }.joinToString(" ")

            fun typeBoth() {
                screen.onNodeWithTag("backup-words").performTextInput(slipped)
                screen.onNodeWithTag("backup-go").performClick()
                says("backup-problem", "3 (qqq)")
                screen.onNodeWithTag("paper-words").performTextInput(slipped)
                screen.onNodeWithTag("paper-look").performClick()
                says("paper-problem", "3 (qqq)")
                assertEquals(slipped, typedIn("backup-words"))
                assertEquals(slipped, typedIn("paper-words"))
            }

            // the app was away for a while; this wallet does not lock, so the same page is still there
            typeBoth()
            screen.runOnIdle { model.lock() }
            screen.waitUntil(15_000) {
                screen.waitForIdle()
                !shown("backup-problem") && !shown("paper-problem") && typedIn("backup-words").isEmpty() && typedIn("paper-words").isEmpty()
            }
            assertFalse(model.state.locked)
            assertNull(model.state.backupProblem)
            assertNull(model.state.paperProblem)

            // another page and back
            typeBoth()
            screen.onNodeWithTag("tab-OVERVIEW").performClick()
            screen.waitUntil(15_000) { screen.waitForIdle(); !shown("backup-words") }
            assertNull(model.state.backupProblem, "a message that names a typed word outlived the page")
            assertNull(model.state.paperProblem, "a message that names a typed word outlived the page")
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("backup-words")
            assertFalse(shown("backup-problem"))
            assertFalse(shown("paper-problem"))
            assertEquals("", typedIn("backup-words"))
            assertEquals("", typedIn("paper-words"))
            assertEquals(0, owner.unlocks + owner.shows)
        }
    }
}
