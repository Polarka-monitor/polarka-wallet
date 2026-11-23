package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
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
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the personal edition shows on its receive page, added after the review of 0.7.0 (L5, and L1 and L3): the
 * first address whatever was used before, no "Next address", and the faucet, asked of a stand-in that answers as
 * faucet.py does; the faucet's question is the activity's and not the page's; and when the owner switches the fixed
 * address off, the address shown next is not the one that was fixed. No node is needed: the wallet is on the main
 * network and its server is at an address where nobody answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class PersonalEditionTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val account = Account.fromWords(Words.generate(), Chain.MAIN)

    @Before
    fun prepare() {
        val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        setups.value = Setup("https://127.0.0.1:$nobody", "", "", Chain.MAIN, emptyList(), server = true)
        Stores.open = { setups }
        Stores.wallet = { wallets }
    }

    @After
    fun restore() {
        Stores.open = Stores.default
        Stores.wallet = Stores.defaultWallet
        FaucetModel.base = Faucet.URL_DEFAULT
    }

    private fun shown(tag: String) = screen.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, millis: Long = 30_000) = screen.waitUntil(millis) { shown(tag) }
    private fun open(section: Section) = screen.onNodeWithTag("tab-" + section.name).performClick()
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test
    fun theFirstAddressAlwaysAndTheFaucetForIt() {
        assertEquals("personal", BuildConfig.FLAVOR)
        assertTrue(BuildConfig.FIXED_ADDRESS)
        // not locked: the lock screen would hold the pages back, and it is tested elsewhere
        wallets.value = WalletInfo(Chain.MAIN, account.watchKey().xpub, lockOnOpen = false, receiveIndex = 3)      // three addresses were used before
        FaucetStandIn(bits = 10).use { faucet ->
            FaucetModel.base = faucet.url
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("tab-RECEIVE")
                open(Section.RECEIVE)
                waitFor("receive-address")
                assertEquals(account.address(0, 0), textOf("receive-address"))
                assertFalse(shown("next-address"))
                assertTrue("fixed address" in textOf("receive-note") && "under More, Wallet in this phone" in textOf("receive-note"), textOf("receive-note"))

                // the faucet: the phone does the work, the stand-in checks it by itself and pays the address shown
                screen.onNodeWithTag("faucet-go").performClick()
                waitFor("faucet-txid")
                assertEquals(listOf(account.address(0, 0)), faucet.paid.toList())
                assertEquals(faucet.txid, textOf("faucet-txid"))

                // leaving the page and coming back neither forgets the answer nor asks again
                open(Section.OVERVIEW)
                open(Section.RECEIVE)
                waitFor("faucet-txid")
                assertEquals(1, faucet.claims)

                // a second time the same day: the faucet's own word, named as the faucet's
                screen.onNodeWithTag("faucet-go").performClick()
                waitFor("faucet-refused")
                assertTrue("The faucet said no (ALREADY)" in textOf("faucet-refused"), textOf("faucet-refused"))

                // the owner switches the fixed address off: a new address for each payment, from the one used last on
                open(Section.MORE)
                waitFor("fixed-address-setting")
                screen.onNodeWithTag("fixed-address-setting").performClick()
                screen.waitUntil(10_000) { wallets.value?.fixedAddress == false }
                open(Section.RECEIVE)
                screen.waitUntil(10_000) { shown("receive-address") && textOf("receive-address") == account.address(0, 3) }
                assertTrue(shown("next-address"))
            }
        }
    }

    @Test
    fun switchedOffBeforeAnythingCameTheFixedAddressIsNotOfferedAsANewOne() {
        wallets.value = WalletInfo(Chain.MAIN, account.watchKey().xpub, lockOnOpen = false)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("tab-RECEIVE")
            open(Section.RECEIVE)
            waitFor("receive-address")
            assertEquals(account.address(0, 0), textOf("receive-address"))
            open(Section.MORE)
            waitFor("fixed-address-setting")
            assertTrue("payer can see what all the others paid" in textOf("fixed-address-note"), textOf("fixed-address-note"))
            screen.onNodeWithTag("fixed-address-setting").performClick()
            screen.waitUntil(10_000) { wallets.value?.fixedAddress == false }
            assertEquals(1, wallets.value!!.receiveIndex)
            open(Section.RECEIVE)
            screen.waitUntil(10_000) { shown("receive-address") && textOf("receive-address") == account.address(0, 1) }
            // and on again: the fixed address is the first one, whatever the number has become
            open(Section.MORE)
            waitFor("fixed-address-setting")
            screen.onNodeWithTag("fixed-address-setting").performClick()
            screen.waitUntil(10_000) { wallets.value?.fixedAddress == true }
            open(Section.RECEIVE)
            screen.waitUntil(10_000) { shown("receive-address") && textOf("receive-address") == account.address(0, 0) }
        }
    }

    @Test
    fun theFaucetCanBeStoppedFromTheScreen() {
        wallets.value = WalletInfo(Chain.MAIN, account.watchKey().xpub, lockOnOpen = false)
        FaucetStandIn().use { faucet ->
            // a faucet that does not answer: the button shows the work going on and a way to stop it
            faucet.misbehave = { FaucetStandIn.Raw(FaucetStandIn.http(200, "{}"), holdMs = 120_000) }
            FaucetModel.base = faucet.url
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("tab-RECEIVE")
                open(Section.RECEIVE)
                waitFor("faucet-go")
                screen.onNodeWithTag("faucet-go").performClick()
                waitFor("faucet-stop")
                assertFalse(shown("faucet-go"))
                screen.onNodeWithTag("faucet-stop").performClick()
                screen.waitUntil(10_000) { shown("faucet-go") }
                assertFalse(shown("faucet-refused"), "stopping by hand is not shown as a refusal")
                assertEquals(0, faucet.claims)
            }
        }
    }
}
