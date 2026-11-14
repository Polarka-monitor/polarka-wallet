package org.polarka.wallet

import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import north.core.Account
import north.core.Chain
import north.core.Words
import north.node.ServerClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.6. The app over a wallet server, from its model down: the model that the screens use, a stored setup
 * that names a server, and between the two only what the app itself builds ([Gateway.of]). The server is the
 * stand-in on a real TrueNorth Core on regtest, behind a relay that can answer in its place.
 * Skipped when TN_BIN does not name the node's programs or python3 is missing.
 */
@RunWith(RobolectricTestRunner::class)
class ServerModelTest {
    private val coin = 100_000_000L
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-server-model-").toFile()
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

    /** A node, the stand-in server on it and a relay in front of that. The app is given the relay's address. */
    private class Bench(val node: ServerNode, val server: StandIn) : AutoCloseable {
        val relay = ServerRelay(server.url)
        private val direct = ServerClient(server.url, maxWaitMs = 0)
        fun setup(chain: Chain = Chain.REGTEST, watched: List<String> = emptyList()) = Setup(relay.url, "", "", chain, watched, server = true)
        fun held(address: String): Long = direct.utxos(listOf(address)).utxos.sumOf { it.amountSat }
        override fun close() {
            relay.close()
            server.close()
            node.close()
        }
    }

    private fun bench(): Bench {
        val node = ServerNode.start()
        return try {
            Bench(node, StandIn.start(node))
        } catch (e: Throwable) {
            node.close()
            throw e
        }
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

    private fun settled(model: WalletModel) {
        repeat(3) {
            until("nothing in progress") { !model.state.busy && !model.state.sending }
            Thread.sleep(40)
        }
    }

    private fun opened(): WalletModel {
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        until("the first look") { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
        return model
    }

    private fun workedOut(model: WalletModel, to: String, amount: String): Draft? {
        until("no look in progress") { !model.state.busy }
        model.dropDraft()
        model.prepare(to, amount, false, 2)
        until("the payment is worked out") { !model.state.sending }
        return model.state.draft
    }

    private fun pay(model: WalletModel, to: String, amount: String): Problem? {
        if (workedOut(model, to, amount) == null) return model.state.sendProblem
        model.confirmSend(owner)
        until("the payment is sent or refused") { !model.state.sending }
        return model.state.sendProblem
    }

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)

    /** Puts a wallet with new words into the phone's stores, as if it had been made there. */
    private fun walletInThePhone(): Account {
        val words = Words.generate()
        val account = Account.fromWords(words, Chain.REGTEST)
        wallets.value = WalletInfo(Chain.REGTEST, account.watchKey().xpub, lockOnOpen = false)
        vault().let { it.store(it.encryptor(), words) }
        return account
    }

    @Test
    fun aWalletOverAServerFromItsFirstLookToAPaymentInABlock() {
        bench().use { b ->
            val wallet = walletInThePhone()
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            b.node.mine(2, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 102: both rewards can be spent
            setups.value = b.setup(watched = listOf(miner))
            assertTrue(Gateway.of(b.setup()) is ServerEngine)
            assertTrue(Gateway.of(Setup("https://node.example.org", "phone", "pw", Chain.REGTEST, emptyList())) is Engine)

            val model = opened()
            assertNull(model.state.problem)
            val first = assertNotNull(model.state.snapshot)
            val reward = first.coins[0].amountSat
            assertEquals(2 * reward, first.spendableSat)
            assertEquals(100 * reward, first.watchedSat, "the address that the setup watches")
            assertEquals(listOf(true, true), first.moves.map { it.coinbase })
            assertNull(first.hashps, "a wallet server does not tell the hash rate")
            assertTrue("/v1/utxos" in b.relay.paths, "the look did not go to the wallet server")

            // what the notifier asks: the same gateway, built from the same setup
            val (reached, arrived) = Gateway.of(b.setup()).incomingSince(wallets.value, 0)
            assertEquals(102, reached)
            assertEquals(listOf(1, 2), arrived.map { it.move.height })
            assertTrue(arrived.all { it.own && it.move.coinbase })

            assertNull(pay(model, shop, "100"))
            val waiting = model.state.pending.single()
            assertEquals(100 * coin, waiting.sentSat)
            assertEquals(listOf(waiting.txid), b.node.mempool())
            settled(model)
            val meanwhile = assertNotNull(model.state.snapshot)
            assertEquals(setOf(waiting.txid), meanwhile.held)
            assertEquals(reward, meanwhile.spendableSat, "the coin of the waiting payment is not offered again")
            assertEquals(reward - 100 * coin - waiting.feeSat, meanwhile.arrivingSat, "the change of the waiting payment is on its way")

            b.node.mine(1, miner)
            model.refresh()
            settled(model)
            assertTrue(model.state.pending.isEmpty(), "the block has settled the payment")
            val after = assertNotNull(model.state.snapshot)
            assertEquals(-(100 * coin + waiting.feeSat), after.moves.single { it.txid == waiting.txid }.netSat)
            assertEquals(0L, after.arrivingSat)
            assertEquals(2 * reward - 100 * coin - waiting.feeSat, after.spendableSat)
            assertEquals(100 * coin, b.held(shop))
            until("the wallet's file is written") { wallets.value!!.pending.isEmpty() && wallets.value!!.changeIndex == 1 }
        }
    }

    @Test
    fun aRefusalAndAHandOverWhoseOutcomeNobodyTells() {
        bench().use { b ->
            val wallet = walletInThePhone()
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(1, wallet.address(0, 1))
            b.node.mine(100, miner)                                             // tip 102
            setups.value = b.setup()
            val model = opened()
            assertNull(model.state.problem)

            // the node behind the server says no: the owner is told why, and nothing is kept as waiting
            b.relay.instead = { path, _ ->
                if (path == "/v1/transactions/validate") 200 to "{\"allowed\":false,\"rejection_reason\":\"min relay fee not met\"}" else null
            }
            val refusal = assertNotNull(pay(model, shop, "5"))
            assertEquals(Problem.Kind.REJECTED to "min relay fee not met", refusal.kind to refusal.detail)
            assertTrue(model.state.pending.isEmpty())
            assertTrue(b.node.mempool().isEmpty())
            assertTrue("/v1/transactions/broadcast" !in b.relay.paths, "a refused payment was handed over")
            until("the wallet's file is written") { wallets.value!!.pending.isEmpty() }
            settled(model)

            // the hand-over reaches nobody: the outcome is not known, and no other payment is made meanwhile
            b.relay.instead = { path, _ ->
                if (path == "/v1/transactions/broadcast") 502 to "{\"message\":\"The blockchain node is unavailable or returned an invalid response.\"}" else null
            }
            assertEquals(Problem.Kind.UNSURE, pay(model, shop, "5")?.kind)
            val unsure = model.state.pending.single()
            assertTrue(unsure.unsure)
            assertTrue(b.node.mempool().isEmpty(), "the node got the transaction")
            settled(model)
            assertEquals(Problem.Kind.OUTCOME_OPEN, pay(model, shop, "5")?.kind)

            // a minute later (the minute is switched off here) two looks in a row decide: it did not go through
            b.relay.instead = { _, _ -> null }
            model.goneAfterMillis = 0
            model.refresh()
            settled(model)
            assertEquals(Notice.PAYMENT_GONE, model.state.notice)
            assertTrue(model.state.pending.isEmpty())
            val lost = model.state.dropped.single()
            assertEquals(unsure.txid, lost.txid)

            // the next payment stands against it: it spends the coin of the one that did not go through
            val draft = assertNotNull(workedOut(model, shop, "5"), "problem: ${model.state.sendProblem}")
            assertEquals(1, draft.against)
            assertEquals(lost.inputs, draft.plan.inputs.map { it.txid + ":" + it.vout }.toSet())
            model.confirmSend(owner)
            settled(model)
            assertNull(model.state.sendProblem)
            assertEquals(1, b.node.mempool().size)
            b.node.mine(1, miner)
            model.refresh()
            settled(model)
            assertTrue(model.state.pending.isEmpty() && model.state.dropped.isEmpty(), "the block has settled both")
            assertEquals(5 * coin, b.held(shop), "the shop was paid once")
        }
    }

    @Test
    fun aServerOfAnotherNetworkAndOneThatIsNotThere() {
        bench().use { b ->
            // a server's address is typed in; it is checked like a setup file and nothing is stored yet
            val empty = opened()
            assertTrue(empty.offerServer(" api.example.org "))
            assertEquals(Setup("https://api.example.org", "", "", Chain.MAIN, emptyList(), server = true), empty.state.offered)
            assertFalse(empty.offerServer("http://api.example.org"))
            assertEquals(SetupError.Reason.NOT_HTTPS, empty.state.offerError)
            assertNull(setups.value)

            // saved, it is asked as a server; nobody is at this address
            val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            assertTrue(empty.offerServer("https://127.0.0.1:$nobody"))
            empty.acceptOffer(false)
            until("the look after saving") { !empty.state.busy && empty.state.problem != null }
            assertEquals(Problem.Kind.UNREACHABLE, empty.state.problem?.kind)
            assertEquals(Setup("https://127.0.0.1:$nobody", "", "", Chain.MAIN, emptyList(), server = true), setups.value)

            // the wallet is of the main network and the server works for regtest: it does not take the wallet's addresses
            b.node.mine(1, fresh().address(0, 0))
            val words = Words.generate()
            val account = Account.fromWords(words, Chain.MAIN)
            val info = WalletInfo(Chain.MAIN, account.watchKey().xpub, lockOnOpen = false)
            val other = Problem(Problem.Kind.OTHER_CHAIN, "address belongs to a different network")
            wallets.value = info
            setups.value = b.setup(Chain.MAIN)
            val model = opened()
            assertNull(model.state.snapshot)
            assertEquals(other, model.state.problem)
            // and the same is said wherever the wallet's addresses are named to it
            val gateway = Gateway.of(b.setup(Chain.MAIN))
            assertEquals(other, Problem.of(assertFailsWith<EngineError> { gateway.incomingSince(info, 0) }))
            assertEquals(other, Problem.of(assertFailsWith<EngineError> { gateway.plan(info, account.address(0, 1), coin, 2) }))
            assertEquals(other, Problem.of(assertFailsWith<EngineError> { gateway.paperLook(words) }))

            // nobody at the address
            wallets.value = null
            setups.value = Setup("http://127.0.0.1:$nobody", "", "", Chain.REGTEST, emptyList(), server = true)
            val alone = opened()
            assertNull(alone.state.snapshot)
            assertEquals(Problem.Kind.UNREACHABLE, alone.state.problem?.kind)
        }
    }

    @Test
    fun theNotifierAsksTheServer() {
        bench().use { b ->
            val wallet = walletInThePhone()
            val miner = fresh().address(0, 0)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(3, miner)                                               // tip 4
            setups.value = b.setup()
            val manager = app.getSystemService(NotificationManager::class.java)
            Notifier.enable(app, true)
            try {
                Notifier.check(app)                                             // the first look only remembers where the chain stands
                assertTrue(shadowOf(manager).allNotifications.isEmpty())
                b.node.mine(1, wallet.address(0, 1))                            // a reward for the wallet
                b.node.mine(1, miner)                                           // tip 6
                b.relay.paths.clear()
                Notifier.check(app)
                assertEquals(1, shadowOf(manager).allNotifications.size)
                assertTrue("/v1/transactions" in b.relay.paths, "the notifier did not ask the wallet server")
                Notifier.check(app)                                             // nothing new, and nothing is told twice
                assertEquals(1, shadowOf(manager).allNotifications.size)
            } finally {
                Notifier.enable(app, false)
            }
        }
    }
}
