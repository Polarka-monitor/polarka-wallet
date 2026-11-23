package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Added after the review of 0.7.0 (L2): the first screen said "run by the Northbound project" of every server that
 * was typed, Polarka's own included. Now it says whose a server is for exactly the two it offers, however they are
 * typed, and for anything else that the app does not know; the check before saving and the More page say it again.
 * Nothing here asks any server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class ServerOwnerScreenTest {
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
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test
    fun theFirstScreenSaysWhoseTheTypedServerIs() {
        val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
        screen.waitUntil(30_000) { shown("server-go") }
        // what is typed, the chip that is lit, what the screen says, and what the check before saving says
        val cases = listOf(
            Triple(Setup.DEFAULT_SERVER, "server-pick-northbound", "run by the Northbound project, not by the authors of this app") to "the Northbound project",
            Triple("https://node.polarka.org/", "server-pick-polarka", "run by Polarka, the authors of this app") to "Polarka, the authors of this app",
            Triple("node.polarka.org", "server-pick-polarka", "run by Polarka, the authors of this app") to "Polarka, the authors of this app",
            Triple("API.northboundwallet.org", "server-pick-northbound", "run by the Northbound project") to "the Northbound project",
            Triple("https://server.example.org", null, "does not know who runs the server") to "not known to this app",
            Triple("node.polarka.org.example.org", null, "neither the Northbound project nor the authors of this app vouch for it") to "not known to this app",
        )
        for ((typedLitSays, runBy) in cases) {
            val (typed, lit, says) = typedLitSays
            screen.onNodeWithTag("server-url").performTextReplacement(typed)
            screen.waitForIdle()
            assertTrue(says in textOf("server-intro"), "$typed: ${textOf("server-intro")}")
            for (chip in listOf("server-pick-northbound", "server-pick-polarka")) {
                if (chip == lit) screen.onNodeWithTag(chip).assertIsSelected() else screen.onNodeWithTag(chip).assertIsNotSelected()
            }
            screen.onNodeWithTag("server-go").performClick()
            screen.waitUntil(10_000) { shown("offer-run-by") }
            assertTrue(textOf("offer-run-by").startsWith(runBy), "$typed: ${textOf("offer-run-by")}")
            assertNull(setups.value)
            model.dropOffer()
            screen.waitUntil(10_000) { shown("server-url") }
        }
        // a chip puts its address into the field, and nothing else
        screen.onNodeWithTag("server-pick-polarka").performClick()
        screen.waitForIdle()
        assertEquals(Setup.POLARKA_SERVER, screen.onNodeWithTag("server-url").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertTrue("run by Polarka" in textOf("server-intro"))
    }

    @Test
    fun theMorePageNamesWhoseTheServerIs() {
        // Nobody is at this address; the pages are there all the same.
        val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        setups.value = Setup("https://127.0.0.1:$nobody", "", "", Chain.MAIN, emptyList(), server = true)
        Robolectric.buildActivity(MainActivity::class.java).setup().get()
        screen.waitUntil(30_000) { shown("tab-MORE") }
        screen.onNodeWithTag("tab-MORE").performClick()
        screen.waitUntil(30_000) { shown("server-run-by") }
        assertEquals("not known to this app", textOf("server-run-by"))
    }
}
