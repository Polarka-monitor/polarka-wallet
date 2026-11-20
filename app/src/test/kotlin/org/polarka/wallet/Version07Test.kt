package org.polarka.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** 0.7: the fixed address is kept in the wallet file, and the faucet's work is the work the faucet checks. */
class Version07Test {
    private val xpub = north.core.Account.fromWords(north.core.Words.generate(), north.core.Chain.MAIN).watchKey().xpub

    @Test
    fun fixedAddressIsKeptOnlyWhenTheOwnerSaidSo() {
        val text = WalletInfo(north.core.Chain.MAIN, xpub).toJson()
        assertTrue("fixedAddress" !in text)
        assertNull(WalletInfo.parse(text).fixedAddress)
        assertEquals(BuildConfig.FIXED_ADDRESS, WalletInfo.parse(text).fixed)
        for (on in listOf(true, false)) {
            val back = WalletInfo.parse(WalletInfo(north.core.Chain.MAIN, xpub, fixedAddress = on).toJson())
            assertEquals(on, back.fixedAddress)
            assertEquals(on, back.fixed)
        }
    }

    @Test
    fun zeroBitsAreCountedAsTheFaucetCountsThem() {
        assertEquals(0, Faucet.leadingZeroBits(byteArrayOf(0x80.toByte())))
        assertEquals(7, Faucet.leadingZeroBits(byteArrayOf(0x01)))
        assertEquals(8, Faucet.leadingZeroBits(byteArrayOf(0x00, 0xff.toByte())))
        assertEquals(12, Faucet.leadingZeroBits(byteArrayOf(0x00, 0x0f)))
        assertEquals(16, Faucet.leadingZeroBits(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun theAnswerFoundIsOneTheFaucetTakes() {
        val challenge = "abc123|16|1795209999|nonce"
        val answer = Faucet.solve(challenge, 12, until = Long.MAX_VALUE / 1000)!!
        val digest = MessageDigest.getInstance("SHA-256").digest("$challenge:$answer".toByteArray())
        assertTrue(Faucet.leadingZeroBits(digest) >= 12)
        // a challenge that has run out is not worked on
        assertNull(Faucet.solve(challenge, 30, until = 0, now = { 1_000_000L }))
    }
}
