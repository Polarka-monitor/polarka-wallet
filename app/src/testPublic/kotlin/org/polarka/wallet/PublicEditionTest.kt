package org.polarka.wallet

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import north.core.Account
import north.core.Chain
import north.core.Words
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the public edition does, added after the review of 0.7.0 (L5): before, no test looked where the editions
 * differ, and a fault that showed address 0 to everybody passed every test. The public edition shows a new address
 * after each payment and after "Next address", the first one only while its owner asks for a fixed address, and it
 * holds no faucet: not on the screen and not in its code. The test with payments needs TN_BIN (a private regtest
 * node) and is skipped without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class PublicEditionTest {
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
    private fun waitFor(tag: String, millis: Long = 30_000) = screen.waitUntil(millis) { shown(tag) }
    private fun open(section: Section) = screen.onNodeWithTag("tab-" + section.name).performClick()
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test
    fun thisIsThePublicEditionAndItHoldsNoFaucet() {
        assertEquals("public", BuildConfig.FLAVOR)
        assertFalse(BuildConfig.FIXED_ADDRESS)
        for (name in listOf("org.polarka.wallet.Faucet", "org.polarka.wallet.FaucetModel", "org.polarka.wallet.Faucet\$Asking")) {
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (text in listOf("faucet_go", "faucet_text", "faucet_done")) {
            assertEquals(0, context.resources.getIdentifier(text, "string", context.packageName), "the text $text is in the public edition")
        }
    }

    @Test
    fun aNewAddressForEachPaymentUnlessTheOwnerAsksForAFixedOne() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val account = Account.fromWords(Words.generate(), Chain.REGTEST)
            val other = Account.fromWords(Words.generate(), Chain.REGTEST)
            node.mine(1, other.address(0, 0))                                          // tip 1
            setups.value = node.setup()
            // not locked: the lock screen would hold the pages back, and it is tested elsewhere
            wallets.value = WalletInfo(Chain.REGTEST, account.watchKey().xpub, lockOnOpen = false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor("height")
                screen.waitUntil(30_000) { textOf("height") == "1" }
                open(Section.RECEIVE)
                waitFor("receive-address")
                assertEquals(account.address(0, 0), textOf("receive-address"))
                assertTrue(shown("next-address"))
                assertFalse(shown("faucet-go"))

                // a payment to the address shown: the next look moves the page on to a new address
                node.mine(1, account.address(0, 0))                                    // tip 2
                open(Section.OVERVIEW)
                scenario.onActivity { it.model.refresh() }                              // a look under way is followed by another
                screen.waitUntil(30_000) { shown("height") && textOf("height") == "2" && wallets.value?.receiveIndex == 1 }
                open(Section.RECEIVE)
                screen.waitUntil(10_000) { shown("receive-address") && textOf("receive-address") == account.address(0, 1) }

                // "Next address" gives the one after it
                screen.onNodeWithTag("next-address").performClick()
                screen.waitUntil(10_000) { textOf("receive-address") == account.address(0, 2) }

                // the owner asks for a fixed address: the first one, and no other is offered
                open(Section.MORE)
                waitFor("fixed-address-setting")
                screen.onNodeWithTag("fixed-address-setting").performClick()
                screen.waitUntil(10_000) { wallets.value?.fixedAddress == true }
                open(Section.RECEIVE)
                screen.waitUntil(10_000) { shown("receive-address") && textOf("receive-address") == account.address(0, 0) }
                assertFalse(shown("next-address"))
                assertTrue("fixed address" in textOf("receive-note"), textOf("receive-note"))

                // and off again: the address that was next before
                open(Section.MORE)
                waitFor("fixed-address-setting")
                screen.onNodeWithTag("fixed-address-setting").performClick()
                screen.waitUntil(10_000) { wallets.value?.fixedAddress == false }
                open(Section.RECEIVE)
                screen.waitUntil(10_000) { shown("receive-address") && textOf("receive-address") == account.address(0, 2) }
                assertTrue(shown("next-address"))
            }
        }
    }
}
