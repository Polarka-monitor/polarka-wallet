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
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fifth look of the independent reviewer (0.5.1): what the change to typed words opened or left open. The tests
 * are the reviewer's own (review/independent-review-0.5.1/RETEST4.md says which finding each one showed); they
 * failed at the commit that was reviewed and hold what was done about it. The second one was turned round,
 * because there the README was put right and not the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class Retest4Test {
    /**
     * Stands in for the phone's Keystore, and does what AndroidKeystoreKey.key(create = true) does with it: the
     * key under the alias is returned if there is one; otherwise a new one is made, which takes the security
     * chip a while, and is put under the alias in place of whatever is there by then.
     */
    private class SlowKeystore : KeyHolder {
        @Volatile private var current: SecretKey? = null
        val made = AtomicInteger(0)
        override fun key(create: Boolean): SecretKey? {
            current?.let { return it }
            if (!create) return null
            val n = made.incrementAndGet()
            Thread.sleep(1500L * n)                                     // the chip makes one key after the other
            val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
            current = key
            return key
        }
        override fun delete() { current = null }
    }

    /** The owner answers the first dialog of the phone with a finger and sends a second one away. */
    private class FirstDialogOnly : Owner {
        val asked = AtomicInteger(0)
        override suspend fun unlock(cipher: Cipher): Cipher {
            if (asked.incrementAndGet() > 1) throw OwnerRefused("cancelled")
            return cipher
        }
        override suspend fun present(): Boolean = true
    }

    @get:Rule
    val screen = createEmptyComposeRule()

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-retest4-").toFile()
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

    /**
     * R4-01. Since 0.5.1 the field keeps the typed words, so "Store the wallet" stays enabled
     * after it was tapped, and nothing moves on the screen while the phone makes the key for the words. A second
     * tap starts a second store. Both find no key and both make one; the second key takes the place of the first
     * under the same name. The owner answers the first dialog and sends the unexpected second one away: the
     * words were sealed with the first key, and the phone now holds the second.
     * Done about it: a second store does not start while one is under way (WalletModel.keepWords, the button is
     * off meanwhile), and AndroidKeystoreKey.key looks for a key and makes it in one step.
     */
    @Test
    fun aSecondTapOnStoreTheWalletLeavesWordsThatCanBeOpened() {
        val keystore = SlowKeystore()
        val asker = FirstDialogOnly()
        fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), keystore, "north-wallet-words/1"))
        Stores.words = { vault() }
        MainActivity.testOwner = asker
        emptyNode().use { fake ->
            setups.value = fake.setup()
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("tab-MORE")
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("restore-words")
            val words = Words.generate()
            screen.onNodeWithTag("restore-words").performTextInput(words.joinToString(" "))
            screen.onNodeWithTag("restore-go").performClick()
            screen.waitForIdle()
            val again = shown("restore-go")
            if (again) screen.onNodeWithTag("restore-go").performClick()            // the second tap
            screen.waitUntil(30_000) { screen.waitForIdle(); model.state.wallet != null }
            val end = System.currentTimeMillis() + 8_000                             // a second store, if there is one, ends
            while (System.currentTimeMillis() < end && asker.asked.get() < 2) {
                screen.waitForIdle()
                Thread.sleep(50)
            }
            repeat(10) { screen.waitForIdle(); Thread.sleep(50) }
            val opened = runCatching { vault().let { it.open(it.decryptor()) } }
            println("RETEST4 R4-01: 'Store the wallet' tapped twice (the button was still there for the second tap: $again): the owner was asked ${asker.asked.get()} time(s), " +
                "keys made under the one name: ${keystore.made.get()}, wallet stored: ${model.state.wallet != null}, shown as its problem: ${model.state.walletProblem?.kind}; " +
                "the stored words can be opened: ${opened.isSuccess}" + (opened.exceptionOrNull()?.let { " (${it.javaClass.simpleName})" } ?: ""))
            assertTrue(opened.getOrNull() == words, "the wallet is stored, but its recovery words cannot be opened: they were sealed with a key that has been replaced")
        }
    }

    /**
     * R4-02. The reviewer wrote this test against a sentence of the README that said more than the app does:
     * typed words "go when the page is left". For a paper address that was looked at this was never so, and it
     * is not meant to be: its owner may look something up on another page and come back to empty it. The
     * README was put right, and this is the test turned round. The look stays across pages, as the README now
     * says; it goes when the app has been away for a minute, also for a wallet that does not lock, and when it
     * is dropped by hand.
     */
    @Test
    fun aPaperAddressThatWasLookedAtStaysOpenOnOtherPagesAndGoesAfterAMinuteAwayOrWhenDropped() {
        emptyNode().use { fake ->
            val words = Words.generate()
            wallets.value = WalletInfo(Chain.REGTEST, Account.fromWords(words, Chain.REGTEST).watchKey().xpub, lockOnOpen = false)
            setups.value = fake.setup()
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("tab-MORE")
            screen.onNodeWithTag("tab-MORE").performClick()

            fun look() {
                waitFor("paper-words")
                screen.onNodeWithTag("paper-words").performTextInput(Words.generate().joinToString(" "))
                screen.onNodeWithTag("paper-look").performClick()
                screen.waitUntil(30_000) { screen.waitForIdle(); !model.state.paperBusy && (model.state.paper != null || model.state.paperProblem != null) }
                check(model.state.paper != null) { "the look at the paper address failed: ${model.state.paperProblem}" }
            }

            look()
            screen.onNodeWithTag("tab-OVERVIEW").performClick()
            screen.waitUntil(15_000) { screen.waitForIdle(); !shown("backup-words") }
            assertTrue(model.state.paper != null, "the look at a paper address was dropped when the page was left; the README says that it stays")
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("backup-words")
            screen.waitForIdle()
            assertTrue(shown("paper-check"), "back on the page the paper address can no longer be emptied")
            assertFalse(shown("paper-words"), "back on the page the words are asked for again although the look is held")

            // the app was away for a minute: the look is gone and the field is there again, empty
            screen.runOnIdle { model.lock() }
            screen.waitUntil(15_000) { screen.waitForIdle(); model.state.paper == null && shown("paper-words") }
            assertFalse(model.state.locked)

            // a look that is dropped by hand is gone at once
            look()
            screen.runOnIdle { model.paperDrop() }
            screen.waitUntil(15_000) { screen.waitForIdle(); model.state.paper == null && shown("paper-words") }
        }
    }

    /**
     * Note N4-1. A message that names what was typed stayed on the screen when the typed text was deleted by
     * hand; and what it names can be made of real words of the paper (here: two words without the space between
     * them). Done about it: the message goes when the field is emptied. While there is text in the field it
     * stays, because it says which word to put right, and it shows nothing that the field above it does not show.
     */
    @Test
    fun aMessageThatNamesWhatWasTypedGoesWhenTheTextIsDeletedByHand() {
        emptyNode().use { fake ->
            setups.value = fake.setup()
            Robolectric.buildActivity(MainActivity::class.java).setup().get()
            waitFor("tab-MORE")
            screen.onNodeWithTag("tab-MORE").performClick()
            waitFor("restore-words")
            val words = Words.generate()
            val joined = (words[0] + words[1]).take(16)
            screen.onNodeWithTag("restore-words").performTextInput((listOf(words[0] + words[1]) + words.drop(2)).joinToString(" "))
            screen.onNodeWithTag("restore-go").performClick()
            says("wallet-problem", "1 (")
            val names = "1 ($joined)" in textOf("wallet-problem")
            screen.onNodeWithTag("restore-words").performTextReplacement("")
            repeat(5) { screen.waitForIdle(); Thread.sleep(20) }
            val still = shown("wallet-problem") && "1 ($joined)" in textOf("wallet-problem")
            println("RETEST4 note: two words typed without the space: the message names them as one (real words of the paper on the screen): $names; " +
                "after the field was emptied by hand the message is still there: $still")
            assertFalse(still, "the typed words were deleted by hand, and the message that names them stays")
        }
    }
}
