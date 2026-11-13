package io.github.severka_monitor.northwatch

import north.core.Account
import north.core.Plan
import north.core.PlanError
import north.core.Planner
import north.core.Qrh
import north.core.Unspent
import north.core.WatchKey
import north.node.Balance
import north.node.ChainBlock
import north.node.ChainInfo
import north.node.History
import north.node.Movement
import north.node.NodeClient
import north.node.NodeError
import north.node.block
import north.node.blockTime
import north.node.networkHashps
import north.node.relevantBlocks
import north.node.scanDescriptors

/** What went wrong, as data. The screen turns it into a sentence in the user's language. */
data class Problem(val kind: Kind, val detail: String = "") {
    enum class Kind {
        UNREACHABLE, LOGIN, NOT_ALLOWED, NODE, OTHER_CHAIN, FOREIGN_OUTPUTS, STORE, UNEXPECTED,
        BAD_ADDRESS, BAD_AMOUNT, NOTHING_TO_SPEND, NOT_ENOUGH, FEE_RATE, TOO_MANY_INPUTS, WRONG_WORDS, BAD_WORDS, REJECTED, UNLOCK,
    }

    companion object {
        fun of(t: Throwable): Problem = when (t) {
            is EngineError -> Problem(t.kind, t.detail)
            is PlanError -> when (t.reason) {
                PlanError.Reason.NOTHING_TO_SPEND -> Problem(Kind.NOTHING_TO_SPEND)
                PlanError.Reason.AMOUNT_TOO_SMALL -> Problem(Kind.BAD_AMOUNT)
                PlanError.Reason.NOT_ENOUGH -> Problem(Kind.NOT_ENOUGH, Format.coins(t.availableSat))
                PlanError.Reason.FEE_RATE -> Problem(Kind.FEE_RATE)
                PlanError.Reason.TOO_MANY_INPUTS -> Problem(Kind.TOO_MANY_INPUTS)
            }
            is NodeError -> when (t.httpStatus) {
                0 -> Problem(Kind.UNREACHABLE, t.message.orEmpty())
                401 -> Problem(Kind.LOGIN)
                403 -> Problem(Kind.NOT_ALLOWED)
                else -> Problem(Kind.NODE, t.message.orEmpty())
            }
            else -> Problem(Kind.UNEXPECTED, t.javaClass.simpleName)
        }
    }
}

class EngineError(val kind: Problem.Kind, val detail: String = "") : Exception(kind.name + " " + detail)

/** One look at the chain. Everything the screens show comes from here. */
data class Snapshot(
    val info: ChainInfo,
    val tip: Int,
    val coins: List<Unspent>,               // the wallet's own coins; empty without a wallet
    val watched: Balance?,                  // the watched addresses
    val moves: List<Movement>,              // history of the wallet, oldest first
    val watchedMoves: List<Movement>,       // history of the watched addresses
    val hashps: Double?,                    // the network's hash rate
    val secondsPerBlock: Double?,           // average over the last 120 blocks
    val atMillis: Long,
) {
    val spendableSat: Long get() = coins.filter { it.spendableAt(tip) }.sumOf { it.amountSat }
    val immatureSat: Long get() = coins.filter { !it.spendableAt(tip) }.sumOf { it.amountSat }
    val walletSat: Long get() = coins.sumOf { it.amountSat }
    val watchedSat: Long get() = watched?.totalSat ?: 0
    val totalSat: Long get() = walletSat + watchedSat
}

/** What a paper address (its recovery words typed in for one use) holds. */
class PaperFound(val address: String, val tip: Int, val coins: List<Unspent>) {
    val spendable: List<Unspent> get() = coins.filter { it.spendableAt(tip) }
    val spendableSat: Long get() = spendable.sumOf { it.amountSat }
    val immatureSat: Long get() = coins.sumOf { it.amountSat } - spendableSat
}

/** A reward or a payment that arrived. [own] is false for a watched address. */
class Incoming(val move: Movement, val own: Boolean)

/**
 * Talks to the node for one wallet. It holds no secret: the words are handed in only for the moment of
 * signing and are not kept. It remembers the blocks it has read, so that a refresh asks only for what is new.
 */
class Engine(private val setup: Setup, private val node: NodeClient = NodeClient(setup.url, setup.user, setup.password, TIMEOUT_MILLIS)) {
    private val blocks = HashMap<String, ChainBlock>()
    private var scannedTo = -1
    private var scannedFor = ""

    private fun places(watch: WatchKey, window: Int): Map<String, Pair<Int, Int>> {
        val map = HashMap<String, Pair<Int, Int>>()
        for (branch in 0..1) for (index in 0 until window) map[Format.hex(watch.script(branch, index))] = branch to index
        return map
    }

    /** The coins of an account, each placed on its own key. The node is trusted for what exists, not for what is ours. */
    private fun coinsOf(watch: WatchKey, window: Int, places: Map<String, Pair<Int, Int>> = places(watch, window)): Pair<Int, List<Unspent>> {
        val scan = node.scanDescriptors(listOf(watch.descriptor(0), watch.descriptor(1)), window)
        val coins = scan.utxos.map { u ->
            val place = places[u.script.lowercase()]
            if (place == null || u.amountSat < 0) throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)
            Unspent(u.txid, u.vout, u.amountSat, place.first, place.second, u.height, u.coinbase)
        }
        return scan.tip to coins
    }

    fun fetch(wallet: WalletInfo?): Snapshot {
        val info = node.chainInfo()
        if (info.chain != setup.chainName) throw EngineError(Problem.Kind.OTHER_CHAIN, info.chain)
        val watch = wallet?.watchKey()
        val window = wallet?.window ?: 1
        val walletPlaces = if (watch == null) emptyMap() else places(watch, window)
        val (tip, coins) = if (watch == null) info.blocks to emptyList() else coinsOf(watch, window, walletPlaces)

        val watchedScripts = setup.addresses.map { Format.hex(Qrh.scriptForAddress(setup.chain, it)) }.toSet()
        val watched = if (setup.addresses.isEmpty()) null else node.balance(setup.addresses)
        if (watched != null && watched.utxos.any { it.script.lowercase() !in watchedScripts || it.amountSat < 0 }) throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)

        // history: only the blocks that are new since the last look, and the last few again in case the chain reorganized
        val descriptors = (if (watch == null) emptyList() else listOf(watch.descriptor(0), watch.descriptor(1))) + setup.addresses.map { "addr($it)" }
        val cacheKey = descriptors.joinToString("|") + "#" + window
        if (cacheKey != scannedFor) {
            blocks.clear()
            scannedTo = -1
            scannedFor = cacheKey
        }
        val from = maxOf(0, scannedTo - REORG_DEPTH)
        blocks.values.removeAll { it.height >= from }
        val named = node.relevantBlocks(descriptors, window, from)
        for (hash in named.hashes) if (hash !in blocks) blocks[hash] = node.block(hash)
        scannedTo = named.toHeight
        val ordered = blocks.values.sortedBy { it.height }

        val hashps = runCatching { node.networkHashps(SPAN) }.getOrNull()
        val seconds = runCatching {
            if (info.blocks > SPAN) (node.blockTime(info.blocks) - node.blockTime(info.blocks - SPAN)) / SPAN.toDouble() else null
        }.getOrNull()
        return Snapshot(
            info, tip, coins, watched, History.movements(ordered, walletPlaces.keys), History.movements(ordered, watchedScripts),
            hashps, seconds, System.currentTimeMillis(),
        )
    }

    /**
     * What arrived after block [afterHeight]: rewards and payments to the wallet's receiving addresses and to
     * the watched addresses. Change that returns from the wallet's own payments is not counted. Returns the
     * height the node has reached together with the list. Only the new blocks are read.
     */
    fun incomingSince(wallet: WalletInfo?, afterHeight: Int): Pair<Int, List<Incoming>> {
        val info = node.chainInfo()
        if (info.chain != setup.chainName) throw EngineError(Problem.Kind.OTHER_CHAIN, info.chain)
        if (afterHeight < 0 || afterHeight >= info.blocks) return info.blocks to emptyList()
        val watch = wallet?.watchKey()
        val window = wallet?.window ?: 1
        val receiving = if (watch == null) emptySet() else (0 until window).map { Format.hex(watch.script(0, it)) }.toSet()
        val watchedScripts = setup.addresses.map { Format.hex(Qrh.scriptForAddress(setup.chain, it)) }.toSet()
        val descriptors = (if (watch == null) emptyList() else listOf(watch.descriptor(0))) + setup.addresses.map { "addr($it)" }
        if (descriptors.isEmpty()) return info.blocks to emptyList()
        val named = node.relevantBlocks(descriptors, window, afterHeight + 1)
        val fresh = named.hashes.map { node.block(it) }.filter { it.height > afterHeight }
        fun arrived(scripts: Set<String>) = History.movements(fresh, scripts).filter { it.receivedSat > 0 }
        return named.toHeight to (arrived(receiving).map { Incoming(it, true) } + arrived(watchedScripts).map { Incoming(it, false) })
    }

    private fun scriptFor(address: String): ByteArray =
        try { Qrh.scriptForAddress(setup.chain, address.trim()) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_ADDRESS) }

    /** A payment from the wallet, worked out from a fresh look at its coins. [amountSat] null means everything. */
    fun plan(wallet: WalletInfo, to: String, amountSat: Long?, feeRate: Long, used: Set<String> = emptySet()): Plan {
        val script = scriptFor(to)
        val watch = wallet.watchKey()
        val (tip, coins) = coinsOf(watch, wallet.window)
        // coins of a payment that still waits for its block look unspent to the node; they must not be spent twice
        val spendable = coins.filter { it.spendableAt(tip) && (it.txid + ":" + it.vout) !in used }
        return if (amountSat == null) Planner.sweep(spendable, script, feeRate)
        else Planner.pay(spendable, script, amountSat, feeRate, watch.script(1, wallet.changeIndex))
    }

    /** Signs with the given words, lets the node check the result, and only then sends it. Returns the transaction id. */
    fun send(expectedXpub: String, plan: Plan, words: List<String>): String {
        val account = try { Account.fromWords(words, setup.chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        if (account.watchKey().xpub != expectedXpub) throw EngineError(Problem.Kind.WRONG_WORDS)
        val signed = plan.sign(account)
        val verdict = node.testAccept(signed.hex)
        if (!verdict.allowed) throw EngineError(Problem.Kind.REJECTED, verdict.rejectReason.orEmpty())
        if (verdict.feeSat != plan.feeSat) throw EngineError(Problem.Kind.REJECTED, "fee")
        return node.broadcast(signed.hex)
    }

    /** Looks at what the account of some recovery words holds (a paper address). The words are not kept. */
    fun paperLook(words: List<String>): PaperFound {
        val account = try { Account.fromWords(words, setup.chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        val (tip, coins) = coinsOf(account.watchKey(), WalletInfo.GAP)
        return PaperFound(account.address(0, 0), tip, coins)
    }

    /**
     * Empties a paper address. With [keepSat] null everything goes to [to]. Otherwise [keepSat] goes to [to]
     * and the rest to [restTo], for example a small amount to the phone and the rest to a new paper address.
     */
    fun paperPlan(found: PaperFound, to: String, feeRate: Long, keepSat: Long? = null, restTo: String? = null): Plan {
        val script = scriptFor(to)
        if (keepSat == null) return Planner.sweep(found.spendable, script, feeRate)
        val rest = scriptFor(restTo ?: throw EngineError(Problem.Kind.BAD_ADDRESS))
        return Planner.split(found.spendable, script, keepSat, feeRate, rest)
    }

    fun paperSend(found: PaperFound, plan: Plan, words: List<String>): String {
        val account = try { Account.fromWords(words, setup.chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        if (account.address(0, 0) != found.address) throw EngineError(Problem.Kind.WRONG_WORDS)
        return send(account.watchKey().xpub, plan, words)
    }

    companion object {
        const val TIMEOUT_MILLIS = 30_000
        const val REORG_DEPTH = 10
        const val SPAN = 120
    }
}
