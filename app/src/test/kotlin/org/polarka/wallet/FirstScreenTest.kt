package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import north.core.Chain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first screen. 0.5.0 filled in the name of a public node that did not exist, and 0.5.1 therefore named
 * none. 0.6 offers a wallet server first: the public server of the Northbound project, with its address
 * filled in, and the screen says whose server that is and what it sees. The node of one's own comes second,
 * and another server can be typed in place of the offered one. Nothing here asks any server.
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
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test
    fun aWalletServerIsOfferedFirstAndTheScreenSaysWhoseItIs() {
        val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
        screen.waitUntil(30_000) { shown("server-go") }

        assertEquals("https://api.northboundwallet.org", Setup.DEFAULT_SERVER)
        assertEquals(Setup.DEFAULT_SERVER, typedIn("server-url"), "the offered server is not filled in")
        screen.onNodeWithTag("server-go").assertIsEnabled()
        assertTrue("run by the Northbound project, not by the authors of this app" in textOf("server-intro"), textOf("server-intro"))
        assertTrue("sees all the addresses of your wallet together" in textOf("server-sees") && "cannot take your coins" in textOf("server-sees"), textOf("server-sees"))
        assertTrue(top("server-url") < top("paste"), "the wallet server does not come first")
        assertFalse(shown("public-go"), "a public node is still offered by name")

        // going on shows what would be saved; nothing is saved, and nobody is asked, before the owner says so
        screen.onNodeWithTag("server-go").performClick()
        screen.waitUntil(10_000) { shown("offer-sees") }
        assertEquals(Setup(Setup.DEFAULT_SERVER, "", "", Chain.MAIN, emptyList(), server = true), model.state.offered)
        assertTrue("The server sees all the addresses of your wallet together" in textOf("offer-sees"), textOf("offer-sees"))
        assertNull(setups.value)
        model.dropOffer()

        // another server can be typed in its place, and an empty field offers nothing
        screen.waitUntil(10_000) { shown("server-url") }
        screen.onNodeWithTag("server-url").performTextReplacement("")
        screen.onNodeWithTag("server-go").assertIsNotEnabled()
        screen.onNodeWithTag("server-url").performTextReplacement("server.example.org")
        screen.onNodeWithTag("server-go").performClick()
        screen.waitUntil(10_000) { model.state.offered != null }
        assertEquals(Setup("https://server.example.org", "", "", Chain.MAIN, emptyList(), server = true), model.state.offered)
        assertNull(setups.value)
    }
}
