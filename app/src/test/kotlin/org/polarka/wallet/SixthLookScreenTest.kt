package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sixth look of the independent reviewer (0.6.0), on the screen. A payment that a wallet server refused
 * is not said to be gone ("Nothing was sent" was not true: the server had seen it). The owner is told that
 * it is kept and why, the Overview keeps it, and what stands under it is true in each state: a coin that the
 * server does not list is a reason for alarm only while nothing of the wallet's stands against the payment.
 * Skipped when TN_BIN does not name the node's programs or python3 is missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class SixthLookScreenTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private val coin = 100_000_000L
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-sixth-look-screen-").toFile()
        Stores.open = { setups }
        Stores.wallet = { wallets }
        Stores.words = { vault() }
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

    private fun idle(model: WalletModel) = screen.waitUntil(60_000) { screen.waitForIdle(); !model.state.busy && !model.state.sending }

    private fun looked(model: WalletModel) {
        idle(model)
        model.refresh()
        idle(model)
    }

    @Test
    fun aPaymentThatAServerRefusedStaysOnTheOverviewAndTheScreenSaysWhatIsTrueOfIt() {
        val node = ServerNode.start()
        node.use {
            StandIn.start(node).use { server ->
                ServerRelay(server.url).use { relay ->
                    val words = Words.generate()
                    val wallet = Account.fromWords(words, Chain.REGTEST)
                    wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                    vault().let { it.store(it.encryptor(), words) }
                    val shop = fresh().address(0, 0)
                    val miner = fresh().address(0, 0)
                    node.mine(1, wallet.address(0, 0))
                    node.mine(1, wallet.address(0, 1))
                    node.mine(100, miner)
                    setups.value = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
                    val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
                    waitFor("wallet-spendable")

                    // the server says that its node does not take the payment
                    relay.instead = { path, _ ->
                        if (path == "/v1/transactions/validate") 200 to "{\"allowed\":false,\"rejection_reason\":\"min relay fee not met\"}" else null
                    }
                    idle(model)
                    model.prepare(shop, "5", false, 2)
                    idle(model)
                    assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    model.confirmSend(owner)
                    idle(model)
                    relay.instead = { _, _ -> null }
                    assertEquals(Problem.Kind.REFUSED_KEPT, model.state.sendProblem?.kind)
                    val refused = model.state.dropped.single()
                    screen.onNodeWithTag("tab-SEND").performClick()
                    screen.waitUntil(30_000) { said("The server says that the node behind it does not take the transaction (min relay fee not met)") }
                    assertTrue(said("The server has seen the whole transaction, so the app keeps the payment on the Overview page as one that did not go through"))
                    assertFalse(said("Nothing was sent"))

                    // the Overview keeps it and says when such an entry goes
                    screen.onNodeWithTag("tab-OVERVIEW").performClick()
                    waitFor("dropped-text")
                    assertTrue("The entry goes when the server shows this payment in a block after all" in textOf("dropped-text"), textOf("dropped-text"))
                    assertTrue("the entry stays until you forget it yourself" in textOf("dropped-text"))
                    assertFalse(shown("dropped-no-coin"), "the coin of the payment is listed, and yet the screen says that it is not")

                    // The server leaves the coin of the payment out of its list. Nothing of the wallet's stands against the
                    // payment yet, so no safe payment can be made, and the entry says that and what to do before forgetting it.
                    relay.rewrite = { path, answer ->
                        if (path != "/v1/utxos") answer else {
                            val whole = Json.parseToJsonElement(answer).jsonObject
                            val left = whole["utxos"]!!.jsonArray.filter { u ->
                                val c = u.jsonObject
                                c["transaction_id"]!!.jsonPrimitive.content + ":" + c["output_index"]!!.jsonPrimitive.content !in refused.inputs
                            }
                            JsonObject(whole + ("utxos" to JsonArray(left))).toString()
                        }
                    }
                    looked(model)
                    waitFor("dropped-no-coin")
                    assertTrue("The server lists none of the coins of this payment" in textOf("dropped-no-coin"), textOf("dropped-no-coin"))
                    assertTrue("look at the wallet through another server or a node of your own" in textOf("dropped-no-coin"))
                    assertEquals(listOf(refused.txid), model.state.dropped.map { it.txid })

                    // The coin is shown again and the next payment is built on it. While that one waits for a block the
                    // server does not list the coin either, since a waiting transaction spends it. That is no reason for
                    // alarm now: the second payment stands against the first.
                    relay.rewrite = { _, answer -> answer }
                    looked(model)
                    screen.waitUntil(30_000) { !shown("dropped-no-coin") }
                    model.prepare(shop, "5", false, 2)
                    idle(model)
                    assertEquals(1, assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}").against)
                    model.confirmSend(owner)
                    idle(model)
                    assertNull(model.state.sendProblem)
                    looked(model)
                    assertEquals(1, model.state.pending.size)
                    assertEquals(listOf(refused.txid), model.state.dropped.map { it.txid })
                    assertTrue(model.state.snapshot!!.coins.none { it.txid + ":" + it.vout in refused.inputs }, "the server lists a coin that a waiting payment spends")
                    waitFor("dropped-text")
                    assertFalse(shown("dropped-no-coin"), "the screen warns of a missing coin that the wallet's own waiting payment spends")

                    // a block with the second payment settles both
                    node.mine(1, miner)
                    looked(model)
                    screen.waitUntil(30_000) { !shown("dropped-text") }
                    assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty())

                    // what a server is told about a paper address stands where the words are typed
                    screen.onNodeWithTag("tab-MORE").performClick()
                    screen.waitUntil(30_000) { said("The server is named the first forty addresses of those words") }
                }
            }
        }
    }
}
