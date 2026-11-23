package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import north.core.Account
import north.core.Chain
import north.core.Unspent
import north.core.Words
import north.node.Balance
import north.node.ChainInfo
import north.node.Movement
import north.node.NodeClient
import north.node.NodeError
import north.node.Utxo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ninth look: an outside review of 0.7.1 by people, findings M-1, M-2, M-3 (the amount typed in), L-1 (new
 * words) and L-3. The stores, the node and the owner are the helpers of AuditSupport.kt.
 */
@RunWith(RobolectricTestRunner::class)
class NinthLookTest {
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val id = "ab".repeat(32)

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    /** An owner who is slow to unlock: the unlocking waits for [gate]. */
    private class SlowOwner : Owner {
        val gate = CompletableDeferred<Unit>()
        @Volatile var unlocks = 0
        @Volatile var shows = 0
        override suspend fun unlock(cipher: Cipher): Cipher {
            unlocks++
            gate.await()
            return cipher
        }
        override suspend fun present(): Boolean {
            shows++
            return true
        }
    }

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("north-ninth-look-").toFile()
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
        until("the first look at the node") { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
        return model
    }

    private val info = ChainInfo("main", 500, 500, false, "cd".repeat(32))

    private fun coin(sat: Long, n: Long = 0, height: Int = 10, coinbase: Boolean = false) = Unspent(id, n, sat, 0, 0, height, coinbase)

    private fun snapshot(coins: List<Unspent> = emptyList(), watched: Balance? = null, moves: List<Movement> = emptyList(), arriving: Long = 0) =
        Snapshot(info, 500, coins, watched, moves, emptyList(), null, null, 1L, arrivingSat = arriving)

    /** M-1: sums that cannot exist stop the look where it is made, as a node's error; they never reach a screen. */
    @Test
    fun sumsThatCannotExistStopTheLook() {
        val max = NodeClient.MAX_SAT
        // each coin can exist, together they cannot
        assertFailsWith<NodeError> { snapshot(listOf(coin(max, 0), coin(1, 1))) }
        // the wallet's coins and the watched addresses' coins each fit, their sum does not (the review's case)
        val watched = Balance(500, listOf(Utxo(id, 2, max, 10, false, "51")))
        assertFailsWith<NodeError> { snapshot(listOf(coin(1)), watched) }
        // rewards in the history whose sum would wrap around a Long (the mining page)
        val rewards = (0 until 47).map { Movement(id, 10 + it, 1_700_000_000L, true, max, 0, null) }
        assertFailsWith<NodeError> { snapshot(moves = rewards) }
        assertFailsWith<NodeError> { snapshot(arriving = -1) }
        // a paper address
        assertFailsWith<NodeError> { PaperFound("north1x", 500, listOf(coin(max, 0), coin(max, 1))) }

        // what can exist is summed once, and the parts fit
        val fine = snapshot(listOf(coin(5_00000000, 0), coin(7_00000000, 1, height = 450, coinbase = true)), watched = Balance(500, listOf(Utxo(id, 3, 3_00000000, 10, false, "51"))),
            moves = listOf(Movement(id, 450, 1_700_000_000L, true, 7_00000000, 0, null), Movement(id, 20, 1_700_000_000L, false, 5_00000000, 0, null)))
        assertEquals(15_00000000L, fine.totalSat)
        assertEquals(5_00000000L, fine.spendableSat)
        assertEquals(7_00000000L, fine.immatureSat)
        assertEquals(7_00000000L, fine.rewardsSat)
        assertEquals("15.00000000", Format.coins(fine.totalSat))
    }

    /** M-3: an amount above all coins that can exist is no amount. */
    @Test
    fun anAmountAboveAllCoinsIsNoAmount() {
        assertEquals(NodeClient.MAX_SAT, Format.parseCoins("2000000000"))
        assertNull(Format.parseCoins("2000000000.00000001"))
        assertNull(Format.parseCoins("92233720368.54775807"))
        assertNull(Format.parseCoins("99999999999.99999999"))
        assertEquals(1L, Format.parseCoins("0.00000001"))
    }

    /** L-3: a setup for another network than the wallet's is not taken, neither when offered nor when saved. */
    @Test
    fun aSetupForAnotherNetworkIsNotTaken() {
        wallets.value = WalletInfo(Chain.MAIN, Account.fromWords(Words.generate(), Chain.MAIN).watchKey().xpub, lockOnOpen = false)
        val model = opened()
        val regtest = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
        val main = Account.fromWords(Words.generate(), Chain.MAIN).address(0, 0)
        val node = """"node":{"url":"https://node.example/","user":"phone","password":"Pass-1"}"""
        assertTrue(!model.offer("""{"format":"north-watch-setup/1",$node,"chain":"regtest","addresses":["$regtest"]}"""))
        assertNull(model.state.offered)
        assertEquals(SetupError.Reason.OTHER_NETWORK, model.state.offerError)
        assertEquals("main", model.state.offerDetail)
        assertNull(setups.value, "nothing is stored")
        // the wallet's own network is taken
        assertTrue(model.offer("""{"format":"north-watch-setup/1",$node,"chain":"main","addresses":["$main"]}"""))
        assertNotNull(model.state.offered)
        assertNull(model.state.offerError)
    }

    /** L-1: new words that wait to be written down are dropped after ten minutes away, not after less. */
    @Test
    fun newWordsDoNotWaitForEver() {
        val model = opened()
        model.startNewWallet()
        assertNotNull(model.state.newWords)
        model.left(1_000_000)
        model.returned(1_000_000 + WalletModel.NEW_WORDS_AFTER_MILLIS)
        assertNotNull(model.state.newWords, "ten minutes and not a moment more: the words stay")
        model.left(2_000_000)
        model.returned(2_000_000 + WalletModel.NEW_WORDS_AFTER_MILLIS + 1)
        assertNull(model.state.newWords)
        assertEquals(Notice.NEW_WORDS_DROPPED, model.state.notice)
        assertNull(wallets.value, "nothing was stored")
        assertTrue(!vault().exists())
    }

    /** L-3 and M-2: a second tap does not ask the owner twice, and nothing changes the wallet or the setup meanwhile. */
    @Test
    fun whileTheWordsAreUnlockedNothingElseChangesTheWallet() {
        val words = Words.generate()
        vault().let { it.store(it.encryptor(), words) }
        val model = opened()
        assertTrue(model.state.wordsWithoutWallet)
        val slow = SlowOwner()
        model.rebuildWallet(slow)
        model.rebuildWallet(slow)
        until("the owner is asked") { slow.unlocks >= 1 }
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, slow.unlocks, "one tap, one question to the owner")
        assertTrue(model.state.walletBusy)

        model.offerServer("https://server.example/")
        model.acceptOffer(false)
        assertEquals(Notice.WAIT_FOR_PAYMENT, model.state.notice)
        assertNull(setups.value, "the setup is not changed meanwhile")
        model.note(null)
        model.forgetSetup(owner)
        assertEquals(Notice.WAIT_FOR_PAYMENT, model.state.notice)
        assertEquals(0, owner.shows)

        slow.gate.complete(Unit)
        until("the wallet is built again") { model.state.wallet != null && !model.state.walletBusy }
        assertEquals(Account.fromWords(words, Chain.MAIN).watchKey().xpub, model.state.wallet!!.xpub)
    }

    /** M-2: the wallet is not removed while a payment is being signed and sent; it would come back to life in its file. */
    @Test
    fun theWalletIsNotRemovedWhileAPaymentIsSigned() {
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
                until("the payment is worked out") { !model.state.sending && model.state.draft != null }
                val slow = SlowOwner()
                model.confirmSend(slow)
                until("the owner is asked") { slow.unlocks == 1 }

                // meanwhile the owner goes to the More page and removes the wallet: refused, and not even asked
                model.note(null)
                model.removeWallet(owner)
                assertEquals(Notice.WAIT_FOR_PAYMENT, model.state.notice)
                assertEquals(0, owner.shows)
                assertNotNull(model.state.wallet)
                assertTrue(vault().exists())

                slow.gate.complete(Unit)
                settled(model)
                assertNull(model.state.sendProblem)
                assertEquals(1, model.state.pending.size)
                assertEquals(1, wallets.value!!.pending.size)
                assertEquals(1, relay.count("sendrawtransaction"))

                // once the payment is over the wallet can be removed, and nothing writes it back
                model.removeWallet(owner)
                until("the wallet is removed") { model.state.wallet == null }
                settled(model)
                Thread.sleep(200)
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(wallets.value)
                assertTrue(!vault().exists())
            }
        }
    }
}
