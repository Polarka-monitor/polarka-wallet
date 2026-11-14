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

/**
 * Fourth look of the independent reviewer (ed47dde): what the changes after the third look opened.
 * Every test describes what a user is entitled to expect; a failure is a finding (see RETEST3.md for which).
 */
@RunWith(RobolectricTestRunner::class)
class Retest3ModelTest {
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
        dir = Files.createTempDirectory("polarka-retest3-model-").toFile()
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
     * R3-01 (fails by design). A payment whose outcome is not known is settled by a look once it is a minute old,
     * and since this version no other payment is made before that. Its age is taken from the phone's clock. If the
     * clock was ahead when the payment was written down and has been put right since, the payment is "younger than
     * a minute" until the clock catches up, and the wallet cannot pay for that long. The wallet's file is written
     * here as it stands then: a payment that never reached the node, written down two days ahead of now.
     */
    @Test
    fun anUnknownPaymentWrittenDownWithAClockThatWasAheadDoesNotStopTheWallet() {
        OwnNode(bin()).use { node ->
            val words = Words.generate()
            val wallet = Account.fromWords(words, Chain.REGTEST)
            val shop = fresh().address(0, 0)
            val miner = fresh().address(0, 0)
            node.mine(2, wallet.address(0, 0))
            node.mine(100, miner)
            setups.value = node.setup()
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            vault().let { it.store(it.encryptor(), words) }
            val engine = Engine(node.setup())
            val plan = engine.plan(info, shop, 100_00000000L, 2)
            val signed = engine.sign(info.xpub, plan, words)
            wallets.value = info.copy(changeIndex = 1, pending = listOf(
                Pending(signed.txid, plan.sentSat, plan.feeSat, plan.inputs.map { it.txid + ":" + it.vout }.toSet(), unsure = true,
                    atMillis = System.currentTimeMillis() + 2 * 24 * 3600_000L),
            ))
            val model = opened()
            refreshed(model)
            val first = pay(model, shop, "100", 2)
            until("no look in progress") { !model.state.busy }
            node.mine(1, miner)                                         // the next block
            refreshed(model)
            refreshed(model)
            val second = pay(model, shop, "100", 2)
            until("no look in progress") { !model.state.busy }
            println("RETEST3 R3-01: a payment that never reached the node, written down with a clock two days ahead: waiting ${model.state.pending.size} " +
                "(outcome not known: ${model.state.pending.count { it.unsure }}), kept ${model.state.dropped.size}; paying: ${first?.kind}; " +
                "after a block and two more looks, paying: ${second?.kind}")
            assertNull(second, "the wallet cannot pay: the payment whose outcome is not known is never settled while the clock is behind the time it was written down")
        }
    }
}
