package north.core

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import java.security.SecureRandom

/** New recovery words, and the checks on words somebody types in. */
object Words {
    /** New words from the system's randomness: 24 words from 256 bits, or 12 words from 128 bits. */
    fun generate(count: Int = 24, random: SecureRandom = SecureRandom()): List<String> {
        require(count == 12 || count == 24) { "a wallet has 12 or 24 words" }
        val entropy = ByteArray(if (count == 12) 16 else 32)
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

    /** What is wrong with typed words, so that the person who typed them can be told. */
    sealed interface Fault {
        /** Words that are not on the English BIP39 list, by their place in what was typed; the first word is 1. */
        data class NotOnList(val places: List<Int>) : Fault

        /** Every word is on the list, but they are not 12 and not 24. */
        data class Count(val words: Int) : Fault

        /** Every word is on the list and their number is right, but they do not fit each other (the checksum). */
        data object Mismatch : Fault
    }

    /** Null exactly when the words are [valid]; otherwise the first thing to put right. */
    fun fault(words: List<String>): Fault? {
        val strangers = words.indices.filter { words[it] !in onList }.map { it + 1 }
        return when {
            strangers.isNotEmpty() -> Fault.NotOnList(strangers)
            words.size != 12 && words.size != 24 -> Fault.Count(words.size)
            !valid(words) -> Fault.Mismatch
            else -> null
        }
    }

    private val onList: Set<String> by lazy { MnemonicCode.englishWordlist.toSet() }
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
class Plan(
    val inputs: List<Unspent>, val payments: List<Payment>, val feeSat: Long, val vsize: Int, val changeSat: Long,
    /** Coins that could be spent but did not fit into this one transaction; emptying must be repeated for them. */
    val leftBehind: Int = 0,
) {
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
    /**
     * Base units of more coins than can ever exist (2 billion NORTH); no amount here is larger, and neither is the
     * sum of the coins of one plan (NodeClient.MAX_SAT is the same number). Below it nothing in a plan can wrap
     * around the 64 bits of a Long (review 9, M-3).
     */
    const val MAX_SAT = 2_000_000_000L * 100_000_000L

    private fun checkRate(feeRate: Long) {
        if (feeRate < MIN_FEE_RATE || feeRate > MAX_FEE_RATE) throw PlanError(PlanError.Reason.FEE_RATE)
    }

    /** Coins whose amounts can exist, one by one and all together; anything else is a fault upstream. */
    private fun checkCoins(coins: List<Unspent>) {
        var total = 0L
        for (c in coins) {
            require(c.amountSat in 0..MAX_SAT) { "a coin with an amount that cannot exist" }
            total += c.amountSat
            require(total <= MAX_SAT) { "coins whose amounts cannot exist together" }
        }
    }

    /** What could be sent from the first [MAX_INPUTS] of [coins] to one output, after the fee; for NOT_ENOUGH. */
    private fun available(coins: List<Unspent>, to: ByteArray, feeRate: Long): Long {
        val usable = coins.take(MAX_INPUTS)
        return maxOf(0, usable.sumOf { it.amountSat } - Spend.vsize(usable.size, listOf(to)) * feeRate)
    }

    private fun key(coin: Unspent): String = coin.txid + ":" + coin.vout

    private fun ordered(coins: List<Unspent>): List<Unspent> =
        coins.distinctBy { key(it) }.sortedWith(compareByDescending<Unspent> { it.amountSat }.thenBy { it.txid }.thenBy { it.vout })

    /** The coins in the order they are taken: first the ones that must be spent in any case, then the others, the largest first. */
    private fun arranged(spendable: List<Unspent>, must: List<Unspent>): List<Unspent> {
        val first = must.distinctBy { key(it) }
        if (first.size > MAX_INPUTS) throw PlanError(PlanError.Reason.TOO_MANY_INPUTS, first.sumOf { it.amountSat })
        val keys = first.map { key(it) }.toSet()
        return first + ordered(spendable.filter { key(it) !in keys })
    }

    /**
     * Pays [amountSat] to the script [to]; the rest returns to the script [change].
     *
     * The coins in [must] are spent in any case, however small the amount. That is how a payment is made to
     * stand against an earlier one that spends the same coin: only one of the two can ever be in a block.
     */
    fun pay(spendable: List<Unspent>, to: ByteArray, amountSat: Long, feeRate: Long, change: ByteArray, must: List<Unspent> = emptyList()): Plan {
        checkRate(feeRate)
        if (amountSat < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.AMOUNT_TOO_SMALL)
        val coins = arranged(spendable, must)
        if (coins.isEmpty()) throw PlanError(PlanError.Reason.NOTHING_TO_SPEND)
        checkCoins(coins)
        // an amount larger than all coins that can exist is never covered; checked before anything is subtracted
        if (amountSat > MAX_SAT) throw PlanError(PlanError.Reason.NOT_ENOUGH, available(coins, to, feeRate))
        val least = maxOf(1, must.distinctBy { key(it) }.size)
        var sum = coins.take(least - 1).sumOf { it.amountSat }
        for (n in least..minOf(coins.size, MAX_INPUTS)) {
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
     * Used to empty a paper address into two places at once. One transaction takes at most [MAX_INPUTS] coins;
     * if there are more, the largest ones go now and the caller repeats with what is left.
     */
    fun split(spendable: List<Unspent>, to: ByteArray, keepSat: Long, feeRate: Long, rest: ByteArray): Plan {
        checkRate(feeRate)
        if (keepSat < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.AMOUNT_TOO_SMALL)
        val every = ordered(spendable)
        val coins = every.take(MAX_INPUTS)
        if (coins.isEmpty()) throw PlanError(PlanError.Reason.NOTHING_TO_SPEND)
        checkCoins(coins)
        if (keepSat > MAX_SAT) throw PlanError(PlanError.Reason.NOT_ENOUGH, available(coins, to, feeRate))
        val size = Spend.vsize(coins.size, listOf(to, rest))
        val all = coins.sumOf { it.amountSat }
        val left = all - keepSat - size * feeRate
        if (left < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.NOT_ENOUGH, maxOf(0, all - size * feeRate - MIN_OUTPUT_SAT))
        return Plan(coins, listOf(Payment(to, keepSat), Payment(rest, left)), size * feeRate, size, left, every.size - coins.size)
    }

    /**
     * Sends everything in [spendable] to the script [to], less the fee. Used to empty a wallet or a paper address.
     * One transaction takes at most [MAX_INPUTS] coins; if there are more, the largest ones go now and the
     * caller repeats with what is left (compare the plan's inputs with what was offered). The coins in [must]
     * are always among those that go now.
     */
    fun sweep(spendable: List<Unspent>, to: ByteArray, feeRate: Long, must: List<Unspent> = emptyList()): Plan {
        checkRate(feeRate)
        val every = arranged(spendable, must)
        val coins = every.take(MAX_INPUTS)
        if (coins.isEmpty()) throw PlanError(PlanError.Reason.NOTHING_TO_SPEND)
        checkCoins(coins)
        val size = Spend.vsize(coins.size, listOf(to))
        val amount = coins.sumOf { it.amountSat } - size * feeRate
        if (amount < MIN_OUTPUT_SAT) throw PlanError(PlanError.Reason.NOT_ENOUGH, maxOf(0, amount))
        return Plan(coins, listOf(Payment(to, amount)), size * feeRate, size, 0, every.size - coins.size)
    }
}
