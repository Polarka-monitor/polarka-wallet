package org.polarka.wallet

import north.core.Account
import north.core.Chain
import north.core.Plan
import north.core.Planner
import north.core.Qrh
import north.core.SignedSpend
import north.core.Unspent
import north.core.WatchKey
import north.node.Balance
import north.node.ChainInfo
import north.node.Movement
import north.node.NodeClient
import north.node.NodeError
import north.node.ServerClient
import north.node.ServerMove
import north.node.Utxo

/**
 * The wallet's look at the chain through a Northbound wallet server, for an owner who runs no node.
 *
 * It gives the picture that [Engine] gives from a node of one's own, as far as such a server can: the coins,
 * the keys in use, the history, and what became of the wallet's waiting payments. It holds no secret. The
 * server is named every address a look covers, so it learns which addresses belong together.
 *
 * What is different from a node:
 * - A coin that a waiting transaction spends is not in the server's list at all, and what a waiting
 *   transaction pays is not in it yet. What is on its way is counted apart ([Snapshot.arrivingSat]): for a
 *   payment from somebody else what the server sums it up to, and for a payment of the wallet's own what it
 *   gives back, worked out from what the wallet wrote down that it put in ([Pending.inputSat]).
 * - The history comes summed up by the server. Each line says what a transaction did to the addresses of one
 *   request: what it paid to them less what it took from them. A request takes a hundred addresses, so a
 *   wallet with more is asked about in parts, fifty keys of each branch to a part, and the lines that the
 *   parts give for one transaction are added up, which is exact. What the lines do not say is how a movement
 *   is made up: a payment shows as what the wallet lost by it, and the fee of the whole transaction stands
 *   beside it when the wallet lost at least that much. A history that is longer than the app reads says that
 *   it is not whole ([Snapshot.historyWhole]).
 * - A block reward is known by having no fee.
 * - The server does not say which transaction spent a coin, and a coin that is missing from its list proves
 *   nothing: the server may be behind its node, or may not show the truth. Nothing is concluded from a
 *   missing coin. A payment of the wallet's counts as beaten only when the server shows, in a block, another
 *   payment of this wallet that spends one of its coins. When a coin went another way (from another phone
 *   with the same words), the payment stays among those that did not go through until its owner forgets it.
 * - The server says whether it knows a transaction, not whether that is in a block. A payment of the
 *   wallet's that the server knows and the history does not list is taken to wait: the index behind the
 *   history may not have caught up with its node. A payment in a block would show the same if it were older
 *   than the lines read for its addresses and had left the wallet no coin.
 * - There is no hash rate and no time between blocks.
 * - The server does not say which network it works for. One that works for another does not take the
 *   wallet's addresses, and by that it is known ([Problem.Kind.OTHER_CHAIN]).
 * - A payment is signed on the phone and handed over afterwards ([deliver]); the node behind the server checks
 *   it at the hand-over, not at the signing. The server answers every failed hand-over alike, so after one
 *   the outcome is asked for and, if nobody knows, stays "not known" until a look settles it. From the first
 *   question of the hand-over on the server has the whole signed transaction, and it could keep it whatever
 *   it answers. A payment is therefore never forgotten on the server's word ([ownersNode]).
 *
 * Everything in one look has to come from the same picture of the chain: the same block and, with a server
 * that numbers them, the same waiting transactions. If that changes in between, the look is made again.
 */
class ServerEngine(
    private val chain: Chain, private val watched: List<String>, private val server: ServerClient,
    /** The most lines of history read for one part of the addresses. */
    private val most: Int = MAX_LINES,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() },
    /** The longest the check before a hand-over may take, and the shortest time any question of the hand-over is given. */
    private val checkMillis: Long = CHECK_MILLIS,
    private val leastMillis: Long = MIN_QUESTION_MILLIS,
) : Gateway {
    private class Keys(
        val xpub: String, val window: Int, val addresses: List<String>,
        val places: Map<String, Pair<Int, Int>>,        // address -> branch and number of its key
        val scripts: Map<String, Pair<Int, Int>>,       // output script in hex -> the same
        val parts: List<List<String>>,                  // the addresses once more, in sets that one request takes
    )

    /** What the parts of some addresses were told about one transaction in a block: a line from every part it touches. */
    private class Told(val txid: String, val height: Int, val lines: MutableList<ServerMove> = ArrayList())

    private class Histories(
        val tip: Int, val tips: Set<String>,            // the pictures of the chain that the parts were read from
        val inBlocks: List<Told>,
        val waiting: Map<String, List<ServerMove>>,     // the lines of the transactions that wait for a block
        val whole: Boolean,                             // every part gave all its lines
        val unsure: Set<String>,                        // in a block, and a part that did not give all its lines may have had one for it
    )

    private var keys: Keys? = null

    /** A wallet server is somebody else's: what it was shown it may keep, whatever it answered. */
    override val ownersNode: Boolean get() = false

    /** The addresses of both branches of an account: some hundred keys. The wallet's own are worked out once and kept from look to look. */
    @Synchronized
    private fun keysOf(watch: WatchKey, window: Int, kept: Boolean = true): Keys {
        if (kept) keys?.let { if (it.xpub == watch.xpub && it.window == window) return it }
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
        // Only the wallet's own table is kept; a paper address that is looked at in between gets one that is thrown away.
        return Keys(watch.xpub, window, addresses, places, scripts, parts).also { if (kept) keys = it }
    }

    /**
     * How many keys of each branch a look covers. As with a public node, the server must not be able to make
     * the wallet name address after address to it: the app looks this far by itself, and further only after
     * the owner has said so ([WalletInfo.lookTo]). Keys the wallet already uses are always looked at.
     */
    override fun allowed(wallet: WalletInfo): Int = maxOf(AUTO, wallet.lookTo, maxOf(wallet.receiveIndex, wallet.changeIndex) + 1)

    /** The keys of each branch that every look covers: all that the app may look at for this wallet. */
    override fun windowOf(wallet: WalletInfo): Int = allowed(wallet)

    private fun key(coin: Unspent): String = coin.txid + ":" + coin.vout

    /**
     * What the engine makes of two answers of the server. More coins on the addresses than are read for one
     * question is said as that. A server that will not take what the wallet asks (HTTP 400) is about the
     * addresses themselves: it works for another network than the setup names, which is what it says of
     * addresses it does not know, or it is not a server of this kind. That is said as one thing, with the
     * server's words.
     */
    private inline fun <T> asked(what: () -> T): T = try {
        what()
    } catch (e: NodeError) {
        if (e.code == ServerClient.TOO_MANY) throw EngineError(Problem.Kind.TOO_MANY_COINS, ServerClient.MAX_COINS.toString())
        if (e.httpStatus != 400) throw e
        throw EngineError(Problem.Kind.OTHER_CHAIN, e.message.orEmpty())
    }

    /** One picture of the chain: a block and, when the server numbers them, its waiting transactions. */
    private fun at(tipHash: String, revision: Long?): String = if (revision == null) tipHash else "$tipHash/$revision"

    private fun scriptFor(address: String): ByteArray =
        try { Qrh.scriptForAddress(chain, address.trim()) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_ADDRESS) }

    /** The server's coins, each on the key it sits on. The server is trusted for what exists, not for whose it is: every coin must sit on one of these keys. */
    private fun placed(utxos: List<Utxo>, keys: Keys): List<Unspent> = utxos.map { u ->
        val place = keys.scripts[u.script] ?: throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)
        Unspent(u.txid, u.vout, u.amountSat, place.first, place.second, u.height, u.coinbase)
    }

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
     * What some lines sum up to: the lines that the parts of the addresses gave for one transaction. Each is
     * what the transaction paid to the addresses of its part less what it took from them.
     */
    private fun sumOf(lines: List<ServerMove>): Long {
        var sum = 0L
        for (line in lines) {
            sum += line.netSat          // every line is within the bounds of an amount, so the sum cannot run over
            if (sum > NodeClient.MAX_SAT || sum < -NodeClient.MAX_SAT) throw NodeError(200, null, "the server sent amounts that cannot exist together")
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
            tips.add(at(history.tipHash, history.revision))
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
        // stopped in. Without its line the sum would be wrong: change that came back would show as a gain.
        val unsure = HashSet<String>()
        for (one in inBlocks.values) {
            val listed = listedBy.getValue(one.txid)
            if (floors.any { (number, floor) -> one.height <= floor && number !in listed }) unsure.add(one.txid)
        }
        return Histories(tip, tips, inBlocks.values.toList(), waiting, floors.isEmpty(), unsure)
    }

    /**
     * A transaction in a block as the wallet's own kind of line, from the lines that the parts of the
     * addresses gave for it. Together they say what the transaction did to the wallet. Null when the lines
     * cannot be true together.
     */
    private fun movement(one: Told): Movement? {
        val first = one.lines.first()
        if (one.lines.any { it.height != one.height || it.feeSat != first.feeSat }) return null       // answers of two moments, or of two minds
        val time = first.time ?: 0
        val gain = sumOf(one.lines)
        val fee = first.feeSat
        return when {
            // a block reward has no fee and takes from nobody
            fee == null -> if (gain >= 0) Movement(one.txid, one.height, time, true, gain, 0, null) else null
            // the wallet has more than before: a payment that came in
            gain >= 0 -> Movement(one.txid, one.height, time, false, gain, 0, null)
            // The wallet has less than before. The fee is that of the whole transaction; it stands beside the
            // loss when the wallet lost at least that much, and so can have paid it.
            else -> Movement(one.txid, one.height, time, false, 0, -gain, fee.takeIf { it <= -gain })
        }
    }

    /**
     * What the waiting transactions are about to bring to the wallet. A payment from somebody else brings
     * what the server sums it up to. A payment of the wallet's own shows there as a loss, and its coins are
     * gone from the server's list at once: what it gives back is what the wallet wrote down that it put in,
     * less that loss. For a payment of the wallet's that has no such note, nothing is counted.
     */
    private fun arriving(wallet: WalletInfo, waiting: Map<String, List<ServerMove>>): Long {
        val put = HashMap<String, Long>()
        for (payment in wallet.pending) put[payment.txid] = payment.inputSat
        for (payment in wallet.dropped) put[payment.txid] = payment.inputSat
        var sum = 0L
        for ((txid, lines) in waiting) {
            val gain = sumOf(lines)
            val known = put[txid] ?: 0
            sum = plus(sum, if (gain >= 0) gain else if (known > 0) maxOf(0, known + gain) else 0)
        }
        return sum
    }

    /**
     * What became of the wallet's waiting payments and of those that did not go through, as the server shows it.
     * [held] and [back] get the ones that wait for a block, [tied] the coins that something waiting may be
     * spending, [spentBy] the coins that a payment of this wallet has spent in a block.
     *
     * Nothing is concluded from a coin that the server does not list. It does not say what spent a coin, and
     * a coin that is left out is no proof that a block spent it: the server may be behind its node, or it may
     * not show the truth, and it is the one who was handed the payment. A payment is therefore beaten only by
     * another payment of this wallet that the server shows in a block, which is something the server has to
     * say and cannot merely leave out. A payment that the server neither knows nor shows in a block becomes,
     * and stays, one that did not go through, and the next payment is built on one of its coins.
     */
    private fun judge(
        wallet: WalletInfo, coins: List<Unspent>, inBlocks: Set<String>, waiting: Map<String, List<ServerMove>>,
        held: MutableSet<String>, back: MutableSet<String>, tied: MutableSet<String>, spentBy: MutableMap<String, String>,
    ) {
        val present = coins.map { it.txid + ":" + it.vout }.toSet()
        // a transaction that made one of the wallet's coins is in a block, whatever the history reaches back to
        val made = coins.map { it.txid }.toSet()
        val own = (wallet.pending.map { it.txid } + wallet.dropped.map { it.txid }).toSet()
        // a waiting transaction that takes from the wallet and is none of its payments: made elsewhere with the same words
        val stranger = waiting.any { (txid, lines) -> txid !in own && lines.any { it.netSat < 0 } }
        val busy = HashSet<String>()                // the coins of the wallet's own payments that wait for a block
        val unknown = ArrayList<Set<String>>()      // the coins of the payments that are in no block and that the server does not know
        fun check(txid: String, inputs: Set<String>, waits: MutableSet<String>) {
            when {
                // in a block: the history lists it there, or one of the wallet's coins is of its making
                txid in inBlocks || txid in made -> inputs.forEach { spentBy[it] = txid }
                // waiting: the index shows it, or the node behind the server has it and the index has not caught up
                txid in waiting || server.known(txid) -> {
                    waits.add(txid)
                    busy.addAll(inputs)
                }
                else -> unknown.add(inputs)
            }
        }
        for (payment in wallet.pending.take(Engine.MAX_CHECKED)) check(payment.txid, payment.inputs, held)
        for (payment in wallet.dropped) check(payment.txid, payment.inputs, back)
        // A coin of such a payment that is not listed while something waits that may be spending it (another
        // payment of this wallet, or a transaction made elsewhere with the same words) is tied up: the next
        // blocks will tell. A missing coin with nothing waiting says nothing at all.
        for (inputs in unknown) for (coin in inputs) if (coin !in present && coin !in spentBy && (stranger || coin in busy)) tied.add(coin)
    }

    /** The transactions in blocks as the wallet's kind of lines, newest first; null for one that cannot be told. */
    private fun movements(told: Histories): List<Movement?> =
        told.inBlocks.map { if (it.txid in told.unsure) null else movement(it) }

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
            tips.add(at(activity.tipHash, activity.revision))
            for (address in activity.used) {
                val (branch, index) = keys.places.getValue(address)
                if (branch == 0) nextReceive = maxOf(nextReceive, index + 1) else nextChange = maxOf(nextChange, index + 1)
            }
            val found = server.utxos(keys.addresses)
            tips.add(at(found.tipHash, found.revision))
            tip = found.tip
            tipHash = found.tipHash
            coins = placed(found.utxos, keys)
            val told = histories(keys.parts)
            tips.addAll(told.tips)
            val lines = movements(told)
            whole = told.whole && lines.none { it == null }
            moves = ordered(lines)
            arriving = arriving(wallet, told.waiting)
            judge(wallet, coins, told.inBlocks.map { it.txid }.toSet(), told.waiting, held, back, tied, spentBy)
        }

        var watchedCoins: Balance? = null
        var watchedMoves = emptyList<Movement>()
        if (watched.isNotEmpty()) {
            val scripts = watched.map { Format.hex(Qrh.scriptForAddress(chain, it)) }.toSet()
            val found = server.utxos(watched)
            tips.add(at(found.tipHash, found.revision))
            if (tip < 0) {
                tip = found.tip
                tipHash = found.tipHash
            }
            if (found.utxos.any { it.script !in scripts }) throw EngineError(Problem.Kind.FOREIGN_OUTPUTS)
            watchedCoins = Balance(found.tip, found.utxos)
            watchedCoins.totalSat                                   // refused here if the sum cannot exist
            val told = histories(watched.chunked(ServerClient.MAX_ADDRESSES))
            tips.addAll(told.tips)
            watchedMoves = ordered(movements(told))
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
     * One look at the chain for the wallet and the watched addresses. When its parts were not read from the
     * same picture of the chain it is made again; if the chain keeps changing, the last look is returned and
     * says that it is not steady, so that nothing is judged from it.
     */
    @Synchronized
    override fun fetch(wallet: WalletInfo?): Snapshot {
        var seen: Snapshot? = null
        repeat(TRIES) {
            val now = try {
                asked { look(wallet) }
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
     * What the wallet and the watched addresses gained in blocks after [afterHeight]: every transaction that
     * left them with more than they had. A payment of the wallet's own is a loss, its change included, and
     * what the wallet pays to itself costs it the fee: neither is told. Returns the height that every answer
     * had reached, and what arrived up to that height.
     */
    override fun incomingSince(wallet: WalletInfo?, afterHeight: Int): Pair<Int, List<Incoming>> = asked { since(wallet, afterHeight) }

    private fun since(wallet: WalletInfo?, afterHeight: Int): Pair<Int, List<Incoming>> {
        val top = server.tipHeight()
        if (afterHeight < 0 || afterHeight >= top) return top to emptyList()
        val result = ArrayList<Incoming>()
        var tip = -1
        fun arrived(parts: List<List<String>>, own: Boolean) {
            val told = histories(parts)
            tip = if (tip < 0) told.tip else minOf(tip, told.tip)
            for (one in told.inBlocks) {
                if (one.height <= afterHeight || one.txid in told.unsure) continue
                val move = movement(one) ?: continue
                if (move.netSat > 0) result.add(Incoming(move, own))
            }
        }
        val watch = wallet?.watchKey()
        if (wallet != null && watch != null) arrived(keysOf(watch, allowed(wallet)).parts, true)
        if (watched.isNotEmpty()) arrived(watched.chunked(ServerClient.MAX_ADDRESSES), false)
        if (tip < 0) tip = top
        // The answers may be of different heights. What is above the lowest of them is told the next time, so that nothing is told twice.
        return tip to result.filter { it.move.height <= tip }.sortedBy { it.move.height }
    }

    /**
     * A payment from the wallet, worked out from a fresh list of its coins. [amountSat] null means everything.
     *
     * A coin that a waiting transaction spends is not in the server's list, whoever made that transaction, so
     * a payment is not built on it a second time. [used] are the coins the app itself knows to be tied up;
     * they are left out as well, for a server whose index is a moment behind its node. As with a node, the
     * payment spends one coin of every payment that did not go through and that nothing stands against yet
     * ([WalletInfo.unanswered]): whatever happens later, only one of the two can be in a block.
     */
    override fun plan(wallet: WalletInfo, to: String, amountSat: Long?, feeRate: Long, used: Set<String>): Plan {
        val script = scriptFor(to)
        val watch = wallet.watchKey()
        val keys = keysOf(watch, allowed(wallet))
        val found = asked { server.utxos(keys.addresses) }
        val spendable = placed(found.utxos, keys).filter { it.spendableAt(found.tip) && key(it) !in used }
        val must = Engine.against(wallet.unanswered, spendable)
        return if (amountSat == null) Planner.sweep(spendable, script, feeRate, must)
        else Planner.pay(spendable, script, amountSat, feeRate, watch.script(1, wallet.changeIndex), must)
    }

    /**
     * Signs with the given words. Nothing leaves the phone here: the server sees the transaction when it is
     * handed over ([deliver]), which the app does only after it has written the payment down.
     */
    override fun sign(expectedXpub: String, plan: Plan, words: List<String>): SignedSpend {
        val account = try { Account.fromWords(words, chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        if (account.watchKey().xpub != expectedXpub) throw EngineError(Problem.Kind.WRONG_WORDS)
        return plan.sign(account)
    }

    /** What a node says of a transaction that it already has, waiting or in a block. */
    private fun onItsWay(reason: String?): Boolean {
        val said = reason.orEmpty().lowercase()
        return "txn-already" in said || "already in" in said || "already known" in said || "same-nonwitness-data" in said
    }

    /**
     * Hands a signed transaction to the server. True: the node behind it has the transaction. False: that
     * could not be found out.
     *
     * The node is first asked whether it would take the transaction. With that question the server has the
     * whole signed transaction, and it could keep it whatever it answers. A no is thrown as a refusal; the one
     * who called must not forget the payment because of it ([ownersNode] is false here, and the model keeps
     * the payment as one that did not go through). A check that could not be made decides nothing: the
     * hand-over is tried all the same.
     *
     * After the check nothing is thrown any more. The server answers every failed hand-over in the same way, a
     * node that said no like a node that was away, so a failed hand-over says nothing by itself. The server is
     * then asked whether it knows the transaction, and the same transaction is handed over again; doing that
     * twice cannot pay twice. An answer that names another transaction is not believed either.
     *
     * There are at most three tries, and none is started later than forty seconds after the beginning. Every
     * question has a time limit of its own, so that a server that answers very slowly cannot hold the
     * hand-over: the whole lasts about a minute at most.
     */
    override fun deliver(signed: SignedSpend): Boolean {
        val until = clock() + Engine.DELIVERY_MILLIS * 1_000_000
        // what one question may take: the time that is left, but long enough for an honest answer over a slow line
        fun left(): Long = maxOf(leastMillis, (until - clock()) / 1_000_000)
        val verdict = runCatching { server.validate(signed.hex, checkMillis) }.getOrNull()
        if (verdict != null && !verdict.allowed) {
            if (onItsWay(verdict.reason)) return true
            throw EngineError(Problem.Kind.REJECTED, verdict.reason.orEmpty())
        }
        for (attempt in 1..Engine.DELIVERY_TRIES) {
            if (runCatching { server.broadcast(signed.hex, left()) }.getOrNull() == signed.txid) return true
            if (runCatching { server.known(signed.txid, left()) }.getOrDefault(false)) return true
            if (clock() > until) return false
            if (attempt < Engine.DELIVERY_TRIES) runCatching { pause(Engine.DELIVERY_PAUSE_MILLIS) }
        }
        return false
    }

    /** Signs and hands over. Returns the id of the transaction that was signed; the server's answer is not taken for it. */
    fun send(expectedXpub: String, plan: Plan, words: List<String>): String {
        val signed = sign(expectedXpub, plan, words)
        deliver(signed)
        return signed.txid
    }

    /**
     * Looks at what the account of some recovery words holds (a paper address). The words are not kept. The
     * server is named the first twenty addresses of each branch of that account, so it learns that this phone
     * asked about them. [spent] are coins already sent away in an earlier part of the same emptying. Coins
     * that a waiting transaction spends are not in the server's list.
     */
    override fun paperLook(words: List<String>, spent: Set<String>): PaperFound {
        val account = try { Account.fromWords(words, chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        val keys = keysOf(account.watchKey(), WalletInfo.GAP, kept = false)
        val found = asked { server.utxos(keys.addresses) }
        return PaperFound(account.address(0, 0), found.tip, placed(found.utxos, keys).filter { key(it) !in spent })
    }

    /**
     * Empties a paper address. With [keepSat] null everything goes to [to]. Otherwise [keepSat] goes to [to]
     * and the rest to [restTo]. One transaction takes at most a thousand coins; the plan says how many are left.
     */
    override fun paperPlan(found: PaperFound, to: String, feeRate: Long, keepSat: Long?, restTo: String?): Plan {
        val script = scriptFor(to)
        if (keepSat == null) return Planner.sweep(found.spendable, script, feeRate)
        val rest = scriptFor(restTo ?: throw EngineError(Problem.Kind.BAD_ADDRESS))
        return Planner.split(found.spendable, script, keepSat, feeRate, rest)
    }

    override fun paperSign(found: PaperFound, plan: Plan, words: List<String>): SignedSpend {
        val account = try { Account.fromWords(words, chain) } catch (e: Exception) { throw EngineError(Problem.Kind.BAD_WORDS) }
        if (account.address(0, 0) != found.address) throw EngineError(Problem.Kind.WRONG_WORDS)
        return sign(account.watchKey().xpub, plan, words)
    }

    fun paperSend(found: PaperFound, plan: Plan, words: List<String>): String {
        val signed = paperSign(found, plan, words)
        deliver(signed)
        return signed.txid
    }

    companion object {
        /** Keys of each branch that are looked at without asking the owner. Both branches together fit one request. */
        const val AUTO = 40
        /** Keys of each branch in one part of the addresses. */
        const val HALF = ServerClient.MAX_ADDRESSES / 2
        /** The most lines of history read for one part of the addresses in one look. */
        const val MAX_LINES = 2000
        const val TRIES = 3
        /** The longest the check before a hand-over may take. */
        const val CHECK_MILLIS = 20_000L
        /** The shortest time a question of the hand-over is given, however little of the forty seconds is left. */
        const val MIN_QUESTION_MILLIS = 10_000L
    }
}
