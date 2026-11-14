package io.github.severka_monitor.northwatch

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import north.core.Account
import north.core.Chain
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent audit of 0.3.1: the app's own model (WalletModel), driven the way the screens drive it, against a
 * private TrueNorth Core on regtest. The Keystore and the fingerprint dialog are replaced as in ScreenTest.
 * Every test describes what a user is entitled to expect; a failure is a finding (see AUDIT.md for which).
 */
@RunWith(RobolectricTestRunner::class)
class AuditModelTest {
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val coin = 512_00000000L

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("north-audit-model-").toFile()
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

    private fun until(what: String, millis: Long = 90_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            Thread.sleep(15)
        }
    }

    /** The app is opened: a new model on the stores as they are, unlocked by the owner, with its first look at the node done. */
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

    /** What the Send tab does: check the payment, then confirm it. Returns the problem the screen would show, or null. */
    private fun pay(model: WalletModel, to: String, amount: String, rate: Long): Problem? {
        until("no look at the node in progress") { !model.state.busy }     // the node runs one scan at a time (see note N-10)
        model.dropDraft()
        model.prepare(to, amount, false, rate)
        until("the payment is worked out") { !model.state.sending }
        if (model.state.draft == null) return model.state.sendProblem
        model.confirmSend(owner)
        until("the payment is sent or refused") { !model.state.sending }
        return model.state.sendProblem
    }

    private fun fresh(chain: Chain = Chain.REGTEST) = Account.fromWords(Words.generate(), chain)

    /**
     * F-01. The list of payments that wait for a block lives only in memory. After the app has been closed and
     * opened again it is empty, the node still reports the coins as unspent, and the next payment is built on
     * them. The node takes it as a replacement of the earlier one (higher fee), which then never arrives.
     */
    @Test
    fun aPaymentMadeAfterTheAppWasClosedDoesNotCancelTheOneBefore() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val shop = fresh().address(0, 0)
            val friend = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            node.mine(1, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }

            val first = opened()
            assertNull(pay(first, shop, "100", 1), "the first payment")
            val firstId = first.state.sentTxid
            check(node.mempool() == listOf(firstId) && first.state.pending.size == 1) { "the first payment is not where it should be" }

            // the app is closed (swiped away, or stopped by the phone to free memory) and opened again before the next block
            until("the first run of the app is idle") { !first.state.busy }
            val second = opened()
            val remembered = second.state.pending.size
            val problem = pay(second, friend, "50", 5)
            val secondId = second.state.sentTxid
            val waiting = node.mempool()
            node.mine(1, miner)
            val shopGot = node.held(shop)
            val friendGot = node.held(friend)
            println("AUDIT F-01: after reopening the app remembers $remembered waiting payment(s); second payment: problem=$problem id=$secondId; " +
                "first id=$firstId; mempool after the second: $waiting; after one block the shop holds ${Format.coins(shopGot)}, the friend ${Format.coins(friendGot)}")
            assertEquals(100_00000000L, shopGot, "the app showed 'Sent' for 100 NORTH to the shop; a later payment was built on the same coin and replaced it, so the shop got")
        }
    }

    /** Sound, the other side of F-01: while the app keeps running, the coins of a waiting payment are not used again. */
    @Test
    fun inOneRunOfTheAppAWaitingPaymentIsLeftAlone() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val shop = fresh().address(0, 0)
            val friend = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            node.mine(1, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            val model = opened()
            assertNull(pay(model, shop, "100", 1), "the first payment")
            val firstId = model.state.sentTxid
            val problem = pay(model, friend, "50", 5)
            assertEquals(Problem.Kind.NOTHING_TO_SPEND, problem?.kind, "the only coin is in a waiting payment")
            assertEquals(listOf(firstId), node.mempool())
            node.mine(1, miner)
            assertEquals(100_00000000L, node.held(shop))
            refreshed(model)
            assertEquals(0, model.state.pending.size)
            assertNull(pay(model, friend, "50", 5), "after the block the change can be spent")
        }
    }

    /**
     * F-02. The node accepts the transaction, but its answer is lost (a mobile connection that drops). The app
     * reports a failure, remembers nothing, and the retry it invites pays a second time.
     */
    @Test
    fun aLostAnswerDoesNotEndInPayingTwice() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            AuditRelay(node.port).use { relay ->
                val words = Words.generate()
                val wallet = Account.fromWords(words, Chain.REGTEST)
                val shop = fresh().address(0, 0)
                val miner = fresh().address(0, 0)
                node.mine(3, wallet.address(0, 0))
                node.mine(100, miner)
                setups.value = node.setup(port = relay.port)
                wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                vault().let { it.store(it.encryptor(), words) }
                val model = opened()

                relay.loseAnswerOf = { it == "sendrawtransaction" }
                val told = pay(model, shop, "100", 2)
                relay.loseAnswerOf = { false }
                val inMempool = node.mempool().size
                val remembered = model.state.pending.size
                // the confirmation is still on the screen; tapping "Sign and send" again
                model.confirmSend(owner)
                until("the second tap is answered") { !model.state.sending }
                val toldAgain = model.state.sendProblem
                node.mine(1, miner)
                refreshed(model)
                // Told twice that nothing went out, the user makes the payment anew.
                // Adapted after the fix: only a user who was told so pays again. The fixed app finds out that the
                // payment did go out and says "Sent"; nobody pays a second time after that.
                val third = if (told != null || toldAgain != null) pay(model, shop, "100", 2) else null
                node.mine(1, miner)
                val shopGot = node.held(shop)
                println("AUDIT F-02: answer lost: the app says $told, remembers $remembered waiting payment(s), the node's mempool holds $inMempool; " +
                    "second tap: $toldAgain; new payment after a block: problem=$third; the shop now holds ${Format.coins(shopGot)}")
                assertEquals(100_00000000L, shopGot, "one payment of 100 NORTH was wanted; the shop received")
                assertEquals(1, inMempool, "the payment must have reached the node")
                assertEquals(1, remembered, "a payment whose answer was lost must be remembered as waiting")
            }
        }
    }

    /**
     * F-03. A wallet that has been used keeps its money on the newest change key. Bringing the wallet back from
     * its words starts at key 0 with a window of 20 and never looks further.
     */
    @Test
    fun aWalletBroughtBackFromItsWordsShowsAllItsCoins() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            node.mine(1, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            val model = opened()
            repeat(21) { n ->
                assertNull(pay(model, shop, "1", 1), "payment ${n + 1}")
                node.mine(1, miner)
                refreshed(model)
            }
            val before = model.state.snapshot!!.walletSat
            val changeIndex = model.state.wallet!!.changeIndex
            check(before > 480 * 100_000_000L && changeIndex == 21) { "the wallet is not in the state the test needs: $before, $changeIndex" }

            // the phone is lost. On a new phone the owner types the words into "Bring back a wallet".
            wallets.value = null
            File(dir, "words.bin").delete()
            val newPhone = opened()
            newPhone.restoreWallet(words.joinToString(" "), owner)
            until("the wallet is stored") { newPhone.state.wallet != null || newPhone.state.walletProblem != null }
            refreshed(newPhone)
            val after = newPhone.state.snapshot?.walletSat
            println("AUDIT F-03: before the loss the wallet showed ${Format.coins(before)} (change key number $changeIndex in use); " +
                "brought back from its words it shows ${after?.let { Format.coins(it) }} with window ${newPhone.state.wallet?.window}, problem=${newPhone.state.problem}")
            assertEquals(before, after, "the wallet brought back from its words does not show its coins")
        }
    }

    /**
     * F-07. One file that cannot be read at start hides the wallet. The app then offers "Make a new wallet",
     * and making one writes new words over the stored ones without a question.
     */
    @Test
    fun aFileThatCannotBeReadOnceDoesNotCostTheStoredWords() {
        val words = Words.generate()
        val setup = Setup("https://127.0.0.1:1/", "phone", "Pass-1", Chain.MAIN, listOf("north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"))
        setups.value = setup
        wallets.value = WalletInfo(Chain.MAIN, Account.fromWords(words, Chain.MAIN).watchKey().xpub)
        vault().let { it.store(it.encryptor(), words) }
        wallets.failLoads = 1                       // wallet.bin cannot be read this one time

        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        val shown = "problem=${model.state.problem}, setup=${model.state.setup?.host}, wallet=${model.state.wallet != null}, locked=${model.state.locked}"
        // the screen says: "The stored setup cannot be read (...). Import the setup file again." The user does.
        model.offer(setup.toJson())
        model.acceptOffer(false)
        until("the setup is stored again") { model.state.setup != null }
        // the overview now says "No wallet in this phone yet" and offers "Make a new wallet"
        model.startNewWallet()
        val offered = model.state.newWords != null
        if (offered) {
            model.confirmNewWallet(owner)
            until("the new wallet is stored") { model.state.wallet != null || model.state.walletProblem != null }
        }
        val kept = vault().let { it.open(it.decryptor()) }
        println("AUDIT F-07: after one unreadable file the app shows: $shown; a new wallet is offered: $offered; the stored words are still the old ones: ${kept == words}")
        assertTrue(kept == words, "the recovery words that were stored in the phone were overwritten by a new wallet without any warning")
    }
}
