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
 *
 * The reviewer then looked at the fixes, and what that look left open is here too: what the screens say when
 * there is no room left among the payments that did not go through, the same transaction refused twice, a
 * payment that has waited for a block for over an hour, a refused emptying of a paper address, and a server
 * that never shows a coin again. And from its third look: a payment keeps the time at which it was made.
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

    private fun refusing(relay: ServerRelay) {
        relay.instead = { path, _ ->
            if (path == "/v1/transactions/validate") 200 to "{\"allowed\":false,\"rejection_reason\":\"min relay fee not met\"}" else null
        }
    }

    private fun honest(relay: ServerRelay) {
        relay.instead = { _, _ -> null }
    }

    @Test
    fun withNoRoomLeftARefusedPaymentIsNotPromisedWhatALookCannotDo() {
        val node = ServerNode.start()
        node.use {
            StandIn.start(node).use { server ->
                ServerRelay(server.url).use { relay ->
                    val words = Words.generate()
                    val wallet = Account.fromWords(words, Chain.REGTEST)
                    wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                    vault().let { it.store(it.encryptor(), words) }
                    val shop = fresh().address(0, 0)
                    node.mine(1, wallet.address(0, 0))
                    node.mine(100, fresh().address(0, 0))
                    setups.value = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
                    // the wallet's one coin is read in a first look; then the app is closed
                    val first = Robolectric.buildActivity(MainActivity::class.java).setup()
                    waitFor("wallet-spendable")
                    idle(first.get().model)
                    val only = first.get().model.state.snapshot!!.coins.single().let { it.txid + ":" + it.vout }
                    first.pause().stop().destroy()
                    // The list of payments that did not go through is full: fifty refusals, each on that coin, as a
                    // server that says no to everything leaves them behind.
                    val old = (1..WalletInfo.MAX_DROPPED).map { Dropped("%064x".format(it), coin, 326, setOf(only), System.currentTimeMillis() - 600_000) }
                    wallets.value = wallets.value!!.copy(dropped = old)
                    val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
                    model.goneAfterMillis = 0
                    waitFor("wallet-spendable")
                    idle(model)
                    assertEquals(WalletInfo.MAX_DROPPED, model.state.dropped.size)

                    refusing(relay)
                    model.prepare(shop, "5", false, 2)
                    idle(model)
                    assertEquals(WalletInfo.MAX_DROPPED, assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}").against)
                    model.confirmSend(owner)
                    idle(model)
                    honest(relay)
                    assertEquals(Problem.Kind.REFUSED_NO_ROOM, model.state.sendProblem?.kind)
                    val open = model.state.pending.single()
                    assertTrue(open.unsure)
                    assertEquals(old.map { it.txid }, model.state.dropped.map { it.txid })
                    screen.onNodeWithTag("tab-SEND").performClick()
                    screen.waitUntil(30_000) { said("The server says that the node behind it does not take the transaction (min relay fee not met)") }
                    assertTrue(said("The list of payments that did not go through is full (50), and there is no room for another: this one stays on the Overview page with its outcome open"))
                    assertFalse(said("Refresh in a minute"))
                    assertFalse(said("Nothing was sent"))

                    // looks do not settle it while the list is full, and its entry says why
                    looked(model)
                    looked(model)
                    assertEquals(listOf(open), model.state.pending)
                    screen.onNodeWithTag("tab-OVERVIEW").performClick()
                    waitFor("pending-text")
                    assertTrue("is full (50). If this payment did not go through either, there is no room for it there" in textOf("pending-text"), textOf("pending-text"))
                    assertFalse("Refresh in a minute" in textOf("pending-text"))

                    // no other payment is worked out, and the reason given is the true one
                    model.prepare(shop, "1", false, 2)
                    idle(model)
                    assertNull(model.state.draft)
                    assertEquals(Problem(Problem.Kind.NO_ROOM), model.state.sendProblem)
                    screen.onNodeWithTag("tab-SEND").performClick()
                    screen.waitUntil(30_000) { said("the list of payments that did not go through is full (50). If a refresh shows the payment as waiting for a block, that is settled") }

                    // the owner forgets one entry: the next looks move the open payment into the list, and a payment is worked out again
                    model.forgetDropped(old[0].txid, owner)
                    screen.waitUntil(30_000) { model.state.dropped.size == WalletInfo.MAX_DROPPED - 1 }
                    looked(model)
                    looked(model)
                    assertTrue(model.state.pending.isEmpty(), "still open: ${model.state.pending}")
                    assertEquals((old.drop(1).map { it.txid } + open.txid).toSet(), model.state.dropped.map { it.txid }.toSet())
                    assertEquals(WalletInfo.MAX_DROPPED, model.state.dropped.size)
                    model.prepare(shop, "1", false, 2)
                    idle(model)
                    assertEquals(WalletInfo.MAX_DROPPED, assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}").against)
                }
            }
        }
    }

    @Test
    fun theSameTransactionRefusedTwiceIsKeptOnce() {
        val node = ServerNode.start()
        node.use {
            StandIn.start(node).use { server ->
                ServerRelay(server.url).use { relay ->
                    val words = Words.generate()
                    val wallet = Account.fromWords(words, Chain.REGTEST)
                    wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                    vault().let { it.store(it.encryptor(), words) }
                    val shop = fresh().address(0, 0)
                    node.mine(1, wallet.address(0, 0))
                    node.mine(100, fresh().address(0, 0))
                    setups.value = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
                    val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
                    waitFor("wallet-spendable")
                    idle(model)

                    // everything the wallet has goes to the shop: one coin in, one amount out, nothing comes back
                    refusing(relay)
                    model.prepare(shop, "", true, 2)
                    idle(model)
                    assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    model.confirmSend(owner)
                    idle(model)
                    assertEquals(Problem.Kind.REFUSED_KEPT, model.state.sendProblem?.kind)
                    val kept = model.state.dropped.single()

                    // the owner tries again: the same coin, the same amount, the same fee, and so the same transaction
                    model.prepare(shop, "", true, 2)
                    idle(model)
                    assertEquals(1, assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}").against)
                    model.confirmSend(owner)
                    idle(model)
                    honest(relay)
                    assertEquals(Problem.Kind.REFUSED_KEPT, model.state.sendProblem?.kind)
                    assertEquals(listOf(kept), model.state.dropped, "the same transaction is kept twice, or the second try made another one")
                    assertTrue(model.state.pending.isEmpty())
                }
            }
        }
    }

    @Test
    fun aPaymentThatHasWaitedLongIsNamedBeforeTheSameThingIsPaidAgain() {
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
                    val first = Robolectric.buildActivity(MainActivity::class.java).setup()
                    val model = first.get().model
                    waitFor("wallet-spendable")
                    idle(model)
                    model.prepare(shop, "5", false, 2)
                    idle(model)
                    assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    model.confirmSend(owner)
                    idle(model)
                    assertNotNull(model.state.sentTxid, "problem: ${model.state.sendProblem}")
                    model.clearSent()
                    looked(model)
                    val waiting = model.state.pending.single()
                    assertFalse(waiting.unsure)

                    // a payment that was made a moment ago: nothing is said, on the Overview or where the next one is confirmed
                    waitFor("pending-text")
                    assertFalse(shown("pending-long"))
                    model.prepare(shop, "1", false, 2)
                    idle(model)
                    assertEquals(0, assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}").against)
                    screen.onNodeWithTag("tab-SEND").performClick()
                    waitFor("send-go")
                    assertFalse(shown("send-beside-waiting"))
                    model.dropDraft()
                    first.pause().stop().destroy()

                    // The app is opened again when the payment has waited for over an hour: the time that was written
                    // down is moved back. The server shows the payment as waiting, as one that holds it back would.
                    wallets.value = wallets.value!!.let { w -> w.copy(pending = w.pending.map { it.copy(atMillis = it.atMillis - WalletInfo.LONG_WAIT_MILLIS - 60_000) }) }
                    val later = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
                    waitFor("wallet-spendable")
                    idle(later)
                    assertEquals(listOf(waiting.txid), later.state.pending.map { it.txid })
                    waitFor("pending-long")
                    assertTrue("has waited for a block for over an hour" in textOf("pending-long"), textOf("pending-long"))
                    assertTrue("look at the wallet through another server or node as well" in textOf("pending-long"))
                    later.prepare(shop, "1", false, 2)
                    idle(later)
                    assertEquals(0, assertNotNull(later.state.draft, "problem: ${later.state.sendProblem}").against)
                    screen.onNodeWithTag("tab-SEND").performClick()
                    waitFor("send-beside-waiting")
                    assertTrue("This payment does not stand against it: both could arrive" in textOf("send-beside-waiting"), textOf("send-beside-waiting"))
                    later.dropDraft()

                    // a block with the payment, and nothing is left to say
                    node.mine(1, miner)
                    looked(later)
                    assertTrue(later.state.pending.isEmpty())
                    screen.onNodeWithTag("tab-OVERVIEW").performClick()
                    screen.waitUntil(30_000) { !shown("pending-text") }
                    assertFalse(shown("pending-long"))
                }
            }
        }
    }

    @Test
    fun aRefusedEmptyingOfAPaperAddressIsNotCalledNothing() {
        val node = ServerNode.start()
        node.use {
            StandIn.start(node).use { server ->
                ServerRelay(server.url).use { relay ->
                    val words = Words.generate()
                    val wallet = Account.fromWords(words, Chain.REGTEST)
                    wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                    vault().let { it.store(it.encryptor(), words) }
                    val paperWords = Words.generate()
                    val paper = Account.fromWords(paperWords, Chain.REGTEST)
                    node.mine(1, paper.address(0, 0))
                    node.mine(100, fresh().address(0, 0))
                    setups.value = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
                    val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
                    waitFor("wallet-spendable")
                    idle(model)
                    fun paperDone() = screen.waitUntil(60_000) { screen.waitForIdle(); !model.state.paperBusy }
                    assertTrue(model.paperLook(paperWords.joinToString(" ")))
                    paperDone()
                    assertNull(model.state.paperProblem)
                    val mine = model.state.wallet!!.let { it.watchKey().address(0, it.receiveIndex) }
                    model.paperPrepare(mine, "", "", 2)
                    assertNotNull(model.state.paperDraft, "problem: ${model.state.paperProblem}")

                    // the server says no to the emptying
                    refusing(relay)
                    model.paperSend()
                    paperDone()
                    honest(relay)
                    assertEquals(Problem.Kind.PAPER_REFUSED, model.state.paperProblem?.kind)
                    assertNull(model.state.paperTxid)
                    screen.onNodeWithTag("tab-MORE").performClick()
                    screen.waitUntil(30_000) { said("The server has seen the whole transaction: it could still pass it on while the coins of the paper address are unspent") }
                    assertTrue(said("Until then do not pay the same thing in another way"))
                    assertFalse(said("Nothing was sent"))

                    // the words are still there for another try, and this time the server passes the emptying on
                    assertNotNull(model.state.paperDraft)
                    model.paperSend()
                    paperDone()
                    assertNull(model.state.paperProblem)
                    assertNotNull(model.state.paperTxid)
                }
            }
        }
    }

    @Test
    fun aCoinThatStaysHiddenStopsEveryPaymentUntilItsOwnerForgetsTheEntry() {
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
                    idle(model)
                    refusing(relay)
                    model.prepare(shop, "5", false, 2)
                    idle(model)
                    assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    model.confirmSend(owner)
                    idle(model)
                    honest(relay)
                    assertEquals(Problem.Kind.REFUSED_KEPT, model.state.sendProblem?.kind)
                    val refused = model.state.dropped.single()

                    // from now on the server leaves the coin of that payment out of its list, and does not show it again
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
                    // looks, and blocks that go by, change nothing: the entry stays and no payment is worked out
                    repeat(3) {
                        node.mine(1, miner)
                        looked(model)
                        assertEquals(listOf(refused.txid), model.state.dropped.map { it.txid })
                        model.prepare(shop, "1", false, 2)
                        idle(model)
                        assertNull(model.state.draft)
                        assertEquals(Problem.Kind.EARLIER_PAYMENT, model.state.sendProblem?.kind)
                    }
                    waitFor("dropped-no-coin")
                    assertTrue("Before you forget this payment, look at the wallet through another server or a node of your own" in textOf("dropped-no-coin"), textOf("dropped-no-coin"))

                    // The way on is the owner's own step, after the screen has said what it means.
                    screen.onNodeWithTag("dropped-forget").performClick()
                    screen.waitUntil(30_000) { said("Only if you are sure that it can no longer arrive") }
                    assertEquals(listOf(refused.txid), model.state.dropped.map { it.txid })
                    model.forgetDropped(refused.txid, owner)
                    screen.waitUntil(30_000) { model.state.dropped.isEmpty() }
                    looked(model)
                    model.prepare(shop, "1", false, 2)
                    idle(model)
                    val next = assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    assertEquals(0, next.against)
                    assertTrue(next.plan.inputs.none { it.txid + ":" + it.vout in refused.inputs })
                }
            }
        }
    }

    @Test
    fun aPaymentThatComesBackFromThoseThatDidNotGoThroughKeepsItsTime() {
        val node = ServerNode.start()
        node.use {
            StandIn.start(node).use { server ->
                ServerRelay(server.url).use { relay ->
                    val words = Words.generate()
                    val wallet = Account.fromWords(words, Chain.REGTEST)
                    wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                    vault().let { it.store(it.encryptor(), words) }
                    val shop = fresh().address(0, 0)
                    node.mine(1, wallet.address(0, 0))
                    node.mine(100, fresh().address(0, 0))
                    setups.value = Setup(relay.url, "", "", Chain.REGTEST, emptyList(), server = true)
                    val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
                    waitFor("wallet-spendable")
                    idle(model)

                    // The server takes the payment in and answers as if its node were away. It keeps what it was handed.
                    val handed = java.util.concurrent.atomic.AtomicReference<String>()
                    relay.instead = { path, body ->
                        if (path != "/v1/transactions/broadcast") null else {
                            handed.set(Json.parseToJsonElement(body).jsonObject["transaction_hex"]!!.jsonPrimitive.content)
                            502 to "{\"message\":\"The blockchain node is unavailable or returned an invalid response.\"}"
                        }
                    }
                    model.prepare(shop, "5", false, 2)
                    idle(model)
                    assertNotNull(model.state.draft, "problem: ${model.state.sendProblem}")
                    model.confirmSend(owner)
                    idle(model)
                    honest(relay)
                    assertEquals(Problem.Kind.UNSURE, model.state.sendProblem?.kind)
                    val made = model.state.pending.single()
                    assertTrue(made.unsure && made.atMillis > 0)
                    Thread.sleep(50)                    // every look from here on is later than the payment
                    model.goneAfterMillis = 0           // from here on a payment is called gone as soon as two looks do not find it

                    // nobody knows it: after two looks it is one that did not go through, with the time at which it was made
                    looked(model)
                    looked(model)
                    assertTrue(model.state.pending.isEmpty(), "still open: ${model.state.pending}")
                    assertEquals(listOf(Dropped(made.txid, made.sentSat, made.feeSat, made.inputs, made.atMillis)), model.state.dropped)

                    // the server passes it on after all: it waits for a block again, and its time is still the first one
                    node.cli("sendrawtransaction", handed.get())
                    looked(model)
                    looked(model)
                    assertTrue(model.state.dropped.isEmpty(), "still among those that did not go through: ${model.state.dropped}")
                    assertEquals(listOf(made.copy(unsure = false)), model.state.pending)
                }
            }
        }
    }
}
