package org.polarka.wallet

import north.core.Chain
import north.core.Qrh
import north.core.Unspent
import north.core.WatchKey
import north.node.Balance
import north.node.ChainInfo
import north.node.Movement
import north.node.NodeClient
import north.node.NodeError
import north.node.ServerClient
import north.node.ServerMove

/**
 * The wallet's look at the chain through a Northbound wallet server, for an owner who runs no node.
 *
 * It gives the picture that [Engine] gives from a node of one's own, as far as such a server can: the coins,
 * the keys in use, the history, and what became of the wallet's waiting payments. It holds no secret. The
 * server is named every address a look covers, so it learns which addresses belong together.
 *
 * What is different from a node:
 * - A coin that a waiting transaction spends is not in the server's list at all, and what a waiting
 *   transaction pays is not in it yet. What is on its way is counted apart ([Snapshot.arrivingSat]).
 * - The history comes summed up by the server, each line against the addresses of one request, and a request
 *   takes a hundred addresses. A wallet with more is asked about in parts, fifty keys of each branch to a
 *   part, and the lines that the parts give for one transaction are put together. That is exact while all
 *   the inputs of a transaction sit in one part. When they sit in more, what the wallet put in still shows
 *   and the fee does not. When a part got back at least as much as it put in, its line does not say what it
 *   put in: such a transaction is left out, and the history says that it is not whole
 *   ([Snapshot.historyWhole]).
 * - Whether a transaction in the history was a block reward is known only while its coin is unspent.
 * - The server does not say which transaction spent a coin. When a coin of one of the wallet's payments is
 *   gone from the list, the payment is known nowhere and nothing else of the wallet waits, a transaction in
 *   a block has spent it; which one is not known, and it is marked [ANOTHER].
 * - The server says whether it knows a transaction, not whether that is in a block. A payment of the
 *   wallet's that the server knows and the history does not list is taken to wait: the index behind the
 *   history may not have caught up with its node. A payment in a block would show the same if it were older
 *   than the lines read for its addresses and had left the wallet no coin.
 * - There is no hash rate and no time between blocks.
 *
 * Everything in one look has to come from the same block. If a block arrives in between, the look is made again.
 */
class ServerEngine(
    private val chain: Chain, private val watched: List<String>, private val server: ServerClient,
    /** The most lines of history read for one part of the addresses. */
    private val most: Int = MAX_LINES,
) {
    private class Keys(
        val xpub: String, val window: Int, val addresses: List<String>,
        val places: Map<String, Pair<Int, Int>>,        // address -> branch and number of its key
        val scripts: Map<String, Pair<Int, Int>>,       // output script in hex -> the same
        val parts: List<List<String>>,                  // the addresses once more, in sets that one request takes
    )

    /** What the parts of some addresses were told about one transaction in a block: a line from every part it touches. */
    private class Told(val txid: String, val height: Int, val lines: MutableList<ServerMove> = ArrayList())

    private class Histories(
        val tip: Int, val tips: Set<String>,
        val inBlocks: List<Told>,
        val waiting: Map<String, List<ServerMove>>,     // the lines of the transactions that wait for a block
        val whole: Boolean,                             // every part gave all its lines
        val unsure: Set<String>,                        // in a block, and a part that did not give all its lines may have had one for it
    )

    private var keys: Keys? = null

    /** The wallet's addresses of both branches: some hundred keys, worked out once and kept from look to look. */
    @Synchronized
    private fun keysOf(watch: WatchKey, window: Int): Keys {
        keys?.let { if (it.xpub == watch.xpub && it.window == window) return it }
        val addresses = ArrayList<String>(2 * window)
        val places = HashMap<String, Pair<Int, Int>>()
        val scripts = HashMap<String, Pair<Int, Int>>()
        for (branch in 0..1) for (index in 0 until window) {
            val address = watch.address(branch, index)
            addresses.add(address)
            places[address] = branch to index
            scripts[Format.hex(watch.script(branch, index))] = branch to index
        }
        // Keys of the same age go into the same part: coins that are spent together tend to sit on such keys,
        // and a transaction whose inputs sit in one part is the one the history is exact about.
        val parts = (0 until window step HALF).map { from ->
            val to = minOf(from + HALF, window)
            addresses.subList(from, to) + addresses.subList(window + from, window + to)
        }
        return Keys(watch.xpub, window, addresses, places, scripts, parts).also { keys = it }
    }

    /**
     * How many keys of each branch a look covers. As with a public node, the server must not be able to make
     * the wallet name address after address to it: the app looks this far by itself, and further only after
     * the owner has said so ([WalletInfo.lookTo]). Keys the wallet already uses are always looked at.
     */
    fun allowed(wallet: WalletInfo): Int = maxOf(AUTO, wallet.lookTo, maxOf(wallet.receiveIndex, wallet.changeIndex) + 1)

    private fun plus(vararg amounts: Long): Long {
        var sum = 0L
        for (amount in amounts) {
            if (amount < 0 || amount > NodeClient.MAX_SAT) throw NodeError(200, null, "the server sent an amount that cannot exist")
            sum += amount
            if (sum > NodeClient.MAX_SAT) throw NodeError(200, null, "the server sent amounts that cannot exist together")
        }
        return sum
    }

    /**
     * The server's history of some addresses, asked for part by part. A transaction that touches several
     * parts has a line from each of them, each summed up against the addresses of its own part.
     */
    private fun histories(parts: List<List<String>>): Histories {
        val tips = HashSet<String>()
        var tip = -1
        val inBlocks = LinkedHashMap<String, Told>()
        val waiting = LinkedHashMap<String, MutableList<ServerMove>>()
        val listedBy = HashMap<String, MutableSet<Int>>()
        val floors = HashMap<Int, Int>()        // for a part that did not give all its lines: the height of the oldest one it gave
        parts.forEachIndexed { number, part ->
            val history = server.history(part, most)
            tips.add(history.tipHash)
            tip = if (tip < 0) history.tip else minOf(tip, history.tip)
            var lowest = Int.MAX_VALUE
            for (line in history.moves) {
                val height = line.height
                if (height == null) {
                    waiting.getOrPut(line.txid) { ArrayList() }.add(line)
                } else {
                    inBlocks.getOrPut(line.txid) { Told(line.txid, height) }.lines.add(line)
                    listedBy.getOrPut(line.txid) { HashSet() }.add(number)
                    lowest = minOf(lowest, height)
                }
            }
            if (!history.complete) floors[number] = lowest
        }
        // A part that stopped before its end says nothing about a transaction at or below the block it
        // stopped in. Without its line the others would be misread: change that came back would show as a gain.
        val unsure = HashSet<String>()
        for (one in inBlocks.values) {
            val listed = listedBy.getValue(one.txid)
            if (floors.any { (number, floor) -> one.height <= floor && number !in listed }) unsure.add(one.txid)
        }
        return Histories(tip, tips, inBlocks.values.toList(), waiting, floors.isEmpty(), unsure)
    }

    /**
     * A transaction in a block as the wallet's own kind of line, from the lines that the parts of the
     * addresses gave for it. Null when the lines do not say what the wallet put in.
     */
    private fun movement(one: Told, rewards: Set<String>): Movement? {
        if (one.lines.any { it.height != one.height }) return null         // answers of two moments
        val time = one.lines.first().time ?: 0
        val received = plus(*one.lines.map { it.receivedSat }.toLongArray())
        val all = one.lines.filter { it.feeSat != null }                               // every input was this part's
        val some = one.lines.filter { it.feeSat == null && it.receivedAt == null }     // some inputs were, others not
        return when {
            // no input was the wallet's: a payment or a block reward that came in
            all.isEmpty() && some.isEmpty() -> Movement(one.txid, one.height, time, one.txid in rewards, received, 0, null)
            // every input sat in one part: what the wallet put in is what went past that part, the fee, and what came back to it
            all.size == 1 && some.isEmpty() -> {
                val line = all.single()
                val fee = all.mapNotNull { it.feeSat }.single()
                Movement(one.txid, one.height, time, false, received, plus(line.sentSat, fee, line.receivedSat), fee)
            }
            // lines that cannot be true together
            all.isNotEmpty() -> null
            // a part that got back at least as much as it put in does not say how much it put in
            some.any { it.sentSat == 0L } -> null
            // inputs in several parts, or inputs that are not the wallet's: each part put in what shows as sent and what came back to it
            else -> Movement(one.txid, one.height, time, false, received, plus(*some.map { plus(it.sentSat, it.receivedSat) }.toLongArray()), null)
        }
    }

    /**
     * What became of the wallet's waiting payments and of those that did not go through, as the server shows it.
     * [held] and [back] get the ones that wait for a block, [tied] the coins that something unknown has tied
     * up, [spentBy] the coins that a transaction in a block has spent.
     */
    private fun judge(
        wallet: WalletInfo, coins: List<Unspent>, inBlocks: Set<String>, waiting: Map<String, List<ServerMove>>,
        held: MutableSet<String>, back: MutableSet<String>, tied: MutableSet<String>, spentBy: MutableMap<String, String>,
    ) {
        val present = coins.map { it.txid + ":" + it.vout }.toSet()
        // a transaction that made one of the wallet's coins is in a block, whatever the history reaches back to
        val made = coins.map { it.txid }.toSet()
        val own = (wallet.pending.map { it.txid } + wallet.dropped.map { it.txid }).toSet()
        // a waiting transaction that spends from the wallet and is none of its payments: made elsewhere with the same words
        val stranger = waiting.any { (txid, lines) -> txid !in own && lines.any { it.receivedAt == null } }
        fun check(txid: String, inputs: Set<String>, waits: MutableSet<String>) {
            when {
                // in a block: the history lists it there, or one of the wallet's coins is of its making
                txid in inBlocks || txid in made -> inputs.forEach { spentBy[it] = txid }
                // waiting: the index shows it, or the node behind the server has it and the index has not caught up
                txid in waiting || server.known(txid) -> waits.add(txid)
                else -> for (coin in inputs) {
                    if (coin in present) continue           // still there, and nothing spends it
                    if (stranger) tied.add(coin) else spentBy[coin] = ANOTHER
                }
            }
        }
        for (payment in wallet.pending.take(Engine.MAX_CHECKED)) check(payment.txid, payment.inputs, held)
        for (payment in wallet.dropped) check(payment.txid, payment.inputs, back)
    }

    /** The transactions in blocks as the wallet's kind of lines, newest first; null for one that cannot be told. */
    private fun movements(told: Histories, rewards: Set<String>): List<Movement?> =
        told.inBlocks.map { if (it.txid in told.unsure) null else movement(it, rewards) }

    /** Oldest first, as a node's history is kept. */
    private fun ordered(lines: List<Movement?>): List<Movement> =
        lines.filterNotNull().sortedWith(compareBy<Movement> { it.height }.thenByDescending { it.txid })

    private fun look(wallet: WalletInfo?): Snapshot {
        val watch = wallet?.watchKey()
        val window = if (wallet == null) 0 else allowed(wallet)
        val tips = HashSet<String>()
        var tip = -1
        var tipHash = ""
        var coins = emptyList<Unspent>()
        var moves = emptyList<Movement>()
        var nextReceive = 0
        var nextChange = 0
        var arriving = 0L
        var whole = true
        val held = HashSet<String>()
        val back = HashSet<String>()
        val tied = HashSet<String>()
        val spentBy = HashMap<String, String>()

        if (wallet != null && watch != null) {
            val keys = keysOf(watch, window)
            val activity = server.activity(keys.addresses)
            tips.add(activity.tipHash)
            for (address in activity.used) {
                val (branch, index) = keys.places.getValue(address)
                if (branch == 0) nextReceive = maxOf(nextReceive, index + 1) else nextChange = maxOf(nextChange, index + 1)
            }
            val found = server.utxos(keys.addresses)
            tips.add(found.tipHash)
            tip = found.tip
            tipHash = found.tipHash
            // the server is trusted for what exists, not for what is the wallet's: every coin must sit on one of its keys
            coins = found.utxos.map { u ->
                val place = keys.scripts[u.script] ?: throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)
                Unspent(u.txid, u.vout, u.amountSat, place.first, place.second, u.height, u.coinbase)
            }
            val rewards = found.utxos.filter { it.coinbase }.map { it.txid }.toSet()
            val told = histories(keys.parts)
            tips.addAll(told.tips)
            val lines = movements(told, rewards)
            whole = told.whole && lines.none { it == null }
            moves = ordered(lines)
            arriving = plus(*told.waiting.values.flatten().map { it.receivedSat }.toLongArray())
            judge(wallet, coins, told.inBlocks.map { it.txid }.toSet(), told.waiting, held, back, tied, spentBy)
        }

        var watchedCoins: Balance? = null
        var watchedMoves = emptyList<Movement>()
        if (watched.isNotEmpty()) {
            val scripts = watched.map { Format.hex(Qrh.scriptForAddress(chain, it)) }.toSet()
            val found = server.utxos(watched)
            tips.add(found.tipHash)
            if (tip < 0) {
                tip = found.tip
                tipHash = found.tipHash
            }
            if (found.utxos.any { it.script !in scripts }) throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)
            watchedCoins = Balance(found.tip, found.utxos)
            watchedCoins.totalSat                                   // refused here if the sum cannot exist
            val rewards = found.utxos.filter { it.coinbase }.map { it.txid }.toSet()
            val told = histories(watched.chunked(ServerClient.MAX_ADDRESSES))
            tips.addAll(told.tips)
            watchedMoves = ordered(movements(told, rewards))
        }
        if (tip < 0) tip = server.tipHeight()

        val info = ChainInfo(Setup.nameOf(chain), tip, tip, false, tipHash)
        val further = watch != null && maxOf(nextReceive, nextChange) + WalletInfo.GAP > window
        return Snapshot(
            info, tip, coins, watchedCoins, moves, watchedMoves, null, null, System.currentTimeMillis(), tied, held,
            nextReceive, nextChange, window, further, tips.size <= 1, back, spentBy, arriving, whole,
        )
    }

    /**
     * One look at the chain for the wallet and the watched addresses. When its parts were read at different
     * blocks it is made again; if blocks keep arriving, the last look is returned and says that it is not
     * steady, so that nothing is judged from it.
     */
    @Synchronized
    fun fetch(wallet: WalletInfo?): Snapshot {
        var seen: Snapshot? = null
        repeat(TRIES) {
            val now = try {
                look(wallet)
            } catch (e: NodeError) {
                if (e.code != ServerClient.MOVED) throw e
                null
            }
            if (now != null) {
                if (now.steady) return now
                seen = now
            }
        }
        return seen ?: throw NodeError(503, null, "blocks kept arriving while the server was asked")
    }

    /**
     * What arrived after block [afterHeight] on the wallet's receiving addresses and on the watched addresses.
     * Change that returns from the wallet's own payments is not counted, nor is what the wallet pays to
     * itself. Returns the height that every answer had reached, and what arrived up to that height.
     */
    fun incomingSince(wallet: WalletInfo?, afterHeight: Int): Pair<Int, List<Incoming>> {
        val top = server.tipHeight()
        if (afterHeight < 0 || afterHeight >= top) return top to emptyList()
        val result = ArrayList<Incoming>()
        var tip = -1
        fun arrived(addresses: List<String>, parts: List<List<String>>, own: Boolean, counts: (String) -> Boolean) {
            val told = histories(parts)
            tip = if (tip < 0) told.tip else minOf(tip, told.tip)
            val fresh = ArrayList<Pair<Told, Long>>()
            for (one in told.inBlocks) {
                if (one.height <= afterHeight || one.txid in told.unsure) continue
                if (one.lines.any { it.receivedAt == null }) continue      // a part of these addresses put something in
                val amount = plus(*one.lines.filter { it.receivedAt?.let(counts) == true }.map { it.receivedSat }.toLongArray())
                if (amount > 0) fresh.add(one to amount)
            }
            if (fresh.isEmpty()) return
            // a block reward is known as one only while its coin is unspent, which a fresh one is
            val rewards = server.utxos(addresses).utxos.filter { it.coinbase }.map { it.txid }.toSet()
            for ((one, amount) in fresh) {
                result.add(Incoming(Movement(one.txid, one.height, one.lines.first().time ?: 0, one.txid in rewards, amount, 0, null), own))
            }
        }
        val watch = wallet?.watchKey()
        if (wallet != null && watch != null) {
            val keys = keysOf(watch, allowed(wallet))
            arrived(keys.addresses, keys.parts, true) { keys.places[it]?.first == 0 }
        }
        if (watched.isNotEmpty()) arrived(watched, watched.chunked(ServerClient.MAX_ADDRESSES), false) { true }
        if (tip < 0) tip = top
        // The answers may be of different heights. What is above the lowest of them is told the next time, so that nothing is told twice.
        return tip to result.filter { it.move.height <= tip }.sortedBy { it.move.height }
    }

    companion object {
        /** Keys of each branch that are looked at without asking the owner. Both branches together fit one request. */
        const val AUTO = 40
        /** Keys of each branch in one part of the addresses. */
        const val HALF = ServerClient.MAX_ADDRESSES / 2
        /** The most lines of history read for one part of the addresses in one look. */
        const val MAX_LINES = 2000
        const val TRIES = 3
        /** In [Snapshot.spentBy]: a transaction in a block has spent the coin, and the server does not say which. */
        const val ANOTHER = "another transaction"
    }
}
