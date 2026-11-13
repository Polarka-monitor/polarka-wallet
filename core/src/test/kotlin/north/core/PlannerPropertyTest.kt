package north.core

import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Many random wallets and payments: whatever the planner answers must obey the rules that protect the coins. */
class PlannerPropertyTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val account = Account.fromWords(words, Chain.REGTEST)
    private val to = account.script(0, 50)
    private val change = account.script(1, 7)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun check(plan: Plan, coins: List<Unspent>, rate: Long, expectChangeTo: ByteArray?) {
        val ids = plan.inputs.map { it.txid + ":" + it.vout }
        assertEquals(ids.size, ids.toSet().size, "a coin is used twice")
        assertTrue(plan.inputs.all { it in coins }, "a coin that was not offered is used")
        assertEquals(plan.inputSat, plan.payments.sumOf { it.amountSat } + plan.feeSat, "inputs must equal outputs plus fee")
        assertTrue(plan.payments.all { it.amountSat >= Planner.MIN_OUTPUT_SAT }, "an output below the smallest allowed")
        assertEquals(Spend.vsize(plan.inputs.size, plan.payments.map { it.script }), plan.vsize)
        assertTrue(plan.feeSat >= rate * plan.vsize, "the fee is below the chosen rate")
        // the fee can exceed the rate only by a rest too small for an output and the cost of that output
        assertTrue(plan.feeSat < rate * plan.vsize + Planner.MIN_OUTPUT_SAT + rate * 43, "the fee is higher than the rules allow: ${plan.feeSat}")
        if (plan.payments.size == 2 && expectChangeTo != null) assertEquals(hex(expectChangeTo), hex(plan.payments[1].script))
    }

    @Test
    fun randomPaymentsObeyTheRules() {
        val random = Random(20261113)
        var paid = 0
        var refused = 0
        repeat(20_000) { round ->
            val coins = List(1 + random.nextInt(12)) { n ->
                Unspent("%064x".format(round * 100L + n), random.nextInt(3).toLong(), 1 + (random.nextDouble() * random.nextDouble() * 600_00000000L).toLong(), 0, n, 5, false)
            }
            val total = coins.sumOf { it.amountSat }
            val rate = 1L + random.nextInt(50)
            val amount = when (random.nextInt(4)) {
                0 -> total                                                  // more than can be paid once the fee is added
                1 -> Planner.MIN_OUTPUT_SAT + random.nextInt(5000)
                else -> 1 + (random.nextDouble() * total).toLong()
            }
            try {
                val plan = Planner.pay(coins, to, amount, rate, change)
                check(plan, coins, rate, change)
                assertEquals(amount, plan.payments[0].amountSat)
                assertEquals(hex(to), hex(plan.payments[0].script))
                paid++
            } catch (e: PlanError) {
                when (e.reason) {
                    PlanError.Reason.AMOUNT_TOO_SMALL -> assertTrue(amount < Planner.MIN_OUTPUT_SAT)
                    PlanError.Reason.NOT_ENOUGH -> {
                        assertTrue(e.availableSat < amount, "refused although enough was there")
                        if (e.availableSat >= Planner.MIN_OUTPUT_SAT) check(Planner.pay(coins, to, e.availableSat, rate, change), coins, rate, change)
                    }
                    else -> throw e
                }
                refused++
            }
            // emptying the same wallet: every coin goes, one output, exact fee
            try {
                val all = Planner.sweep(coins, to, rate)
                check(all, coins, rate, null)
                assertEquals(coins.size, all.inputs.size)
                assertEquals(1, all.payments.size)
                assertEquals(rate * all.vsize, all.feeSat)
            } catch (e: PlanError) {
                assertEquals(PlanError.Reason.NOT_ENOUGH, e.reason)
                assertTrue(total - rate * Spend.vsize(coins.size, listOf(to)) < Planner.MIN_OUTPUT_SAT)
            }
            // splitting it: every coin goes, two outputs, exact fee
            val keep = Planner.MIN_OUTPUT_SAT + (random.nextDouble() * total / 2).toLong()
            try {
                val two = Planner.split(coins, to, keep, rate, change)
                check(two, coins, rate, change)
                assertEquals(coins.size, two.inputs.size)
                assertEquals(keep, two.payments[0].amountSat)
                assertEquals(rate * two.vsize, two.feeSat)
            } catch (e: PlanError) {
                assertEquals(PlanError.Reason.NOT_ENOUGH, e.reason)
            }
        }
        assertTrue(paid > 5000 && refused > 1000, "the random cases must cover both outcomes: $paid paid, $refused refused")
        println("PLANNER OK: 20000 random wallets, $paid payments planned, $refused refused, every plan within the rules")
    }

    /**
     * Words to addresses against a second implementation that shares no code with this one: the cold address
     * tool in Python. This writes the phrases with this code's answers; review/crosscheck.py recomputes them.
     */
    @Test
    fun writesRandomPhrasesForTheCrossCheck() {
        val out = System.getenv("NORTH_CROSSCHECK") ?: return
        val lines = (1..300).map {
            val phrase = Words.generate()
            val main = Account.fromWords(phrase, Chain.MAIN)
            listOf(phrase.joinToString(" "), main.address(0, 0), main.address(0, 19), main.address(1, 3), main.watchKey().descriptor(0)).joinToString("\t")
        }
        File(out).writeText(lines.joinToString("\n") + "\n")
        println("CROSSCHECK: wrote ${lines.size} phrases to $out")
    }
}
