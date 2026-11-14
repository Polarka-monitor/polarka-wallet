package org.polarka.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Qrh
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screens that came with the fixes after the reviewer's retest of 0.4.0: the real activity under
 * Robolectric, as in ScreenTest and AuditScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h4000dp")
class AfterRetestScreenTest {
    @get:Rule
    val screen = createEmptyComposeRule()

    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val mainAddress = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-after-retest-screen-").toFile()
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
    private fun waitFor(tag: String, millis: Long = 30_000) = screen.waitUntil(millis) { shown(tag) }
    private fun textOf(tag: String): String =
        screen.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /**
     * R-06. A setup with a user name and a password makes the app treat the node as its owner's own, and such
     * a node is given the key that shows the whole wallet. "Check before saving" says so; for a public node it
     * says what that one sees.
     */
    @Test
    fun checkBeforeSavingSaysWhatTheNodeWillBeGiven() {
        val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
        waitFor("server-go")
        model.offer("""{"format":"north-watch-setup/1","node":{"url":"https://node.example/","user":"phone","password":"Pass-1"},"chain":"main","addresses":["$mainAddress"]}""")
        waitFor("offer-sees")
        val own = textOf("offer-sees")
        assertTrue("the key that shows every address of the wallet" in own && "node of your own" in own, own)
        model.dropOffer()
        model.offerPublic("https://node.example/")
        screen.waitUntil(10_000) { shown("offer-sees") && "A public node sees your addresses" in textOf("offer-sees") }
        // 0.6: and for a wallet server it says what a server sees
        model.dropOffer()
        model.offerServer("https://server.example/")
        screen.waitUntil(10_000) { shown("offer-sees") && "The server sees all the addresses of your wallet together" in textOf("offer-sees") }
    }

    /**
     * R-03 on the screen. A public node that shows every address it is told as used gets no further than the
     * first forty of each branch. The overview then asks the owner, and only the owner's tap lets the app look
     * at more, and then only at twice as many.
     */
    @Test
    fun theOwnerIsAskedBeforeTheAppLooksFurther() {
        val account = Account.fromWords(Words.generate(), Chain.REGTEST)
        val told = ConcurrentHashMap<String, String>()          // every address the node was told, with its script
        fun note(list: JsonElement) = list.jsonArray.forEach {
            val address = it.jsonPrimitive.content.removePrefix("addr(").removeSuffix(")")
            told.computeIfAbsent(address) { a -> Format.hex(Qrh.scriptForAddress(Chain.REGTEST, a)) }
        }
        val hash = "ab".repeat(32)
        AuditFakeNode { method, params ->
            when (method) {
                "getblockchaininfo" -> "{\"chain\":\"regtest\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false,\"bestblockhash\":\"$hash\"}"
                "scanblocks" -> {
                    note(params[1])
                    "{\"from_height\":0,\"to_height\":1,\"relevant_blocks\":[\"$hash\"],\"completed\":true}"
                }
                "scantxoutset" -> {
                    note(params[1])
                    "{\"success\":true,\"height\":1,\"unspents\":[]}"
                }
                // one block that pays a little to every address the node has been told so far
                "getblock" -> "{\"hash\":\"$hash\",\"height\":1,\"time\":1700000000,\"tx\":[{\"txid\":\"${"cd".repeat(32)}\",\"vin\":[{\"coinbase\":\"00\"}],\"vout\":[" +
                    told.values.toList().mapIndexed { i, script -> "{\"n\":$i,\"value\":0.00001000,\"scriptPubKey\":{\"hex\":\"$script\"}}" }.joinToString(",") + "]}]}"
                "getnetworkhashps" -> "1"
                else -> "null"
            }
        }.use { fake ->
            setups.value = Setup("http://127.0.0.1:${fake.port}/", "", "", Chain.REGTEST, emptyList())      // a public node: no account
            wallets.value = WalletInfo(Chain.REGTEST, account.watchKey().xpub, lockOnOpen = false)
            val model = Robolectric.buildActivity(MainActivity::class.java).setup().get().model
            waitFor("look-further", 120_000)
            screen.waitUntil(60_000) { shown("look-further") && !model.state.busy }
            assertEquals(Engine.AUTO_PUBLIC - 1, model.state.wallet!!.receiveIndex)
            assertEquals(2 * Engine.AUTO_PUBLIC, told.size, "addresses named to the node before the owner was asked")
            assertTrue("address number ${Engine.AUTO_PUBLIC} " in textOf("further-text"), textOf("further-text"))

            screen.onNodeWithTag("look-further").performClick()
            screen.waitUntil(120_000) { shown("look-further") && !model.state.busy && model.state.snapshot?.lookedTo == 2 * Engine.AUTO_PUBLIC }
            assertEquals(2 * Engine.AUTO_PUBLIC, wallets.value!!.lookTo, "the owner's yes is kept in the wallet's file")
            assertEquals(4 * Engine.AUTO_PUBLIC, told.size, "one yes lets the app look at twice as many addresses, not at thousands")
            assertEquals(2 * Engine.AUTO_PUBLIC - 1, model.state.wallet!!.receiveIndex)
        }
    }
}
