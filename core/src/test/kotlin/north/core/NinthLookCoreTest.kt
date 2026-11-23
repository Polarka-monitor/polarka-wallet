package north.core

import fr.acinq.bitcoin.ByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Review 9 (the human review of 0.7.1), M-3: an amount typed as large as a Long can hold, with a first coin
 * smaller than the fee, made Planner.pay wrap around the 64 bits of a Long into a large positive change, and
 * Spend.sign then wrapped the sum back and signed. Now no amount is above MAX_SAT and nothing can wrap.
 */
class NinthLookCoreTest {
    private val to = ByteArray(34) { 1 }
    private val change = ByteArray(34) { 2 }
    private fun coin(n: Int, sat: Long) = Unspent("%064x".format(n), 0, sat, 0, n, 5, false)
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val account = Account.fromWords(words, Chain.REGTEST)

    @Test
    fun theAmountOfTheReviewIsNotEnoughAndNoPlan() {
        val dust = coin(1, 500)                     // above the node's dust limit, below the fee at 10 base units a vbyte
        val fee = Spend.vsize(1, listOf(to, change)) * 10L
        check(dust.amountSat < fee)
        val e = assertFailsWith<PlanError> { Planner.pay(listOf(dust), to, Long.MAX_VALUE, 10, change) }
        assertEquals(PlanError.Reason.NOT_ENOUGH, e.reason)
        // the same with the small coin as one that must be spent, and larger coins after it
        val e2 = assertFailsWith<PlanError> { Planner.pay(listOf(dust, coin(2, 3_00000000L)), to, Long.MAX_VALUE, 10, change, listOf(dust)) }
        assertEquals(PlanError.Reason.NOT_ENOUGH, e2.reason)
    }

    @Test
    fun anAmountAboveAllCoinsThatCanExistIsNeverCovered() {
        val coins = listOf(coin(1, 5_00000000L))
        for (amount in listOf(Planner.MAX_SAT + 1, Long.MAX_VALUE / 2, Long.MAX_VALUE)) {
            assertEquals(PlanError.Reason.NOT_ENOUGH, assertFailsWith<PlanError> { Planner.pay(coins, to, amount, 2, change) }.reason)
            assertEquals(PlanError.Reason.NOT_ENOUGH, assertFailsWith<PlanError> { Planner.split(coins, to, amount, 2, change) }.reason)
        }
        val e = assertFailsWith<PlanError> { Planner.pay(coins, to, Long.MAX_VALUE, 2, change) }
        assertEquals(5_00000000L - Spend.vsize(1, listOf(to)) * 2, e.availableSat)
    }

    @Test
    fun coinsThatCannotExistAreAFaultNotAPlan() {
        assertFailsWith<IllegalArgumentException> { Planner.pay(listOf(coin(1, Planner.MAX_SAT + 1)), to, 1_00000000L, 2, change) }
        val twoHalves = listOf(coin(1, Planner.MAX_SAT / 2 + 1), coin(2, Planner.MAX_SAT / 2 + 1))
        assertFailsWith<IllegalArgumentException> { Planner.sweep(twoHalves, to, 2) }
        assertFailsWith<IllegalArgumentException> { Planner.split(twoHalves, to, 1_00000000L, 2, change) }
        assertFailsWith<IllegalArgumentException> { Planner.pay(listOf(coin(1, -1)), to, 1_00000000L, 2, change) }
    }

    @Test
    fun ordinaryPaymentsAreAsBefore() {
        val coins = listOf(coin(1, 10_00000000L), coin(2, 50_00000000L))
        val plan = Planner.pay(coins, to, 5_00000000L, 2, change)
        assertEquals(5_00000000L, plan.sentSat)
        assertEquals(plan.inputSat, plan.payments.sumOf { it.amountSat } + plan.feeSat)
        val split = Planner.split(coins, to, 7_00000000L, 2, change)
        assertEquals(7_00000000L, split.payments[0].amountSat)
        assertEquals(split.inputSat, split.payments.sumOf { it.amountSat } + split.feeSat)
    }

    @Test
    fun signRefusesAmountsThatCannotExist() {
        val script = account.script(0, 0)
        val coins = listOf(Coin("%064x".format(1), 0, 1_00000000L, 0, 0))
        val aux = ByteVector32(ByteArray(32))
        // the two payments of the review: one as large as a Long holds, and the change that wraps the sum back
        assertFailsWith<IllegalArgumentException> {
            Spend.sign(account, coins, listOf(Payment(script, Long.MAX_VALUE), Payment(script, 1_00000000L - 1000 + 1)), aux)
        }
        assertFailsWith<IllegalArgumentException> { Spend.sign(account, coins, listOf(Payment(script, Planner.MAX_SAT + 1)), aux) }
        assertFailsWith<IllegalArgumentException> { Spend.sign(account, listOf(Coin("%064x".format(1), 0, Planner.MAX_SAT + 1, 0, 0)), listOf(Payment(script, 1000)), aux) }
        assertFailsWith<IllegalArgumentException> { Spend.sign(account, coins, listOf(Payment(script, 0)), aux) }
        // an ordinary one still signs, and its fee is the difference
        val signed = Spend.sign(account, coins, listOf(Payment(script, 99_990_000L)), aux)
        assertEquals(10_000L, signed.feeSat)
    }
}
