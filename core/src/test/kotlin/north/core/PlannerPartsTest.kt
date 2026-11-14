package north.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Emptying an address that holds more coins than one transaction takes (finding F-12 of the audit of 0.3.1). */
class PlannerPartsTest {
    private val to = ByteArray(34) { 1 }
    private val rest = ByteArray(34) { 2 }

    @Test
    fun emptyingTakesTheLargestThousandCoinsAndSaysHowManyAreLeft() {
        val coins = List(1001) { Unspent("%064x".format(it + 1), 0, 1_00000000L + it, 0, 0, 5, false) }
        val first = Planner.sweep(coins, to, 1)
        assertEquals(Planner.MAX_INPUTS, first.inputs.size)
        assertEquals(1, first.leftBehind)
        assertTrue(first.inputs.none { it.amountSat == 1_00000000L }, "the smallest coin is the one that waits")
        assertEquals(first.inputs.sumOf { it.amountSat } - first.feeSat, first.payments.single().amountSat)

        val split = Planner.split(coins, to, 10_00000000L, 1, rest)
        assertEquals(Planner.MAX_INPUTS, split.inputs.size)
        assertEquals(1, split.leftBehind)
        assertEquals(split.inputs.sumOf { it.amountSat }, split.payments.sumOf { it.amountSat } + split.feeSat)

        // the second part takes what the first one left, and then nothing is left
        val second = Planner.sweep(coins - first.inputs.toSet(), to, 1)
        assertEquals(1, second.inputs.size)
        assertEquals(0, second.leftBehind)
        assertEquals(0, Planner.sweep(coins.take(Planner.MAX_INPUTS), to, 1).leftBehind)
    }
}
