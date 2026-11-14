package io.github.severka_monitor.northwatch

import android.security.keystore.KeyPermanentlyInvalidatedException
import north.core.Account
import north.core.Plan
import north.core.PlanError
import north.core.Planner
import north.core.Qrh
import north.core.SignedSpend
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
import north.node.blockHash
import north.node.blockTime
import north.node.networkHashps
import north.node.relevantBlocks
import north.node.scanDescriptors

/** What went wrong, as data. The screen turns it into a sentence in the user's language. */
data class Problem(val kind: Kind, val detail: String = "") {
    enum class Kind {
        UNREACHABLE, TIMEOUT, BUSY, LOGIN, NOT_ALLOWED, NODE, OTHER_CHAIN, FOREIGN_OUTPUTS, STORE, UNEXPECTED,
        BAD_ADDRESS, BAD_AMOUNT, NOTHING_TO_SPEND, NOT_ENOUGH, FEE_RATE, TOO_MANY_INPUTS, WRONG_WORDS, BAD_WORDS, REJECTED, UNLOCK,
        IN_WAITING_PAYMENT, UNSURE, KEY_GONE, WORDS_EXIST, WALLET_FILE, BAD_PRICE,
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
            is NodeError -> when {
                t.httpStatus == 0 && t.code == NodeError.TIMEOUT -> Problem(Kind.TIMEOUT)
                t.httpStatus == 0 -> Problem(Kind.UNREACHABLE, t.message.orEmpty())
                t.httpStatus == 401 -> Problem(Kind.LOGIN)
                t.httpStatus == 403 -> Problem(Kind.NOT_ALLOWED)
                t.httpStatus == 429 || t.httpStatus == 503 -> Problem(Kind.BUSY)
                else -> Problem(Kind.NODE, t.message.orEmpty())
            }
            is KeyPermanentlyInvalidatedException -> Problem(Kind.KEY_GONE)
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
    /** Coins ("txid:n") that a transaction waiting for a block spends. The node still lists them as unspent. */
    val inWaiting: Set<String> = emptySet(),
    /** Ids of the wallet's own waiting payments that the node holds among the transactions waiting for a block. */
    val held: Set<String> = emptySet(),
    /** The first key of each branch that the chain does not show as used. */
    val nextReceive: Int = 0,
    val nextChange: Int = 0,
) {
    private fun free(coin: Unspent): Boolean = (coin.txid + ":" + coin.vout) !in inWaiting
    val spendableSat: Long get() = NodeClient.total(coins.filter { it.spendableAt(tip) && free(it) }.map { it.amountSat })
    val waitingSat: Long get() = NodeClient.total(coins.filter { it.spendableAt(tip) && !free(it) }.map { it.amountSat })
    val immatureSat: Long get() = NodeClient.total(coins.filter { !it.spendableAt(tip) }.map { it.amountSat })
    val walletSat: Long get() = NodeClient.total(coins.map { it.amountSat })
    val watchedSat: Long get() = watched?.totalSat ?: 0
    val totalSat: Long get() = NodeClient.total(listOf(walletSat, watchedSat))
}

/** What a paper address (its recovery words typed in for one use) holds. */
class PaperFound(val address: String, val tip: Int, val coins: List<Unspent>) {
    val spendable: List<Unspent> get() = coins.filter { it.spendableAt(tip) }
    val spendableSat: Long get() = spendable.sumOf { it.amountSat }
    val immature: List<Unspent> get() = coins.filter { !it.spendableAt(tip) }
    val immatureSat: Long get() = immature.sumOf { it.amountSat }

    /** Blocks until the last reward on the address can be spent; 0 if nothing is maturing. */
    val blocksUntilRipe: Int get() = immature.maxOfOrNull { it.height + Planner.MATURITY - tip } ?: 0
}

/** A reward or a payment that arrived. [own] is false for a watched address. */
class Incoming(val move: Movement, val own: Boolean)

/**
 * Talks to the node for one wallet. It holds no secret: the words are handed in only for the moment of
 * signing and are not kept. It remembers the blocks it has read, so that a refresh asks only for what is new.
 *
 * To a node of one's own the wallet is named by its two public descriptors. To a public node it is named
 * address by address, so that the node never gets the key that shows the whole wallet, past and future.
 */
class Engine(private val setup: Setup, private val node: NodeClient = NodeClient(setup.url, setup.user, setup.password, TIMEOUT_MILLIS)) {
    private val blocks = HashMap<String, ChainBlock>()
    private var scannedTo = -1
    private var scannedWindow = 0
    private var scannedFor = ""
    private var placesFor = ""
    private var placesMap: Map<String, Pair<Int, Int>> = emptyMap()

    /** Every output script of the first [window] keys of both branches, with the key it belongs to. */
    @Synchronized
    private fun places(watch: WatchKey, window: Int): Map<String, Pair<Int, Int>> {
        val key = watch.xpub + "#" + window
        if (key != placesFor) {
            val map = HashMap<String, Pair<Int, Int>>()
            for (branch in 0..1) for (index in 0 until window) map[Format.hex(watch.script(branch, index))] = branch to index
            placesMap = map
            placesFor = key
        }
        return placesMap
    }

    /** How the wallet's keys are named to the node: two descriptors for a node of one's own, single addresses for a public one. */
    private fun reach(watch: WatchKey, window: Int, branches: IntRange = 0..1): List<String> =
        if (!setup.isPublic) branches.map { watch.descriptor(it) }
        else branches.flatMap { branch -> (0 until window).map { "addr(" + watch.address(branch, it) + ")" } }

    /** The coins of an account, each placed on its own key. The node is trusted for what exists, not for what is ours. */
    private fun coinsOf(watch: WatchKey, window: Int): Pair<Int, List<Unspent>> {
        val places = places(watch, window)
        val scan = node.scanDescriptors(reach(watch, window), window)
        val coins = scan.utxos.map { u ->
            val place = places[u.script.lowercase()] ?: throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)
            Unspent(u.txid, u.vout, u.amountSat, place.first, place.second, u.height, u.coinbase)
        }
        NodeClient.total(coins.map { it.amountSat })            // together they must be an amount that can exist
        return scan.tip to coins
    }

    private fun forget(key: String) {
        blocks.clear()
        scannedTo = -1
        scannedWindow = 0
        scannedFor = key
    }

    /**
     * One look at the chain for the wallet and the watched addresses.
     *
     * The history is read first, and with it the wallet's used keys are found: the look is widened until
     * twenty unused keys follow the last used one on both branches. That is what lets a wallet brought back
     * from its words find all of its coins, however long it had been in use.
     */
    @Synchronized
    fun fetch(wallet: WalletInfo?): Snapshot {
        val info = node.chainInfo()
        if (info.chain != setup.chainName) throw EngineError(Problem.Kind.OTHER_CHAIN, info.chain)
        val watch = wallet?.watchKey()

        val watchedScripts = setup.addresses.map { Format.hex(Qrh.scriptForAddress(setup.chain, it)) }.toSet()
        val watched = if (setup.addresses.isEmpty()) null else node.balance(setup.addresses)
        if (watched != null && watched.utxos.any { it.script.lowercase() !in watchedScripts }) throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)
        watched?.totalSat                                       // refused here if the sum cannot exist

        val watchedDescriptors = setup.addresses.map { "addr($it)" }
        val cacheKey = (watch?.xpub ?: "") + "|" + setup.addresses.joinToString(",")
        if (cacheKey != scannedFor) forget(cacheKey)
        // the blocks read earlier must still be on the node's chain; after a long reorganization they are not
        val anchor = blocks.values.filter { it.height <= scannedTo - REORG_DEPTH }.maxByOrNull { it.height }
        if (anchor != null && node.blockHash(anchor.height) != anchor.hash) forget(cacheKey)

        var window = if (wallet == null) 1 else maxOf(wallet.window, scannedWindow)
        var nextReceive = 0
        var nextChange = 0
        if (watch != null || watchedDescriptors.isNotEmpty()) {
            while (true) {
                val descriptors = (if (watch == null) emptyList() else reach(watch, window)) + watchedDescriptors
                // only the blocks that are new since the last look, and the last few again in case the chain
                // reorganized; everything again when more keys are looked for than before
                val tail = maxOf(0, scannedTo - REORG_DEPTH)
                val from = if (window > scannedWindow) 0 else tail
                blocks.values.removeAll { it.height >= tail }
                val named = node.relevantBlocks(descriptors, window, from)
                for (hash in named.hashes) if (hash !in blocks) blocks[hash] = node.block(hash)
                scannedTo = named.toHeight
                scannedWindow = window
                if (watch == null) break
                val paid = History.paidScripts(blocks.values)
                var topReceive = -1
                var topChange = -1
                for ((script, place) in places(watch, window)) {
                    if (script !in paid) continue
                    if (place.first == 0) topReceive = maxOf(topReceive, place.second) else topChange = maxOf(topChange, place.second)
                }
                nextReceive = topReceive + 1
                nextChange = topChange + 1
                val needed = maxOf(nextReceive, nextChange) + WalletInfo.GAP
                if (needed <= window || window >= MAX_WINDOW) break
                window = minOf(MAX_WINDOW, maxOf(needed, window * 2))
            }
        }
        val ordered = blocks.values.sortedBy { it.height }
        val walletScripts = if (watch == null) emptySet() else places(watch, window).keys
        val (tip, coins) = if (watch == null) info.blocks to emptyList() else coinsOf(watch, window)

        // the wallet's own waiting payments: does the node hold them, and which coins are tied up in a waiting transaction
        val held = HashSet<String>()
        val busy = HashSet<String>()
        val present = coins.associateBy { it.txid + ":" + it.vout }
        var asked = 0
        for (payment in wallet?.pending.orEmpty().take(MAX_CHECKED)) {
            if (node.waiting(payment.txid)) held.add(payment.txid)
            for (input in payment.inputs) {
                val coin = present[input] ?: continue
                if (asked++ >= MAX_CHECKED) break
                if (!node.unspent(coin.txid, coin.vout)) busy.add(input)
            }
        }

        val hashps = runCatching { node.networkHashps(SPAN) }.getOrNull()
        val seconds = runCatching {
            if (info.blocks > SPAN) (node.blockTime(info.blocks) - node.blockTime(info.blocks - SPAN)) / SPAN.toDouble() else null
        }.getOrNull()
        return Snapshot(
            info, tip, coins, watched, History.movements(ordered, walletScripts), History.movements(ordered, watchedScripts),
            hashps, seconds, System.currentTimeMillis(), busy, held, nextReceive, nextChange,
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
        val descriptors = (if (watch == null) emptyList() else reach(watch, window, 0..0)) + setup.addresses.map { "addr($it)" }
        if (descriptors.isEmpty()) return info.blocks to emptyList()
        val named = node.relevantBlocks(descriptors, window, afterHeight + 1)
        val fresh = named.hashes.map { node.block(it) }.filter { it.height > afterHeight }
        fun arrived(scripts: Set<String>) = History.movements(fresh, scripts).filter { it.receivedSat > 0 }
        return named.toHeight to (arrived(receiving).map { Incoming(it, true) } + arrived(watchedScripts).map { Incoming(it, false) })
    }

    private fun scriptFor(address: String): ByteArray =
        try { Qrh.scriptForAddress(setup.chain, address.trim()) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_ADDRESS) }

    private fun key(coin: Unspent): String = coin.txid + ":" + coin.vout

    /**
     * A payment from the wallet, worked out from a fresh look at its coins. [amountSat] null means everything.
     *
     * A coin that a transaction waiting for a block already spends is still in the node's list of unspent
     * outputs. Building on it again must not happen: with a higher fee the node would take the new payment in
     * place of the earlier one, which would then never arrive. [used] are the coins the app knows to be tied
     * up; the node is asked about the chosen ones as well, so that a payment made on another phone with the
     * same words, or one this phone has forgotten, is respected too.
     */
    fun plan(wallet: WalletInfo, to: String, amountSat: Long?, feeRate: Long, used: Set<String> = emptySet()): Plan {
        val script = scriptFor(to)
        val watch = wallet.watchKey()
        val (tip, coins) = coinsOf(watch, wallet.window)
        val taken = HashSet(used)
        repeat(PLAN_ROUNDS) {
            val spendable = coins.filter { it.spendableAt(tip) && key(it) !in taken }
            val plan = if (amountSat == null) Planner.sweep(spendable, script, feeRate)
            else Planner.pay(spendable, script, amountSat, feeRate, watch.script(1, wallet.changeIndex))
            val tied = plan.inputs.take(MAX_CHECKED).filter { !node.unspent(it.txid, it.vout) }.map { key(it) }
            if (tied.isEmpty()) return plan
            taken.addAll(tied)
        }
        throw EngineError(Problem.Kind.IN_WAITING_PAYMENT)
    }

    /** Signs with the given words and lets the node check the result. Nothing is sent here. */
    fun sign(expectedXpub: String, plan: Plan, words: List<String>): SignedSpend {
        val account = try { Account.fromWords(words, setup.chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        if (account.watchKey().xpub != expectedXpub) throw EngineError(Problem.Kind.WRONG_WORDS)
        val signed = plan.sign(account)
        val verdict = node.testAccept(signed.hex)
        if (!verdict.allowed) throw EngineError(Problem.Kind.REJECTED, verdict.rejectReason.orEmpty())
        if (verdict.feeSat != plan.feeSat) throw EngineError(Problem.Kind.REJECTED, "fee")
        return signed
    }

    private fun onItsWay(e: NodeError): Boolean {
        val said = e.message.orEmpty().lowercase()
        return e.code == -27 || "already in" in said || "already known" in said || "txn-already" in said || "same-nonwitness-data" in said
    }

    /**
     * Hands a signed transaction to the node. True: the node has it. False: that could not be found out (the
     * node did not answer, or answered with something else than the transaction's id). A refusal is thrown.
     *
     * A lost answer does not mean that nothing was sent. So after an error on the way the node is asked
     * whether it holds the transaction, and the same transaction is handed over again; doing that twice
     * cannot pay twice, because it is the same transaction.
     */
    fun deliver(signed: SignedSpend): Boolean {
        for (attempt in 1..DELIVERY_TRIES) {
            try {
                if (node.broadcast(signed.hex) == signed.txid) return true
                return runCatching { node.waiting(signed.txid) }.getOrDefault(false)
            } catch (e: NodeError) {
                when {
                    onItsWay(e) -> return true
                    e.httpStatus == 0 || e.httpStatus == 429 || e.httpStatus == 503 -> {
                        if (runCatching { node.waiting(signed.txid) }.getOrDefault(false)) return true
                        if (attempt < DELIVERY_TRIES) runCatching { Thread.sleep(DELIVERY_PAUSE_MILLIS) }
                    }
                    else -> throw e
                }
            }
        }
        return false
    }

    /**
     * Signs, lets the node check the result, and sends it. Returns the id of the transaction that was signed;
     * the node's own answer is not taken for it.
     */
    fun send(expectedXpub: String, plan: Plan, words: List<String>): String {
        val signed = sign(expectedXpub, plan, words)
        deliver(signed)
        return signed.txid
    }

    /**
     * Looks at what the account of some recovery words holds (a paper address). The words are not kept.
     * [spent] are coins already sent away in an earlier part of the same emptying.
     */
    fun paperLook(words: List<String>, spent: Set<String> = emptySet()): PaperFound {
        val account = try { Account.fromWords(words, setup.chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        val (tip, coins) = coinsOf(account.watchKey(), WalletInfo.GAP)
        var free = coins.filter { key(it) !in spent }
        // coins that a waiting transaction already spends are left out, as far as it is reasonable to ask one by one
        if (free.size <= MAX_CHECKED) free = free.filter { !it.spendableAt(tip) || node.unspent(it.txid, it.vout) }
        return PaperFound(account.address(0, 0), tip, free)
    }

    /**
     * Empties a paper address. With [keepSat] null everything goes to [to]. Otherwise [keepSat] goes to [to]
     * and the rest to [restTo], for example a small amount to the phone and the rest to a new paper address.
     * One transaction takes at most a thousand coins; the plan says how many are left for the next one.
     */
    fun paperPlan(found: PaperFound, to: String, feeRate: Long, keepSat: Long? = null, restTo: String? = null): Plan {
        val script = scriptFor(to)
        if (keepSat == null) return Planner.sweep(found.spendable, script, feeRate)
        val rest = scriptFor(restTo ?: throw EngineError(Problem.Kind.BAD_ADDRESS))
        return Planner.split(found.spendable, script, keepSat, feeRate, rest)
    }

    fun paperSign(found: PaperFound, plan: Plan, words: List<String>): SignedSpend {
        val account = try { Account.fromWords(words, setup.chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        if (account.address(0, 0) != found.address) throw EngineError(Problem.Kind.WRONG_WORDS)
        return sign(account.watchKey().xpub, plan, words)
    }

    fun paperSend(found: PaperFound, plan: Plan, words: List<String>): String {
        val signed = paperSign(found, plan, words)
        deliver(signed)
        return signed.txid
    }

    companion object {
        const val TIMEOUT_MILLIS = 30_000
        const val REORG_DEPTH = 10
        const val SPAN = 120
        /** The most keys of one branch that are ever looked for. */
        const val MAX_WINDOW = 5000
        /** The most coins the node is asked about one by one in a single step. */
        const val MAX_CHECKED = 100
        const val PLAN_ROUNDS = 6
        const val DELIVERY_TRIES = 3
        const val DELIVERY_PAUSE_MILLIS = 1500L
    }
}
