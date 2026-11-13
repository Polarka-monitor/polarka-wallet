package north.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The parts of the wallet that need no node: the watch key and its descriptors, new words, choosing coins. */
class WalletEngineTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // The four descriptors below were made by the cold address tool (Python, no shared code) for the public
    // test phrase. TrueNorth Core v30.5.4 confirms their checksums (getdescriptorinfo) and derives the
    // addresses of the published vectors from them (deriveaddresses).
    private val mainKey = "Tpub17LTNxYHMGvdAp8cFBGxqwCa53K8N7nNQQRfgXSseFei4YNGdecWamM3Cfna7c1nWCtTZ9aTu12mKWbzoK5vy9V3SkuWYiuwWmCBdhTV4QyP"
    private val regtestKey = "tpuadsaFXyjTJh3ZqUgPeMSFEq7GPL32KbTwVj9SiWpxhxhGKaDfb2REQABJxvmANzcPVdxCX9mY3oBUB8vLDrNpQX7xFbYyYQxvNG1k9aq1UYs"

    @Test
    fun theWatchKeyAndItsDescriptorsAreTheOnesCoreAccepts() {
        val main = Account.fromWords(words, Chain.MAIN).watchKey()
        assertEquals(mainKey, main.xpub)
        assertEquals("qrh($mainKey/0/*)#cqvhc4j3", main.descriptor(0))
        assertEquals("qrh($mainKey/1/*)#f5fk9qzf", main.descriptor(1))
        val regtest = Account.fromWords(words, Chain.REGTEST).watchKey()
        assertEquals("qrh($regtestKey/0/*)#w32n6ay0", regtest.descriptor(0))
        assertEquals("qrh($regtestKey/1/*)#l90j8g5h", regtest.descriptor(1))
        assertEquals("north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx", main.address(0, 0))
    }

    @Test
    fun theWatchKeyGivesTheSameAddressesAsThePrivateKeys() {
        for (chain in Chain.entries) {
            val account = Account.fromWords(words, chain)
            val watch = WatchKey.parse(account.watchKey().xpub, chain)
            for (branch in 0..1) for (index in listOf(0, 1, 2, 19, 1000)) {
                assertEquals(account.address(branch, index), watch.address(branch, index))
                assertEquals(hex(account.script(branch, index)), hex(watch.script(branch, index)))
            }
        }
        assertFailsWith<IllegalArgumentException> { WatchKey.parse(mainKey, Chain.REGTEST) }
        assertFailsWith<Exception> { WatchKey.parse(mainKey.dropLast(1) + "Q", Chain.MAIN) }
    }

    @Test
    fun newWordsAreValidAndDifferentEachTime() {
        val a = Words.generate()
        val b = Words.generate()
        assertEquals(24, a.size)
        assertTrue(Words.valid(a) && Words.valid(b))
        assertNotEquals(a, b)
        assertNotEquals(Account.fromWords(a, Chain.MAIN).address(0, 0), Account.fromWords(b, Chain.MAIN).address(0, 0))
        assertEquals(words, Words.split("  Abandon abandon\tabandon abandon abandon abandon\nabandon abandon abandon abandon abandon ABOUT "))
        assertTrue(Words.valid(words))
        assertFalse(Words.valid(words.dropLast(1) + "abandon"))      // wrong checksum
        assertFalse(Words.valid(words.dropLast(1)))                   // eleven words
        assertFalse(Words.valid(words.dropLast(1) + "sever"))         // not on the list
    }

    private val account = Account.fromWords(words, Chain.REGTEST)
    private val to = Account.fromWords(words, Chain.MAIN).script(0, 5)
    private val change = account.script(1, 0)
    private fun coin(n: Int, sat: Long) = Unspent("%064x".format(n), 0, sat, 0, n, 10, false)

    @Test
    fun aPaymentTakesTheLargestCoinsAndReturnsTheRest() {
        val coins = listOf(coin(1, 500_000), coin(2, 9_000_000), coin(3, 2_000_000), coin(4, 7_000_000))
        val plan = Planner.pay(coins, to, 10_000_000, 3, change)
        assertEquals(listOf(9_000_000L, 7_000_000L), plan.inputs.map { it.amountSat })
        assertEquals(Spend.vsize(2, listOf(to, change)), plan.vsize)
        assertEquals(3L * plan.vsize, plan.feeSat)
        assertEquals(16_000_000 - 10_000_000 - plan.feeSat, plan.changeSat)
        assertEquals(10_000_000, plan.sentSat)
        assertEquals(plan.inputSat, plan.payments.sumOf { it.amountSat } + plan.feeSat)
        assertEquals(hex(change), hex(plan.payments[1].script))
        // the same coins in another order give the same plan
        assertEquals(plan.inputs, Planner.pay(coins.reversed(), to, 10_000_000, 3, change).inputs)
        // and the plan signs into a transaction of exactly that size and fee
        val signed = plan.sign(account)
        assertEquals(plan.vsize, signed.vsize)
        assertEquals(plan.feeSat, signed.feeSat)
    }

    @Test
    fun aRestTooSmallForAnOutputGoesToTheFee() {
        val alone = Spend.vsize(1, listOf(to))
        val plan = Planner.pay(listOf(coin(1, 1_000_000)), to, 1_000_000 - alone * 2L - 400, 2, change)
        assertEquals(1, plan.payments.size)
        assertEquals(0, plan.changeSat)
        assertEquals(alone * 2L + 400, plan.feeSat)
        assertEquals(alone, plan.vsize)
    }

    @Test
    fun whatCannotBePaidIsRefusedWithTheReason() {
        val coins = listOf(coin(1, 1_000_000), coin(2, 2_000_000))
        val short = assertFailsWith<PlanError> { Planner.pay(coins, to, 3_000_000, 2, change) }
        assertEquals(PlanError.Reason.NOT_ENOUGH, short.reason)
        assertEquals(3_000_000 - Spend.vsize(2, listOf(to)) * 2L, short.availableSat)
        assertEquals(PlanError.Reason.NOTHING_TO_SPEND, assertFailsWith<PlanError> { Planner.pay(emptyList(), to, 5000, 2, change) }.reason)
        assertEquals(PlanError.Reason.AMOUNT_TOO_SMALL, assertFailsWith<PlanError> { Planner.pay(coins, to, 999, 2, change) }.reason)
        assertEquals(PlanError.Reason.FEE_RATE, assertFailsWith<PlanError> { Planner.pay(coins, to, 5000, 0, change) }.reason)
        assertEquals(PlanError.Reason.FEE_RATE, assertFailsWith<PlanError> { Planner.pay(coins, to, 5000, 1001, change) }.reason)
        // the amount that the error names can really be sent
        assertEquals(1, Planner.pay(coins, to, short.availableSat, 2, change).payments.size)
    }

    @Test
    fun aSweepSendsEverythingLessTheFee() {
        val coins = listOf(coin(1, 1_000_000), coin(2, 2_000_000), coin(2, 2_000_000))      // the same coin twice counts once
        val plan = Planner.sweep(coins, to, 5)
        assertEquals(2, plan.inputs.size)
        assertEquals(1, plan.payments.size)
        assertEquals(3_000_000 - plan.feeSat, plan.payments[0].amountSat)
        assertEquals(5L * Spend.vsize(2, listOf(to)), plan.feeSat)
        assertEquals(PlanError.Reason.NOT_ENOUGH, assertFailsWith<PlanError> { Planner.sweep(listOf(coin(1, 1200)), to, 5) }.reason)
    }

    @Test
    fun blockRewardsWaitAHundredBlocks() {
        val reward = Unspent("%064x".format(1), 0, 512, 0, 0, 50, true)
        assertFalse(reward.spendableAt(149))
        assertTrue(reward.spendableAt(150))
        assertTrue(reward.copy(coinbase = false).spendableAt(50))
        assertFalse(reward.copy(coinbase = false).spendableAt(49))
    }
}
