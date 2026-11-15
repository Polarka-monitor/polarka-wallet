package org.polarka.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the reviewer's look at the fixes of 0.6.0 left open, where it can be shown without a node: when a
 * payment counts as one that has waited long for a block, and what a refused emptying of a paper address is
 * called.
 */
class AfterSixthLookPlainTest {
    @Test
    fun aPaymentHasWaitedLongAfterAnHourAndOnlyWhenItsTimeIsKnown() {
        val hour = WalletInfo.LONG_WAIT_MILLIS
        assertEquals(60 * 60_000L, hour)
        val made = 1_800_000_000_000L
        val waiting = Pending("aa".repeat(32), 5, 1, setOf("bb".repeat(32) + ":0"), unsure = false, atMillis = made)
        assertFalse(waiting.waitsLong(made))
        assertFalse(waiting.waitsLong(made + hour - 1))
        assertTrue(waiting.waitsLong(made + hour))
        // the clock of the phone was ahead when the payment was made: it has not waited long
        assertFalse(waiting.waitsLong(made - 5 * hour))
        // a payment whose outcome is open does not wait for a block, and its entry says something else
        assertFalse(waiting.copy(unsure = true).waitsLong(made + 2 * hour))
        // a wallet file of an early version has no time for a payment: nothing is claimed about it
        assertFalse(waiting.copy(atMillis = 0).waitsLong(made + 2 * hour))
    }

    @Test
    fun onlyTheOwnersNodeIsBelievedThatNothingOfAPaperAddressWentOut() {
        val no = Problem(Problem.Kind.REJECTED, "min relay fee not met")
        assertEquals(no, paperRefusal(no, ownersNode = true))
        assertEquals(Problem(Problem.Kind.PAPER_REFUSED, "min relay fee not met"), paperRefusal(no, ownersNode = false))
        // what is no refusal by a node stays what it is, whoever was asked
        val other = Problem(Problem.Kind.STORE, "wallet")
        assertEquals(other, paperRefusal(other, ownersNode = false))
        assertEquals(other, paperRefusal(other, ownersNode = true))
    }
}
