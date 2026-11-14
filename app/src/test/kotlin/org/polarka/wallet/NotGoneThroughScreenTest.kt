package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertTrue

/** 0.4.2 on the screen: a payment that did not go through is shown on the overview, and only the owner lets it go. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class NotGoneThroughScreenTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-not-gone-through-screen-").toFile()
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

    @Test
    fun aPaymentThatDidNotGoThroughIsShownAndOnlyTheOwnerLetsItGo() {
        val hash = "ab".repeat(32)
        AuditFakeNode { method, _ ->
            when (method) {
                "getblockchaininfo" -> "{\"chain\":\"regtest\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false,\"bestblockhash\":\"$hash\"}"
                "scanblocks" -> "{\"from_height\":0,\"to_height\":1,\"relevant_blocks\":[],\"completed\":true}"
                "scantxoutset" -> "{\"success\":true,\"height\":1,\"unspents\":[]}"
                "getnetworkhashps" -> "1"
                else -> "null"
            }
        }.use { fake ->
            val gone = Dropped("cd".repeat(32), 5_00000000L, 326, setOf("ef".repeat(32) + ":0"), 1)
            setups.value = fake.setup()
            wallets.value = WalletInfo(Chain.REGTEST, Account.fromWords(Words.generate(), Chain.REGTEST).watchKey().xpub, lockOnOpen = false, dropped = listOf(gone))
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("dropped-forget")
            screen.waitUntil(60_000) { screen.waitForIdle(); !model.state.busy }
            assertTrue("could still arrive from another node" in textOf("dropped-text"), textOf("dropped-text"))
            assertEquals("-5.00000326", textOf("dropped-amount"))
            assertEquals(listOf(gone), model.state.dropped, "a look at a node that shows nothing about it leaves it where it is")

            owner.there = false
            screen.onNodeWithTag("dropped-forget").performClick()
            waitFor("dropped-forget-yes")
            screen.onNodeWithTag("dropped-forget-yes").performClick()
            screen.waitUntil(30_000) { screen.waitForIdle(); owner.shows == 1 }
            screen.waitForIdle()
            assertEquals(1, model.state.dropped.size, "somebody who is not the owner let the payment go")
            assertTrue(shown("dropped-forget"))

            owner.there = true
            screen.onNodeWithTag("dropped-forget").performClick()
            waitFor("dropped-forget-yes")
            screen.onNodeWithTag("dropped-forget-yes").performClick()
            screen.waitUntil(30_000) { screen.waitForIdle(); model.state.dropped.isEmpty() && !shown("dropped-forget") }
            screen.waitUntil(30_000) { wallets.value!!.dropped.isEmpty() }
        }
    }
}
