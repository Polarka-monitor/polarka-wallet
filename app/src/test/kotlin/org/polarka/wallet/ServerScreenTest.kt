package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.6 on the screen. An owner without a node: a wallet of twelve words is made, the app asks a wallet server
 * (the stand-in on a real TrueNorth Core on regtest), and the pages say "server" where they said "node",
 * show what is on its way, and say what a server does not tell.
 * Skipped when TN_BIN does not name the node's programs or python3 is missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class ServerScreenTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private val coin = 100_000_000L
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-server-screen-").toFile()
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
    private fun said(text: String) = screen.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)

    @Test
    fun aWalletOfTwelveWordsOverAServerAndWhatThePagesSay() {
        val node = ServerNode.start()
        node.use {
            StandIn.start(node).use { server ->
                ServerRelay(server.url).use { relay ->
                    val shop = fresh().address(0, 0)
                    val miner = fresh().address(0, 0)
                    node.mine(1, miner)
                    setups.value = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
                    val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model

                    // no wallet yet: the number of words is the owner's choice, and 24 is what is offered
                    waitFor("wallet-new")
                    assertTrue(shown("words-24") && shown("words-12"))
                    assertTrue(said("24 words carry 256 bits of randomness, 12 words 128 bits"))
                    screen.onNodeWithTag("words-12").performClick()
                    screen.onNodeWithTag("wallet-new").performClick()
                    waitFor("word-0")
                    val words = assertNotNull(model.state.newWords).words
                    assertEquals(12, words.size)
                    assertTrue(shown("word-11") && !shown("word-12"))
                    assertTrue(said("Write these 12 words on paper"))
                    assertTrue(said("I have written all 12 words on paper"))
                    assertNull(wallets.value)
                    screen.onNodeWithTag("written").performClick()
                    screen.onNodeWithTag("new-next").performClick()
                    waitFor("check-0")
                    val asked = (1..12).filter { n -> screen.onAllNodes(hasText("Word number $n")).fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(3, asked.size, "three words are asked: $asked")
                    asked.forEachIndexed { k, n -> screen.onNodeWithTag("check-$k").performTextInput(words[n - 1]) }
                    screen.onNodeWithTag("new-save").performClick()
                    screen.waitUntil(30_000) { wallets.value != null || shown("wallet-problem") }
                    val wallet = Account.fromWords(words, Chain.REGTEST)
                    assertEquals(wallet.watchKey().xpub, wallets.value?.xpub, "the wallet was not stored: ${model.state.walletProblem}")

                    // two rewards that can be spent; the overview says who answers
                    node.mine(2, wallet.address(0, 0))
                    node.mine(100, miner)                                       // tip 103
                    screen.onNodeWithTag("refresh").performClick()
                    screen.waitUntil(60_000) { shown("height") && textOf("height") == "103" }
                    assertEquals("The server answers, block 103", textOf("node-answers"))
                    assertFalse(said("The node answers"))
                    val reward = model.state.snapshot!!.coins[0].amountSat

                    // a payment that waits for a block: the page says how a server shows it, and what is on its way
                    screen.waitUntil(30_000) { screen.waitForIdle(); !model.state.busy }
                    model.prepare(shop, "100", false, 2)
                    screen.waitUntil(30_000) { screen.waitForIdle(); !model.state.sending }
                    val draft = assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    model.confirmSend(owner)
                    screen.waitUntil(60_000) { screen.waitForIdle(); !model.state.sending && !model.state.busy && model.state.pending.size == 1 }
                    assertNull(model.state.sendProblem)
                    screen.onNodeWithTag("tab-OVERVIEW").performClick()
                    waitFor("wallet-arriving")
                    assertEquals(Format.coins(reward - 100 * coin - draft.plan.feeSat), textOf("wallet-arriving"))
                    assertTrue(said("the balance leaves out the coin it is paid from"))
                    assertFalse(said("The node shows the old balance"))

                    // a hand-over that reaches nobody: handed to the server, outcome not known
                    node.mine(1, miner)                                         // tip 104, the first payment is in a block
                    screen.onNodeWithTag("refresh").performClick()
                    screen.waitUntil(60_000) { screen.waitForIdle(); !model.state.busy && model.state.pending.isEmpty() && textOf("height") == "104" }
                    relay.instead = { path, _ -> if (path == "/v1/transactions/broadcast") 502 to "{\"message\":\"no\"}" else null }
                    model.prepare(shop, "5", false, 2)
                    screen.waitUntil(30_000) { screen.waitForIdle(); !model.state.sending }
                    assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    model.confirmSend(owner)
                    screen.waitUntil(60_000) { screen.waitForIdle(); !model.state.sending && model.state.pending.size == 1 }
                    relay.instead = { _, _ -> null }
                    assertEquals(Problem.Kind.UNSURE, model.state.sendProblem?.kind)
                    screen.waitUntil(30_000) { said("Handed to the server, outcome not known") }
                    assertTrue(said("The server did not confirm that it took this payment"))

                    // the mining page says what a server does not tell
                    screen.onNodeWithTag("tab-MORE").performClick()
                    waitFor("tab-MINING")
                    assertTrue(said("the phone asks the server whether a block reward or a payment has arrived"))
                    screen.onNodeWithTag("forget").performClick()
                    screen.waitUntil(10_000) { said("The app forgets the server and the watched addresses") }
                    screen.onNodeWithText("Cancel").performClick()
                    screen.onNodeWithTag("tab-MINING").performClick()
                    waitFor("mining-no-network")
                    assertTrue("does not tell the hash rate" in textOf("mining-no-network"))
                    assertTrue(said("a reward is known as one only while its coin is unspent"))
                }
            }
        }
    }

    @Test
    fun aServerThatCannotBeReachedIsCalledAServer() {
        val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        setups.value = Setup("http://127.0.0.1:$nobody", "", "", Chain.REGTEST, emptyList(), server = true)
        Robolectric.buildActivity(MainActivity::class.java).setup().get()
        waitFor("problem")
        assertTrue("The server cannot be reached" in textOf("problem"), textOf("problem"))
    }
}
