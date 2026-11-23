package org.polarka.wallet

import north.core.Chain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7: the fixed address is kept in the wallet file and decides which address is shown; whose a wallet server is
 * follows from its address alone. The faucet's tests are those of the personal edition (src/testPersonal), the
 * tests of what each edition shows are in src/testPublic and src/testPersonal.
 */
class Version07Test {
    private val xpub = north.core.Account.fromWords(north.core.Words.generate(), Chain.MAIN).watchKey().xpub

    @Test
    fun fixedAddressIsKeptOnlyWhenTheOwnerSaidSo() {
        val text = WalletInfo(Chain.MAIN, xpub).toJson()
        assertTrue("fixedAddress" !in text)
        assertNull(WalletInfo.parse(text).fixedAddress)
        assertEquals(BuildConfig.FIXED_ADDRESS, WalletInfo.parse(text).fixed)
        for (on in listOf(true, false)) {
            val back = WalletInfo.parse(WalletInfo(Chain.MAIN, xpub, fixedAddress = on).toJson())
            assertEquals(on, back.fixedAddress)
            assertEquals(on, back.fixed)
        }
    }

    @Test
    fun theAddressShownIsTheFirstWhileFixedAndTheNextOneOtherwise() {
        val wallet = WalletInfo(Chain.MAIN, xpub, receiveIndex = 5)
        val key = wallet.watchKey()
        assertEquals(0, wallet.copy(fixedAddress = true).shownIndex)
        assertEquals(key.address(0, 0), wallet.copy(fixedAddress = true).shownAddress())
        assertEquals(5, wallet.copy(fixedAddress = false).shownIndex)
        assertEquals(key.address(0, 5), wallet.copy(fixedAddress = false).shownAddress())
        // without the owner's word the edition decides
        assertEquals(if (BuildConfig.FIXED_ADDRESS) 0 else 5, wallet.shownIndex)
    }

    @Test
    fun whoseAServerIsFollowsFromItsAddressAlone() {
        for (typed in listOf(
            "https://api.northboundwallet.org", "https://api.northboundwallet.org/", "api.northboundwallet.org", " API.NorthboundWallet.org ",
            "https://api.northboundwallet.org:443", "https://api.northboundwallet.org.",
        )) assertEquals(ServerOwner.NORTHBOUND, Setup.ownerOf(typed), typed)
        for (typed in listOf("https://node.polarka.org", "https://node.polarka.org/", "node.polarka.org", "Node.Polarka.Org", "node.polarka.org/")) {
            assertEquals(ServerOwner.POLARKA, Setup.ownerOf(typed), typed)
        }
        for (typed in listOf(
            "https://node.polarka.org.example.org", "https://example.org/node.polarka.org", "http://node.polarka.org", "https://node.polarka.org:8443",
            "https://user@node.polarka.org", "https://node.polarka.org/api", "https://node.polarka.org/?a=1", "https://node.polarka.org/#a",
            "https://polarka.org", "https://northboundwallet.org", "https://api.northboundwallet.org.example.org", "server.example.org", "", "not an address",
        )) assertEquals(ServerOwner.OTHER, Setup.ownerOf(typed), typed)
        // what the screen says of a typed address is what it says of the address that is saved for it
        for (typed in listOf("node.polarka.org", "https://node.polarka.org/", "api.northboundwallet.org", "server.example.org")) {
            assertEquals(Setup.ownerOf(typed), Setup.ownerOf(Setup.server(typed).url), typed)
        }
    }
}
