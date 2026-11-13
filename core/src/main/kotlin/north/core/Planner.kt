package north.core

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import java.security.SecureRandom

/** New recovery words, and the checks on words somebody types in. */
object Words {
    /** 24 words from 256 bits of the system's randomness. */
    fun generate(random: SecureRandom = SecureRandom()): List<String> {
        val entropy = ByteArray(32)
        random.nextBytes(entropy)
        try {
            return MnemonicCode.toMnemonics(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    /** Typed text to words: lower case, any white space between them. */
    fun split(text: String): List<String> = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** True if these are 12 or 24 words of the English BIP39 list with a correct checksum. */
    fun valid(words: List<String>): Boolean =
        (words.size == 12 || words.size == 24) && runCatching { MnemonicCode.validate(words) }.isSuccess
}

/** One unspent output of the wallet: where it is on the chain and which key of the account it belongs to. */
data class Unspent(
    val txid: String, val vout: Long, val amountSat: Long, val branch: Int, val index: Int, val height: Int, val coinbase: Boolean,
) {
    /** A block reward can be spent once it is 100 blocks deep, counted the way TrueNorth Core's wallet shows it. */
    fun spendableAt(tip: Int): Boolean = height in 1..tip && (!coinbase || height <= tip - Planner.MATURITY)
}

class PlanError(val reason: Reason, val availableSat: Long = 0) : Exception(reason.name) {
    enum class Reason { NOTHING_TO_SPEND, AMOUNT_TOO_SMALL, NOT_ENOUGH, FEE_RATE, TOO_MANY_INPUTS }
}

/** A payment worked out but not signed: which coins go in, what goes out, and what it costs. */
class Plan(val inputs: List<Unspent>, val payments: List<Payment>, val feeSat: Long, val vsize: Int, val changeSat: Long) {
    val inputSat: Long get() = inputs.sumOf { it.amountSat }
    val sentSat: Long get() = payments.sumOf { it.amountSat } - changeSat

    /** Signs the plan with the keys of [account]. The result is ready for testmempoolaccept and sendrawtransaction. */
    fun sign(account: Account, random: SecureRandom = SecureRandom()): SignedSpend {
        val aux = ByteArray(32).also { random.nextBytes(it) }
        val coins = inputs.map { Coin(it.txid, it.vout, it.amountSat, it.branch, it.index) }
        val signed = Spend.sign(account, coins, payments, ByteVector32(aux))
        check(signed.feeSat == feeSat && signed.vsize == vsize) { "the signed transaction differs from the plan" }
        return signed
    }
}

/**
 * Chooses coins and works out the fee. Nothing here touches keys or the network.
 *
 * The rule is simple on purpose: the largest coins first, until the amount and the fee are covered. What is
 * left comes back to the wallet's own change address; if it would be smaller than [MIN_OUTPUT_SAT] it is not
 * worth an output and goes to the fee instead (at most a few hundred base units).
 */
object Planner {
    const val MATURITY = 100
    /** Smallest output this wallet makes. The node's own dust limit for these outputs is 330. */
    const val MIN_OUTPUT_SAT = 1000L
    /** Base units per virtual byte. The network's minimum is 0.1; the wallet does not go below 1 or above 1000. */
    const val MIN_FEE_RATE = 1L
    const val MAX_FEE_RATE = 1000L
    const val DEFAULT_FEE_RATE = 2L
    const val MAX_INPUTS = 1000

    private fun checkRate(feeRate: Long) {
        if (feeRate < MIN_FEE_RATE || feeRate > MAX_FEE_RATE) throw PlanError(PlanError.Reason.FEE_RATE)
    }

    private fun ordered(coins: List<Unspent>): List<Unspent> =
        coins.distinctBy { it.txid + ":" + it.vout }.sortedWith(compareByDescending<Unspent> { it.amountSat }.thenBy { it.txid }.thenBy { it.vout })

    /** Pays [amountSat] to the script [to]; the rest returns to the script [change]. */
    fun pay(spendable: List<Unspent>, to: ByteArray, amountSat: Long, feeRate: Long, change: ByteArray): Plan {
        checkRate(feeRate)
        if (amountSat < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.AMOUNT_TOO_SMALL)
        val coins = ordered(spendable)
        if (coins.isEmpty()) throw PlanError(PlanError.Reason.NOTHING_TO_SPEND)
        var sum = 0L
        for (n in 1..minOf(coins.size, MAX_INPUTS)) {
            sum += coins[n - 1].amountSat
            val sizeWithChange = Spend.vsize(n, listOf(to, change))
            val left = sum - amountSat - sizeWithChange * feeRate
            if (left >= MIN_OUTPUT_SAT) {
                return Plan(coins.take(n), listOf(Payment(to, amountSat), Payment(change, left)), sizeWithChange * feeRate, sizeWithChange, left)
            }
            val sizeAlone = Spend.vsize(n, listOf(to))
            if (sum - amountSat >= sizeAlone * feeRate) {
                return Plan(coins.take(n), listOf(Payment(to, amountSat)), sum - amountSat, sizeAlone, 0)
            }
        }
        if (coins.size > MAX_INPUTS) throw PlanError(PlanError.Reason.TOO_MANY_INPUTS, sum)
        throw PlanError(PlanError.Reason.NOT_ENOUGH, maxOf(0, sum - Spend.vsize(coins.size, listOf(to)) * feeRate))
    }

    /**
     * Spends every coin: [keepSat] to the script [to], and all the rest, less the fee, to the script [rest].
     * Used to empty a paper address into two places at once.
     */
    fun split(spendable: List<Unspent>, to: ByteArray, keepSat: Long, feeRate: Long, rest: ByteArray): Plan {
        checkRate(feeRate)
        if (keepSat < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.AMOUNT_TOO_SMALL)
        val coins = ordered(spendable)
        if (coins.isEmpty()) throw PlanError(PlanError.Reason.NOTHING_TO_SPEND)
        if (coins.size > MAX_INPUTS) throw PlanError(PlanError.Reason.TOO_MANY_INPUTS, coins.take(MAX_INPUTS).sumOf { it.amountSat })
        val size = Spend.vsize(coins.size, listOf(to, rest))
        val all = coins.sumOf { it.amountSat }
        val left = all - keepSat - size * feeRate
        if (left < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.NOT_ENOUGH, maxOf(0, all - size * feeRate - MIN_OUTPUT_SAT))
        return Plan(coins, listOf(Payment(to, keepSat), Payment(rest, left)), size * feeRate, size, left)
    }

    /** Sends everything in [spendable] to the script [to], less the fee. Used to empty a wallet or a paper address. */
    fun sweep(spendable: List<Unspent>, to: ByteArray, feeRate: Long): Plan {
        checkRate(feeRate)
        val coins = ordered(spendable)
        if (coins.isEmpty()) throw PlanError(PlanError.Reason.NOTHING_TO_SPEND)
        if (coins.size > MAX_INPUTS) throw PlanError(PlanError.Reason.TOO_MANY_INPUTS, coins.take(MAX_INPUTS).sumOf { it.amountSat })
        val size = Spend.vsize(coins.size, listOf(to))
        val amount = coins.sumOf { it.amountSat } - size * feeRate
        if (amount < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.NOT_ENOUGH, maxOf(0, amount))
        return Plan(coins, listOf(Payment(to, amount)), size * feeRate, size, 0)
    }
}
