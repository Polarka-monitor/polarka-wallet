package org.polarka.wallet

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import north.node.ChainInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.6. Through a wallet server the history can lack movements: when it is longer than the app reads in one
 * look, or when the server's lines for one movement do not fit together (see ServerEngine). The look says so,
 * and the History page has to pass that on: a list that looks complete and is not would be worse than a list
 * that says what it is.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryNoteTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private fun shown(tag: String) = screen.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun said(text: String) = screen.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private val nothingYet: String get() = ApplicationProvider.getApplicationContext<Application>().getString(R.string.history_none)

    private fun history(whole: Boolean) {
        val info = ChainInfo("regtest", 5, 5, false, "ab".repeat(32))
        val state = WalletState(loaded = true, snapshot = Snapshot(info, 5, emptyList(), null, emptyList(), emptyList(), null, null, 1L, historyWhole = whole))
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent { PolarkaTheme { HistoryTab(state) } }
        screen.waitUntil(30_000) { said(nothingYet) }
    }

    @Test
    fun aHistoryThatIsNotWholeSaysSo() {
        history(whole = false)
        assertTrue(shown("history-not-whole"))
        val note = screen.onNodeWithTag("history-not-whole").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        assertTrue("Some movements are missing from the history" in note && "The balance and the coins are shown in full" in note, note)
        // Every reason for it is named (the sixth look of the reviewer, L4). Since 0.6.1 the server's lines add up over any
        // number of addresses, and two reasons are left: a history that is too long, and lines that do not fit together.
        assertTrue("longer than the app reads" in note && "do not fit together" in note, note)
        assertFalse("more than a hundred addresses" in note || "coins of somebody else" in note, note)
    }

    @Test
    fun aWholeHistorySaysNothingOfIt() {
        history(whole = true)
        assertFalse(shown("history-not-whole"))
    }
}
