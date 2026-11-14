package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.5.1, after the first start on a real phone: 0.5.0 filled in the name of a public node that does not exist
 * yet, and the first thing a new user could press led to "the node cannot be reached". While the project has
 * no public node of its own, the first screen names none, says so, and puts the node of one's own first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class FirstScreenTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()

    @Before
    fun prepare() {
        Stores.open = { setups }
        Stores.wallet = { wallets }
    }

    @After
    fun restore() {
        Stores.open = Stores.default
        Stores.wallet = Stores.defaultWallet
    }

    private fun shown(tag: String) = screen.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun top(tag: String) = screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun typedIn(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun whileTheProjectHasNoPublicNodeNoneIsOfferedByName() {
        // When a public node of the project runs, its address goes into PUBLIC_NODE and this test changes with it.
        assertEquals("", PUBLIC_NODE, "a public node is named: is it running, and does the first screen offer it first?")
        Robolectric.buildActivity(MainActivity::class.java).setup().get()
        screen.waitUntil(30_000) { shown("public-go") }

        assertEquals("", typedIn("public-url"), "an address is filled in although there is no public node to name")
        screen.onNodeWithTag("public-go").assertIsNotEnabled()
        assertTrue(shown("public-none"), "the screen does not say that there is no public node of the project yet")
        assertTrue(top("paste") < top("public-url"), "the node of one's own does not come first")

        // a public node that the user knows can still be typed in
        screen.onNodeWithTag("public-url").performTextInput("https://node.example")
        screen.onNodeWithTag("public-go").assertIsEnabled()
    }
}
