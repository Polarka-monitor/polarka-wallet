package org.polarka.wallet

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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What 0.4 added to the app's model beyond what the auditor's own tests cover. The stores, the node and the
 * owner are the helpers the auditor wrote (AuditSupport.kt).
 */
@RunWith(RobolectricTestRunner::class)
class FixesModelTest {
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val nowhere = Setup("https://127.0.0.1:1/", "phone", "Pass-1", Chain.MAIN, listOf("north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"))

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("north-fixes-model-").toFile()
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

    private fun settled(model: WalletModel) {
        repeat(3) {
            until("nothing in progress") { !model.state.busy && !model.state.sending }
            Thread.sleep(40)
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

    /** A payment that the node let go of (or never took) is reported, and nothing stays written down about it. */
    @Test
    fun aPaymentTheNodeNoLongerHoldsIsReported() {
        val node = AuditNode.startOrNull() ?: return
        node.use {
            AuditRelay(node.port).use { relay ->
                val words = Words.generate()
                val wallet = Account.fromWords(words, Chain.REGTEST)
                val shop = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
                val miner = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
                node.mine(1, wallet.address(0, 0))
                node.mine(100, miner)
                setups.value = node.setup(port = relay.port)
                wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
                vault().let { it.store(it.encryptor(), words) }
                val model = opened()
                model.prepare(shop, "100", false, 2)
                until("the payment is worked out") { !model.state.sending }
                model.confirmSend(owner)
                settled(model)
                assertNull(model.state.sendProblem)
                assertEquals(1, model.state.pending.size)
                assertFalse(model.state.pending[0].unsure, "the node holds the payment, so it is not in doubt")
                assertEquals(1, wallets.value!!.pending.size, "the waiting payment must be in the wallet's file")
                assertEquals(0L, model.state.snapshot!!.spendableSat, "the coin of a waiting payment is not offered")
                assertEquals(512_00000000L, model.state.snapshot!!.waitingSat)

                // the node drops the payment from its waiting list (here: it says so; a real node does after two weeks, or when it is short of memory)
                relay.rewrite = { method, answer ->
                    if (method == "getmempoolentry") "{\"result\":null,\"error\":{\"code\":-5,\"message\":\"Transaction not in mempool\"},\"id\":\"north\"}" else answer
                }
                model.refresh()
                settled(model)
                assertEquals(0, model.state.pending.size)
                assertEquals(Notice.PAYMENT_GONE, model.state.notice)
                assertEquals("100.00000000", model.state.noticeDetail)
                until("the wallet's file is written") { wallets.value!!.pending.isEmpty() }
            }
        }
    }

    /** Stored recovery words are never written over; the wallet whose file was lost is built again from them. */
    @Test
    fun storedWordsRebuildTheWalletAndAreNeverWrittenOver() {
        val words = Words.generate()
        setups.value = nowhere
        vault().let { it.store(it.encryptor(), words) }
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        assertTrue(model.state.wordsWithoutWallet && model.state.wallet == null)

        model.restoreWallet(Words.generate().joinToString(" "), owner)
        until("the attempt is answered") { model.state.walletProblem != null }
        assertEquals(Problem.Kind.WORDS_EXIST, model.state.walletProblem?.kind)
        model.startNewWallet()
        assertNull(model.state.newWords)
        assertEquals(words, vault().let { it.open(it.decryptor()) })

        model.rebuildWallet(owner)
        until("the wallet is built again") { model.state.wallet != null }
        assertEquals(Account.fromWords(words, Chain.MAIN).watchKey().xpub, model.state.wallet!!.xpub)
        assertFalse(model.state.wordsWithoutWallet)
        assertEquals(model.state.wallet, wallets.value)
    }

    /** Removing the wallet, removing the node setup and switching the lock off are for the owner only. */
    @Test
    fun onlyTheOwnerRemovesTheWalletOrSwitchesTheLockOff() {
        val words = Words.generate()
        setups.value = nowhere
        wallets.value = WalletInfo(Chain.MAIN, Account.fromWords(words, Chain.MAIN).watchKey().xpub, lockOnOpen = true)
        vault().let { it.store(it.encryptor(), words) }
        val model = opened()

        owner.there = false
        val asked = owner.shows
        model.removeWallet(owner)
        model.forgetSetup(owner)
        model.saveLock(false, owner)
        until("the three requests are answered") { owner.shows == asked + 3 }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(model.state.wallet?.lockOnOpen == true && model.state.setup != null && vault().exists(), "somebody who is not the owner changed the wallet")

        owner.there = true
        model.saveLock(false, owner)
        until("the lock is off") { model.state.wallet?.lockOnOpen == false }
        model.removeWallet(owner)
        until("the wallet is removed") { model.state.wallet == null }
        assertFalse(vault().exists())
    }

    /** "Next address" stops where a wallet brought back from its words would stop looking. */
    @Test
    fun addressesAreNotOpenedFurtherThanARestoredWalletLooks() {
        setups.value = nowhere
        wallets.value = WalletInfo(Chain.MAIN, Account.fromWords(Words.generate(), Chain.MAIN).watchKey().xpub, lockOnOpen = false)
        val model = opened()
        repeat(WalletInfo.GAP + 5) { model.nextAddress() }
        assertEquals(WalletInfo.GAP - 1, model.state.wallet!!.receiveIndex)
        assertEquals(Notice.ADDRESS_LIMIT, model.state.notice)
    }

    /** The app locks after a minute away, also when a rebuild of the screen falls into that minute; words of a paper address are dropped. */
    @Test
    fun aMinuteAwayLocksTheWallet() {
        setups.value = nowhere
        wallets.value = WalletInfo(Chain.MAIN, Account.fromWords(Words.generate(), Chain.MAIN).watchKey().xpub, lockOnOpen = true)
        val model = opened()
        model.left(1_000_000)
        model.returned(1_000_000 + WalletModel.LOCK_AFTER_MILLIS - 1)
        assertFalse(model.state.locked, "less than a minute away")
        model.left(2_000_000)
        model.returned(2_000_000 + WalletModel.LOCK_AFTER_MILLIS + 1)
        assertTrue(model.state.locked)
    }
}
