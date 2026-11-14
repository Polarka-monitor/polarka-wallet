package north.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coins that a payment must spend whatever its amount. That is how a payment is made to stand against an
 * earlier one that did not go through and could still arrive: both spend the same coin, so only one of them
 * can ever be in a block (0.4.2).
 */
class PlannerMustTest {
    private val to = ByteArray(34) { 1 }
    private val change = ByteArray(34) { 2 }
    private fun coin(n: Int, sat: Long) = Unspent("%064x".format(n), 0, sat, 0, n, 5, false)
    private val small = coin(1, 10_00000000L)
    private val middle = coin(2, 50_00000000L)
    private val large = coin(3, 100_00000000L)
    private val all = listOf(small, middle, large)

    @Test
    fun withoutSuchCoinsNothingChanges() {
        val plan = Planner.pay(all, to, 5_00000000L, 2, change)
        assertEquals(listOf(large), plan.inputs)
        assertEquals(plan.inputs, Planner.pay(all, to, 5_00000000L, 2, change, emptyList()).inputs)
        assertEquals(Planner.sweep(all, to, 2).inputs, Planner.sweep(all, to, 2, emptyList()).inputs)
    }

    @Test
    fun aCoinThatMustBeSpentIsSpentEvenWhenALargerOneWouldDo() {
        val plan = Planner.pay(all, to, 5_00000000L, 2, change, listOf(small))
        assertEquals(listOf(small), plan.inputs, "the small coin covers the amount, and it is the one that must go")
        assertEquals(5_00000000L, plan.sentSat)
        assertEquals(small.amountSat, plan.payments.sumOf { it.amountSat } + plan.feeSat)
    }

    @Test
    fun whenItDoesNotCoverTheAmountTheLargestOthersAreAdded() {
        val plan = Planner.pay(all, to, 60_00000000L, 2, change, listOf(small))
        assertEquals(listOf(small, large), plan.inputs, "the coin that must go comes first, then the largest")
        assertEquals(60_00000000L, plan.sentSat)
        assertEquals(small.amountSat + large.amountSat, plan.payments.sumOf { it.amountSat } + plan.feeSat)
        assertEquals(Spend.vsize(2, listOf(to, change)) * 2L, plan.feeSat)
    }

    @Test
    fun severalSuchCoinsAllGoInAndNoneTwice() {
        val plan = Planner.pay(all, to, 5_00000000L, 2, change, listOf(small, middle, small))
        assertEquals(listOf(small, middle), plan.inputs)
        // a coin that is not among the ones offered is spent all the same: the caller says what must go
        val other = coin(9, 3_00000000L)
        assertEquals(listOf(other), Planner.pay(all, to, 1_00000000L, 2, change, listOf(other)).inputs)
    }

    @Test
    fun emptyingTheWalletTakesThemFirst() {
        val plan = Planner.sweep(all, to, 2, listOf(small))
        assertEquals(listOf(small, large, middle), plan.inputs)
        assertEquals(all.sumOf { it.amountSat } - plan.feeSat, plan.payments.single().amountSat)
        // with more coins than one transaction takes, the ones that must go are still among those taken
        val many = List(Planner.MAX_INPUTS + 5) { coin(100 + it, 2_00000000L + it) }
        val tiny = coin(5000, 1000L)
        val part = Planner.sweep(many + tiny, to, 1, listOf(tiny))
        assertEquals(Planner.MAX_INPUTS, part.inputs.size)
        assertEquals(tiny, part.inputs.first())
        assertEquals(6, part.leftBehind)
    }

    @Test
    fun notEnoughIsStillNotEnough() {
        val error = assertFailsWith<PlanError> { Planner.pay(all, to, 500_00000000L, 2, change, listOf(small)) }
        assertEquals(PlanError.Reason.NOT_ENOUGH, error.reason)
        assertTrue(error.availableSat in 1 until all.sumOf { it.amountSat })
    }
}
