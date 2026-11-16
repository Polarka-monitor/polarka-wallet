package org.polarka.wallet

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import north.node.ServerClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seventh look of the reviewer (0.6.1), R1 and R4. What the screen says when a wallet server's picture of the
 * chain kept changing under a look, and how a count is written in a sentence.
 */
@RunWith(RobolectricTestRunner::class)
class AfterSeventhLookTextsTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private fun said(problem: Problem): String {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent { PolarkaTheme { ProblemCard(problem) } }
        screen.waitUntil(30_000) { screen.onAllNodesWithTag("problem").fetchSemanticsNodes().isNotEmpty() }
        return screen.onNodeWithTag("problem").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    }

    @Test
    fun aPictureThatKeptChangingIsSaidAsThatAndNotAsABusyServerOrAnError() {
        val text = said(Problem(Problem.Kind.MOVING))
        assertTrue("kept changing while the app was reading it" in text && "Try again in a moment" in text, text)
        assertTrue("nothing new can be shown" in text && "long history" in text, text)
        assertFalse("busy" in text || "error" in text, text)
    }

    @Test
    fun theBoundOfCoinsIsWrittenWithItsThousandsApart() {
        val text = said(Problem(Problem.Kind.TOO_MANY_COINS, ServerClient.MAX_COINS.toString()))
        val written = String.format(Locale.getDefault(), "%,d", ServerClient.MAX_COINS)
        assertTrue(written != ServerClient.MAX_COINS.toString() && "more than $written" in text, text)
        assertFalse(ServerClient.MAX_COINS.toString() in text, text)
    }
}
