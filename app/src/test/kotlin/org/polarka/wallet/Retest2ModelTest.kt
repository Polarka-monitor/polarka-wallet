package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Qrh
import north.core.Words
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.math.BigDecimal
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Third look of the independent reviewer (0.4.1): what the fixes after the retest left open.
 * Every test describes what a user is entitled to expect; a failure is a finding (see RETEST2.md for which).
 *
 * Taken into the suite as the reviewer wrote it (SHA-256 of the reviewer's file: 05e285ca0231c02efaa0506c2f4051f9
 * f6a8b4a44ef835b12754ea631d54c568), with one change that is marked where it is made: the end of the third test.
 */
@RunWith(RobolectricTestRunner::class)
class Retest2ModelTest {
    /** A private regtest node like AuditNode, which can also be stopped and started again on the same data. */
    private class OwnNode(private val bin: File) : AutoCloseable {
        val password: String
        val port: Int = ServerSocket(0).use { it.localPort }
        private val dir: File = Files.createTempDirectory("north-audit-node-").toFile()
        private val args: List<String>
        private var process: Process

        init {
            val random = SecureRandom()
            password = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val hash = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt.encodeToByteArray(), "HmacSHA256")) }
                .doFinal(password.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            args = listOf(
                File(bin, "truenorthd").path, "-datadir=$dir", "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
                "-disablewallet=1", "-server=1", "-txindex=1", "-blockfilterindex=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1",
                "-rpcport=$port", "-rpcauth=phone:$salt\$$hash", "-rpcwhitelist=phone:$AUDIT_ALLOWED", "-rpcwhitelistdefault=0", "-printtoconsole=0",
            )
            process = start()
        }

        private fun start(vararg more: String): Process {
            val p = ProcessBuilder(args + more).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end && runCatching { cli("getblockcount") }.isFailure) Thread.sleep(300)
            return p
        }

        fun cli(vararg a: String): String {
            val p = ProcessBuilder(listOf(File(bin, "truenorth-cli").path, "-datadir=$dir", "-chain=regtest", "-rpcconnect=127.0.0.1", "-rpcport=$port") + a)
                .redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "truenorth-cli ${a.first()}: $out" }
            return out
        }

        /** Stops the node and starts it again on the same data, with further settings. */
        fun restart(vararg more: String) {
            cli("stop")
            if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
            process = start(*more)
        }

        fun mine(blocks: Int, address: String): String = cli("generatetoaddress", blocks.toString(), address)

        fun mempool(): List<String> = Json.parseToJsonElement(cli("getrawmempool")).jsonArray.map { it.jsonPrimitive.content }

        fun held(address: String): Long {
            val r = Json.parseToJsonElement(cli("scantxoutset", "start", "[\"addr($address)\"]")).jsonObject
            return BigDecimal(r["total_amount"]!!.jsonPrimitive.content).movePointRight(8).longValueExact()
        }

        fun setup(): Setup = Setup("http://127.0.0.1:$port/", "phone", password, Chain.REGTEST, emptyList())

        override fun close() {
            runCatching { cli("stop") }
            if (!process.waitFor(40, TimeUnit.SECONDS)) process.destroyForcibly()
            dir.deleteRecursively()
        }
    }

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun vault() = WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1"))

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-retest2-model-").toFile()
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

    private fun bin(): File {
        val bin = System.getenv("TN_BIN")?.let { File(it) }
        if (bin == null || !File(bin, "truenorthd").canExecute()) {
            throw org.junit.AssumptionViolatedException("set TN_BIN to the folder with the official truenorthd and truenorth-cli")
        }
        return bin
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

    private fun opened(millis: Long = 120_000): WalletModel {
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        if (model.state.locked) {
            model.unlockApp(owner)
            until("the app is unlocked") { !model.state.locked }
        }
        until("the first look at the node", millis) { !model.state.busy && (model.state.snapshot != null || model.state.problem != null || model.state.setup == null) }
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

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)

    /**
     * Sound (expected to pass): the case I had begun to test in 0.4.1, now against the safeguard of 0.4.2. The node
     * took a payment and then lost its waiting transactions (stopped and started again with the setting that does
     * not keep them, as after a crash). Another node of the network would still have the payment. The app calls it
     * "did not go through" and keeps it; the owner pays again; then a peer hands the first payment in again.
     * The shop must be paid once.
     */
    @Test
    fun afterTheNodeLostAPaymentTheNextOneCannotArriveBesideIt() {
        OwnNode(bin()).use { node ->
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            node.mine(3, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            val model = opened()
            assertNull(pay(model, shop, "100", 2), "the first payment")
            until("the look after the payment") { !model.state.busy }
            val first = model.state.pending.single()
            check(node.mempool() == listOf(first.txid)) { "the payment is not waiting at the node as the test needs it" }
            val raw = node.cli("getrawtransaction", first.txid)        // what another node of the network keeps

            node.restart("-persistmempool=0")
            check(node.mempool().isEmpty()) { "the node still holds the payment after it was started again" }
            Thread.sleep(1500)                                          // its indexes catch up
            model.goneAfterMillis = 0                                   // stands for: the owner looks again more than a minute later
            refreshed(model)
            check(model.state.problem == null) { "the look after the restart failed: ${model.state.problem}" }
            val notice = model.state.notice
            val dropped = model.state.dropped.size
            val waiting = model.state.pending.size

            // told that it did not go through, the owner pays again
            val again = pay(model, shop, "100", 2)
            until("the look after the second payment") { !model.state.busy }
            val second = model.state.pending.singleOrNull()
            val shares = second != null && second.inputs.any { it in first.inputs }
            // a node that still had the first payment hands it in again; then a block is found
            val peer = runCatching { node.cli("sendrawtransaction", raw) }
            node.mine(1, miner)
            val shopGot = node.held(shop)
            refreshed(model)
            println("RETEST2 sound: the node came back without its waiting transactions: notice=$notice, kept as not gone through: $dropped, waiting: $waiting; " +
                "paying again: problem=$again, the new payment spends a coin of the first: $shares; the first one handed in again by a peer: " +
                (if (peer.isSuccess) "taken" else "refused") + "; after a block the shop holds ${Format.coins(shopGot)}; " +
                "the app then: notice=${model.state.notice}, kept: ${model.state.dropped.size}, waiting: ${model.state.pending.size}")
            assertEquals(Notice.PAYMENT_GONE, notice)
            assertEquals(1, dropped)
            assertNull(again, "the second payment")
            assertEquals(100_00000000L, shopGot, "one payment of 100 NORTH was wanted; the shop received")
        }
    }

    /**
     * R-03, what is left of it (fails by design). A public node needs no work to show a block. One that pays the
     * twentieth address it was told moves the wallet's receiving address to number 21, and nothing on the screen
     * says so. A wallet brought back from its words looks at the first twenty addresses only: coins received on
     * the address the app showed are not found then, until the owner asks it to look at twice as many.
     */
    @Test
    fun coinsOnTheAddressTheAppShowsAreFoundWhenTheWalletIsBroughtBack() {
        val bin = bin()
        val account = Account.fromWords(Words.generate(), Chain.REGTEST)
        val twentieth = Format.hex(Qrh.scriptForAddress(Chain.REGTEST, account.address(0, 19)))
        val told = ConcurrentHashMap.newKeySet<String>()
        val hash = "ab".repeat(32)
        var shownIndex = -1
        var card = false
        AuditFakeNode { method, params ->
            when (method) {
                "getblockchaininfo" -> "{\"chain\":\"regtest\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false,\"bestblockhash\":\"$hash\"}"
                "scanblocks" -> {
                    params[1].jsonArray.forEach { told.add(it.jsonPrimitive.content) }
                    "{\"from_height\":0,\"to_height\":1,\"relevant_blocks\":[\"$hash\"],\"completed\":true}"
                }
                "scantxoutset" -> {
                    params[1].jsonArray.forEach { told.add(it.jsonPrimitive.content) }
                    "{\"success\":true,\"height\":1,\"unspents\":[]}"
                }
                // one made-up block that pays a little to the twentieth receiving address
                "getblock" -> "{\"hash\":\"$hash\",\"height\":1,\"time\":1700000000,\"tx\":[{\"txid\":\"${"cd".repeat(32)}\",\"vin\":[{\"coinbase\":\"00\"}],\"vout\":[" +
                    "{\"n\":0,\"value\":0.00001000,\"scriptPubKey\":{\"hex\":\"$twentieth\"}}]}]}"
                "getnetworkhashps" -> "1"
                else -> "null"
            }
        }.use { fake ->
            setups.value = Setup("http://127.0.0.1:${fake.port}/", "", "", Chain.REGTEST, emptyList())      // a public node: no account
            wallets.value = WalletInfo(Chain.REGTEST, account.watchKey().xpub, lockOnOpen = false)
            val model = opened(300_000)
            until("the key numbers are written down") { wallets.value?.receiveIndex == model.state.wallet?.receiveIndex }
            shownIndex = model.state.wallet!!.receiveIndex
            card = model.state.snapshot?.wantsFurther ?: false
            check(model.state.problem == null) { "the look at the public node failed: ${model.state.problem}" }
        }
        OwnNode(bin).use { node ->
            // an honest node; somebody pays the address the app now shows for receiving
            val shown = account.address(0, shownIndex)
            node.mine(1, shown)
            val paid = node.held(shown)
            // the wallet is brought back from its words on another phone: a fresh wallet file, the same account key
            val found = Engine(node.setup()).fetch(WalletInfo(Chain.REGTEST, account.watchKey().xpub)).walletSat
            println("RETEST2 R-03: after one look at a public node that shows one made-up block: addresses named to it ${told.size}, the receiving address is number ${shownIndex + 1}, " +
                "card on the overview: $card; paid to that address at an honest node: ${Format.coins(paid)}; found by the wallet brought back from its words: ${Format.coins(found)}")
            assertEquals(paid, found, "coins paid to the address the app showed (number ${shownIndex + 1}) and found by a wallet brought back from its words")
        }
    }

    /**
     * R2-02 (fails by design). A payment that did not go through is kept until a block spends one of its coins. If
     * its coin no longer exists (a reorganization removed, for good, the transaction that had created it), no block
     * ever will. The app then refuses every payment and says "wait for the next block"; the next block changes
     * nothing. The wallet's file is written here as it stands after such a reorganization: one kept payment whose
     * coin the chain does not have.
     *
     * Changed by the author after the third look, from the println on. The reviewer's test ended with
     * assertNull(second, ...): the payment was to go through after the next block. The app does not let such
     * a payment go by itself, because a coin that a node does not show is no proof that the payment cannot
     * arrive (the node may lag, or the coin may lie on an address the app does not look at). What was put right
     * is what the owner is told: the message no longer says that a block will change it, the entry on the
     * Overview says what can be done, and "Forget this payment" is the way out. The end of the test follows that.
     */
    @Test
    fun aKeptPaymentWhoseCoinIsGoneDoesNotStopTheWalletAfterTheNextBlock() {
        OwnNode(bin()).use { node ->
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            node.mine(2, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            wallets.value = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false).copy(
                dropped = listOf(Dropped("ee".repeat(32), 100_00000000L, 1000L, setOf("ff".repeat(32) + ":0"), atMillis = 1)),
            )
            vault().let { it.store(it.encryptor(), words) }
            val model = opened()
            val canSpend = model.state.snapshot?.spendableSat
            val first = pay(model, shop, "100", 2)
            until("no look in progress") { !model.state.busy }
            node.mine(1, miner)                                         // the next block
            refreshed(model)
            val second = pay(model, shop, "100", 2)
            until("no look in progress") { !model.state.busy }
            println("RETEST2 R2-02: a kept payment whose coin the chain does not have: can be spent ${canSpend?.let { Format.coins(it) }}; paying: ${first?.kind}; " +
                "after the next block and a refresh, paying: ${second?.kind}; kept payments: ${model.state.dropped.size}")
            assertEquals(Problem.Kind.EARLIER_PAYMENT, second?.kind, "nothing is paid beside the kept payment until its owner lets it go")
            val said = app.getString(R.string.problem_earlier_payment)
            assertTrue("wait for the next block" !in said && "look at that payment on the Overview page" in said, said)
            assertTrue("Forget this payment only if you are sure" in app.getString(R.string.dropped_no_coin))
            model.forgetDropped("ee".repeat(32), owner)
            until("the owner has let the kept payment go") { model.state.dropped.isEmpty() }
            assertNull(pay(model, shop, "100", 2), "after the owner let the kept payment go")
        }
    }
}
