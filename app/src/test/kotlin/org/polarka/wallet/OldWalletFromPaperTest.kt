package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import north.core.Account
import north.core.Chain
import north.core.Planner
import north.core.Words
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A wallet that was removed from the phone while it still held coins, emptied later into the phone's new
 * wallet with its recovery words (the part of the screen called "Empty a paper address").
 *
 * This is what happens to somebody who makes a wallet, receives one payment, pays a part of it on, removes
 * the wallet and makes a new one. After the one payment the old wallet's coins lie on its first change
 * address only. The address its words are recognised by, the first receiving address, is empty by then.
 *
 * The node is the official TrueNorth Core on regtest; the test is skipped when TN_BIN does not name it.
 */
@RunWith(RobolectricTestRunner::class)
class OldWalletFromPaperTest {
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-old-wallet-").toFile()
        Stores.open = { setups }
        Stores.wallet = { wallets }
        Stores.words = { vault() }
    }

    @After
    fun restore() {
        Stores.open = Stores.default
        Stores.wallet = Stores.defaultWallet
        Stores.words = Stores.defaultWords
        dir.deleteRecursively()
    }

    private fun until(what: String, millis: Long = 120_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            Thread.sleep(15)
        }
    }

    private fun opened(): WalletModel {
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        if (model.state.locked) {
            model.unlockApp(owner)
            until("the app is unlocked") { !model.state.locked }
        }
        until("the first look at the node") { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
        return model
    }

    private fun refreshed(model: WalletModel) {
        until("no look in progress") { !model.state.busy }
        model.refresh()
        until("a fresh look") { !model.state.busy }
    }

    private fun pay(model: WalletModel, to: String, amount: String, rate: Long): Problem? {
        until("no look at the node in progress") { !model.state.busy }
        model.dropDraft()
        model.prepare(to, amount, false, rate)
        until("the payment is worked out") { !model.state.sending }
        if (model.state.draft == null) return model.state.sendProblem
        model.confirmSend(owner)
        until("the payment is sent or refused") { !model.state.sending }
        return model.state.sendProblem
    }

    /** Makes a wallet the way its owner does: the app makes the words, shows them, and stores them when confirmed. */
    private fun made(model: WalletModel): List<String> {
        assertNull(model.state.wallet)
        model.startNewWallet()
        val words = model.state.newWords!!.words
        model.confirmNewWallet(owner)
        until("the new wallet is stored") { !model.state.walletBusy && model.state.wallet != null }
        assertEquals(Account.fromWords(words, Chain.REGTEST).watchKey().xpub, model.state.wallet!!.xpub)
        return words
    }

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)

    @Test
    fun aRemovedWalletWithCoinsOnItsChangeAddressIsEmptiedIntoTheNewWalletByItsWords() {
        AuditNode.startOrNull()!!.use { node ->
            val funderWords = Words.generate()
            val funder = Account.fromWords(funderWords, Chain.REGTEST)
            val miner = fresh().address(0, 0)
            val elsewhere = fresh().address(0, 0)               // where the old wallet pays a part of its coins
            node.mine(2, funder.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()

            // the old wallet is made in the app, and one payment arrives on its first address
            val model = opened()
            val oldWords = made(model)
            val old = Account.fromWords(oldWords, Chain.REGTEST)
            val received = 8_99999434L
            val funding = Engine(node.setup())
            val funderInfo = WalletInfo(Chain.REGTEST, funder.watchKey().xpub, lockOnOpen = false)
            funding.send(funderInfo.xpub, funding.plan(funderInfo, old.address(0, 0), received, Planner.DEFAULT_FEE_RATE), funderWords)
            node.mine(1, miner)
            refreshed(model)
            assertEquals(received, model.state.snapshot!!.walletSat)

            // its owner pays one coin on; what is left comes back to the wallet's first change address
            assertNull(pay(model, elsewhere, "1", Planner.DEFAULT_FEE_RATE))
            until("no look in progress") { !model.state.busy }
            node.mine(1, miner)
            refreshed(model)
            val left = model.state.snapshot!!.walletSat
            assertEquals(1_00000000L, node.held(elsewhere))
            assertEquals(0, node.held(old.address(0, 0)))
            assertEquals(left, node.held(old.address(1, 0)))
            println("OLD-WALLET: received $received, paid on 100000000, fee ${received - 1_00000000L - left}, left on its change address: $left")

            // the wallet is removed from the phone, and a new one is made
            model.removeWallet(owner)
            until("the old wallet is gone") { model.state.wallet == null && wallets.value == null && !vault().exists() }
            until("no look in progress") { !model.state.busy }
            val newWords = made(model)
            refreshed(model)
            assertEquals(0, model.state.snapshot!!.walletSat)
            val mine = model.state.wallet!!.let { it.watchKey().address(0, it.receiveIndex) }
            assertEquals(Account.fromWords(newWords, Chain.REGTEST).address(0, 0), mine)

            // the old words copied with one word wrong do not fit each other; the app says so and asks no node
            val slip = oldWords.toMutableList()
            val spare = Words.generate()
            val swapped = spare.firstOrNull { other ->
                slip[11] = other
                other != oldWords[11] && Words.fault(slip) == Words.Fault.Mismatch
            }
            assertNotNull(swapped, "no word of a second set breaks the checksum; that cannot be")
            assertFalse(model.paperLook(slip.joinToString(" ")))
            assertEquals(Problem(Problem.Kind.BAD_WORDS, "fit"), model.state.paperProblem)
            assertNull(model.state.paper)

            // other words that are correct in themselves belong to another wallet: another address, with nothing on it
            assertTrue(model.paperLook(spare.joinToString(" ")))
            until("the other words are looked at") { !model.state.paperBusy }
            assertNull(model.state.paperProblem)
            assertNotEquals(old.address(0, 0), model.state.paper!!.address)
            assertEquals(0, model.state.paper!!.spendableSat)
            model.paperDrop()

            // the right words: the old wallet's first address is shown, with what lies on its change address
            assertTrue(model.paperLook(oldWords.joinToString(" ")))
            until("the old wallet is looked at") { !model.state.paperBusy }
            assertNull(model.state.paperProblem)
            val found = model.state.paper!!
            assertEquals(old.address(0, 0), found.address)
            assertEquals(left, found.spendableSat)
            assertEquals(0, found.immatureSat)
            assertEquals(listOf(1 to 0), found.coins.map { it.branch to it.index })

            // everything into the new wallet, as the screen does it: the wallet's current address, nothing kept apart
            model.paperPrepare(mine, "", "", Planner.DEFAULT_FEE_RATE)
            assertNull(model.state.paperProblem)
            val plan = model.state.paperDraft!!.plan
            assertEquals(1, plan.payments.size)
            assertEquals(left - plan.feeSat, plan.payments[0].amountSat)
            assertEquals(0, plan.leftBehind)
            model.paperSend()
            until("the old wallet is emptied") { !model.state.paperBusy }
            assertNull(model.state.paperProblem)
            val txid = assertNotNull(model.state.paperTxid)
            assertEquals(listOf(txid), node.mempool())
            until("no look in progress") { !model.state.busy }
            node.mine(1, miner)
            refreshed(model)
            assertEquals(left - plan.feeSat, model.state.snapshot!!.walletSat)
            assertEquals(left - plan.feeSat, node.held(mine))
            assertEquals(0, node.held(old.address(1, 0)))
            println("OLD-WALLET: emptied with its words, fee ${plan.feeSat}, in the new wallet: ${model.state.snapshot!!.walletSat}")
        }
    }
}
