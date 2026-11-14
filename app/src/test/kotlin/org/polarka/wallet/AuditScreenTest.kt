package org.polarka.wallet

import android.content.res.Configuration
import android.os.Bundle
import android.util.SparseArray
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlin.test.assertTrue

/**
 * Independent audit of 0.3.1: the real activity and the real screens under Robolectric, as in ScreenTest.
 * Every test describes what a user is entitled to expect; a failure is a finding (see AUDIT.md for which).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class AuditScreenTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val mainAddress = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"
    private val nowhere = Setup("https://127.0.0.1:1/", "phone", "Pass-1", Chain.MAIN, listOf(mainAddress))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("north-audit-screen-").toFile()
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
    private fun waitFor(tag: String, millis: Long = 30_000) = screen.waitUntil(millis) { shown(tag) }
    private fun nodesWith(part: String) = screen.onAllNodes(hasText(part, substring = true)).fetchSemanticsNodes().size
    private fun open(section: Section) = screen.onNodeWithTag("tab-" + section.name).performClick()
    private fun typedIn(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /**
     * F-04. The time the app spent in the background is kept in the activity object (MainActivity.leftAt).
     * When Android rebuilds the activity on return (the phone was turned, folded, switched to dark mode, the
     * font size changed) the new object starts with 0 and the lock is skipped, while the model, which survives
     * such a rebuild, is still unlocked.
     */
    @Test
    fun theLockEngagesAlsoWhenThePhoneRebuildsTheScreenOnReturn() {
        setups.value = nowhere
        wallets.value = WalletInfo(Chain.MAIN, Account.fromWords(Words.generate(), Chain.MAIN).watchKey().xpub, lockOnOpen = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val model = controller.get().model
        // Adapted after the retest: this wait now brings the screen into line with the model before it looks
        // (waitForIdle). Before, it only watched the model, and nothing in it made the screen follow: the test
        // timed out before it began whenever the stores were read after the activity was set up, or when
        // Compose's own notice of changes made outside it had stopped working earlier in the same test run
        // (under Robolectric its wake-up can be lost between two tests). A race in the test, not in the app.
        try {
            screen.waitUntil(30_000) {
                screen.waitForIdle()
                model.state.loaded && !model.state.locked                               // the owner is there and unlocks
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val s = model.state
            throw AssertionError("the test did not get started: loaded=${s.loaded} locked=${s.locked} lockNote=${s.lockNote} busy=${s.busy} " +
                "problem=${s.problem}, the owner was asked ${owner.shows} time(s)", e)
        }
        // Adapted after the fix: the moment the app left the front is kept in the model now (that is the fix),
        // so the two minutes are subtracted there and no longer from a field of the activity.
        fun awayForTwoMinutes() {
            controller.pause().stop()
            check(model.leftAtMillis != 0L) { "the test is not valid: leaving the app was not noted" }
            model.leftAtMillis -= 120_000
        }

        // the ordinary case: two minutes in the background, then back. Whoever holds the phone now is not the owner.
        owner.there = false
        awayForTwoMinutes()
        controller.restart().resume()
        screen.waitForIdle()
        check(model.state.locked) { "the test is not valid: the lock does not engage even in the ordinary case" }
        owner.there = true
        screen.onNodeWithTag("unlock").performClick()
        screen.waitUntil(10_000) { !model.state.locked }

        // the same two minutes; on return Android rebuilds the activity because the configuration changed meanwhile
        owner.there = false
        val askedBefore = owner.shows
        awayForTwoMinutes()
        val turned = Configuration(controller.get().resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        controller.configurationChange(turned)
        screen.waitForIdle()
        check(controller.get().model === model) { "the test is not valid: the model did not survive the rebuild" }
        val locked = model.state.locked
        val inside = runCatching { controller.visible(); screen.waitForIdle(); shown("tab-SEND") }.getOrDefault(false)
        println("AUDIT F-04: back after two minutes and a rebuild of the activity: locked=$locked, the tabs are on the screen=$inside, the owner was asked ${owner.shows - askedBefore} time(s)")
        assertTrue(locked, "after two minutes in the background the wallet is open without anybody being asked: the lock was skipped")
    }

    private fun holds(value: Any?, needle: String, depth: Int = 0): Boolean = when {
        value == null || depth > 60 -> false
        value is CharSequence -> value.toString().contains(needle)
        value is Bundle -> value.keySet().any { holds(value.get(it), needle, depth + 1) }
        value is Iterable<*> -> value.any { holds(it, needle, depth + 1) }
        value is Array<*> -> value.any { holds(it, needle, depth + 1) }
        value is Map<*, *> -> value.values.any { holds(it, needle, depth + 1) }
        value is State<*> -> holds(value.value, needle, depth + 1)
        value is SparseArray<*> -> (0 until value.size()).any { holds(value.valueAt(it), needle, depth + 1) }
        else -> false
    }

    /**
     * F-06. The field for bringing a wallet back keeps its text with rememberSaveable (Tabs.kt, MoreTab), so
     * the words typed so far are written into the activity's saved state whenever the app goes to the
     * background. The field for a paper address next to it uses remember and stays out; that is the right way.
     */
    @Test
    fun wordsTypedForBringingAWalletBackStayOutOfTheSavedState() {
        setups.value = nowhere
        val typed = Words.generate().joinToString(" ")
        val paper = Words.generate().joinToString(" ")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("tab-MORE")
            open(Section.MORE)
            waitFor("restore-words")
            screen.onNodeWithTag("restore-words").performTextInput(typed)
            screen.onNodeWithTag("paper-words").performTextInput(paper)
            screen.waitForIdle()
            // the user switches to another app for a moment: Android asks the activity for its state and keeps it outside the app
            val state = Bundle()
            scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, state) }
            val restoreInState = holds(state, typed.take(60))
            val paperInState = holds(state, paper.take(60))
            scenario.recreate()
            waitFor("restore-words")
            val restoreComesBack = typedIn("restore-words").contains(typed.take(60))
            val paperComesBack = typedIn("paper-words").contains(paper.take(60))
            println("AUDIT F-06: words typed for restore: in the saved state=$restoreInState, back after a rebuild=$restoreComesBack; " +
                "words typed for a paper address: in the saved state=$paperInState, back after a rebuild=$paperComesBack")
            assertFalse(paperInState || paperComesBack, "the words of a paper address reach the saved state")
            assertFalse(restoreInState || restoreComesBack, "recovery words typed into 'Bring back a wallet' are written into the activity's saved state")
        }
    }

    /**
     * F-05. The confirmation of "Empty a paper address" prints the two addresses from the text fields as they are
     * now, not from the plan that will be signed, and the fields stay editable after "Check the payment".
     */
    @Test
    fun thePaperConfirmationShowsTheAddressThatWillBePaid() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val paperWords = Words.generate()
            val paper = Account.fromWords(paperWords, Chain.REGTEST)
            val phone = Account.fromWords(Words.generate(), Chain.REGTEST)
            val pasted = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)       // a valid address that is not the one meant
            val meant = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)        // the new paper address
            val miner = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
            node.mine(3, paper.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup(addresses = listOf(paper.address(0, 0)))
            wallets.value = WalletInfo(Chain.REGTEST, phone.watchKey().xpub, lockOnOpen = false)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("tab-MORE")
                open(Section.MORE)
                waitFor("paper-words")
                screen.onNodeWithTag("paper-words").performTextInput(paperWords.joinToString(" "))
                screen.onNodeWithTag("paper-look").performClick()
                waitFor("paper-spendable")
                screen.onNodeWithTag("paper-keep").performTextInput("10")
                screen.onNodeWithTag("paper-rest").performTextInput(pasted)
                screen.onNodeWithTag("paper-check").performClick()
                waitFor("paper-go")
                // the user reads the confirmation, sees that the address for the rest is not the right one, and corrects the field
                screen.onNodeWithTag("paper-rest").performTextReplacement(meant)
                screen.waitForIdle()
                // Adapted after the fix: a change in a field now throws the worked-out payment away, so there is
                // nothing to confirm until the user has it worked out again. If the button is still there, the
                // test goes on exactly as the auditor wrote it.
                val confirmationGone = !shown("paper-go")
                if (confirmationGone) {
                    screen.onNodeWithTag("paper-check").performClick()
                    waitFor("paper-go")
                }
                val meantOnScreen = nodesWith(meant)
                val pastedOnScreen = nodesWith(pasted)
                val shownForTheRest = screen.onNodeWithTag("paper-rest-shown").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                assertEquals(meant, shownForTheRest, "the confirmation must show the address the plan was made for")
                println("AUDIT F-05 (after the fix): the edit removed the confirmation: $confirmationGone")
                screen.onNodeWithTag("paper-go").performClick()
                waitFor("paper-txid", 60_000)
                node.mine(1, miner)
                val toPasted = node.held(pasted)
                val toMeant = node.held(meant)
                println("AUDIT F-05: when 'Sign and send' was tapped the screen showed the corrected address in $meantOnScreen place(s) and the first one in $pastedOnScreen; " +
                    "the corrected address received ${Format.coins(toMeant)}, the first one ${Format.coins(toPasted)}")
                assertEquals(0L, toPasted, "the confirmation showed one address for the rest and the transaction paid another; that other address received")
                assertTrue(toMeant > 0, "the address shown in the confirmation received nothing")
            }
        }
    }

    /**
     * F-08. Only ripe coins are moved. The confirmation nevertheless says "This empties the paper address
     * completely", and the text above tells the user that the paper is no longer safe afterwards.
     */
    @Test
    fun theWarningDoesNotSayEverythingIsMovedWhenRewardsStayBehind() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val paperWords = Words.generate()
            val paper = Account.fromWords(paperWords, Chain.REGTEST).address(0, 0)
            val phone = Account.fromWords(Words.generate(), Chain.REGTEST)
            val miner = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
            node.mine(1, paper)
            node.mine(100, miner)
            node.mine(1, paper)                                     // a fresh reward: 100 blocks to go
            setups.value = node.setup(addresses = listOf(paper))
            wallets.value = WalletInfo(Chain.REGTEST, phone.watchKey().xpub, lockOnOpen = false)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("tab-MORE")
                open(Section.MORE)
                waitFor("paper-words")
                screen.onNodeWithTag("paper-words").performTextInput(paperWords.joinToString(" "))
                screen.onNodeWithTag("paper-look").performClick()
                waitFor("paper-spendable")
                screen.onNodeWithTag("paper-check").performClick()
                waitFor("paper-go")
                val saysCompletely = nodesWith("empties the paper address completely") > 0
                screen.onNodeWithTag("paper-go").performClick()
                waitFor("paper-txid", 60_000)
                node.mine(1, miner)
                val left = node.held(paper)
                println("AUDIT F-08: the confirmation said 'This empties the paper address completely': $saysCompletely; after the transaction the paper address still holds ${Format.coins(left)}")
                assertFalse(saysCompletely && left > 0, "the app said the paper address is emptied completely, and ${Format.coins(left)} NORTH stayed on it")
            }
        }
    }
}
