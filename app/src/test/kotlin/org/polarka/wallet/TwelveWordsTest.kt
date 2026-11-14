package org.polarka.wallet

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import north.core.Chain
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
 * A wallet of twelve words, as the Northbound desktop wallet makes them, brought back in this app.
 *
 * Both wallets use the same key path (m/88h/1867h/0h, then the branch and the number of the key), so the same
 * words must give the same addresses in both. The words are the usual test phrase; the addresses are those
 * that Northbound's own tests expect for it (wallet/src/keys.rs at v0.1.0-rc.5). No node is needed.
 */
@RunWith(RobolectricTestRunner::class)
class TwelveWordsTest {
    private lateinit var dir: File
    private val setups = AuditSetups(null)
    private val wallets = AuditWallets()
    private val owner = AuditOwner()
    private val key = AuditKey()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun prepare() {
        dir = Files.createTempDirectory("polarka-twelve-words-").toFile()
        Stores.open = { setups }
        Stores.wallet = { wallets }
        Stores.words = { WordVault(SealedBytes(File(dir, "words.bin"), key, "north-wallet-words/1")) }
    }

    @After
    fun restore() {
        Stores.open = Stores.default
        Stores.wallet = Stores.defaultWallet
        Stores.words = Stores.defaultWords
        dir.deleteRecursively()
    }

    private fun until(what: String, millis: Long = 60_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            Thread.sleep(15)
        }
    }

    @Test
    fun twelveWordsOfAnotherWalletGiveTheSameAddressesHere() {
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val model = WalletModel(app)
        until("the stores are read") { model.state.loaded }
        assertNull(model.state.setup)                                   // no node: the wallet is for the main network
        assertTrue(model.restoreWallet(phrase, owner))
        until("the wallet is stored") { !model.state.walletBusy && (model.state.wallet != null || model.state.walletProblem != null) }
        assertNull(model.state.walletProblem)
        val wallet = model.state.wallet!!
        assertEquals(Chain.MAIN, wallet.chain)
        val watch = wallet.watchKey()
        assertEquals("north1zk3jwu52pe3h5sendhwpm7e6hlfs9657zltfrvxfqee4gxlgj25usu60dh3", watch.address(0, 2))
        assertEquals("north1zr2q797hdf9rsaxaj6rjczrq3kyczdwaa4fzayfv6046q72qqurmsp6788r", watch.address(1, 1))

        // the whole paper is checked with twelve words as with twenty-four
        assertTrue(model.checkBackup(phrase))
        until("the paper is compared") { !model.state.backupBusy }
        assertEquals(true, model.state.backupSame)
        assertTrue(model.checkBackup("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"))
        until("another paper is compared") { !model.state.backupBusy }
        assertEquals(false, model.state.backupSame)
    }
}
