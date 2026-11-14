package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Planner
import north.core.Qrh
import north.core.Unspent
import north.core.Words
import north.node.Movement
import north.node.ServerClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wallet's look at the chain through a wallet server: what [ServerEngine] makes of the answers of the
 * stand-in server, which stands on a real TrueNorth Core on regtest. Skipped when TN_BIN does not name the
 * node's programs or python3 is missing.
 */
class ServerEngineTest {
    private val coin = 100_000_000L

    private fun script(address: String) = Qrh.scriptForAddress(Chain.REGTEST, address)

    private fun key(coin: Unspent) = coin.txid + ":" + coin.vout

    private fun fresh() = Account.fromWords(Words.generate(), Chain.REGTEST)

    /** A node, the stand-in server on top of it, and a client that does not wait for a busy server. */
    private class Bench(val node: ServerNode, val server: StandIn) : AutoCloseable {
        val client = ServerClient(server.url, maxWaitMs = 0)
        override fun close() {
            server.close()
            node.close()
        }
    }

    private fun bench(): Bench {
        val node = ServerNode.start()
        return try {
            Bench(node, StandIn.start(node))
        } catch (e: Throwable) {
            node.close()
            throw e
        }
    }

    @Test
    fun coinsHistoryAndAWaitingPaymentAsANodeWouldShowThem() {
        bench().use { b ->
            val wallet = fresh()
            val other = fresh()
            val miner = fresh().address(0, 0)
            var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(2, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 102
            val engine = ServerEngine(Chain.REGTEST, emptyList(), b.client)

            var seen = engine.fetch(info)
            assertEquals(102, seen.tip)
            assertEquals(b.node.bestHash(), seen.info.bestBlockHash)
            assertEquals("regtest", seen.info.chain)
            assertTrue(seen.steady && seen.historyWhole)
            assertEquals(listOf(0 to 0, 0 to 0), seen.coins.map { it.branch to it.index })
            val reward = seen.coins[0].amountSat
            assertEquals(2 * reward, seen.spendableSat)
            assertEquals(listOf(1, 2), seen.moves.map { it.height })
            assertTrue(seen.moves.all { it.coinbase && it.netSat == reward && it.feeSat == null })
            assertEquals(1 to 0, seen.nextReceive to seen.nextChange)
            assertEquals(ServerEngine.AUTO, seen.lookedTo)
            assertFalse(seen.wantsFurther)
            assertEquals(0L, seen.arrivingSat)
            assertNull(seen.hashps)
            assertNull(seen.secondsPerBlock)
            assertEquals(seen.walletSat, seen.moves.sumOf { it.netSat })

            // a payment: its coin leaves the list at once, and its change is on its way
            val plan = Planner.pay(seen.coins.filter { it.spendableAt(seen.tip) }, script(other.address(0, 0)), 100 * coin, 2, wallet.script(1, 0))
            val signed = plan.sign(wallet)
            val change = reward - 100 * coin - plan.feeSat
            assertEquals(signed.txid, b.client.broadcast(signed.hex))
            info = info.copy(changeIndex = 1, pending = listOf(
                Pending(signed.txid, plan.sentSat, plan.feeSat, plan.inputs.map { key(it) }.toSet(), atMillis = System.currentTimeMillis()),
            ))
            seen = engine.fetch(info)
            assertEquals(setOf(signed.txid), seen.held)
            assertTrue(seen.inWaiting.isEmpty() && seen.spentBy.isEmpty() && seen.back.isEmpty())
            assertEquals(reward, seen.walletSat)
            assertEquals(change, seen.arrivingSat)
            assertEquals(2, seen.moves.size)
            assertEquals(1 to 1, seen.nextReceive to seen.nextChange)       // the change address is in use at once

            // in a block: the history has it with its fee, the change is a coin, nothing is on its way
            b.node.mine(1, miner)                                               // tip 103
            seen = engine.fetch(info)
            assertTrue(seen.held.isEmpty())
            assertEquals(0L, seen.arrivingSat)
            val last = seen.moves.last()
            assertEquals(Movement(signed.txid, 103, last.time, false, change, reward, plan.feeSat), last)
            assertEquals(-(100 * coin + plan.feeSat), last.netSat)
            assertEquals(plan.inputs.map { key(it) }.associateWith { signed.txid }, seen.spentBy)
            assertEquals(seen.walletSat, seen.moves.sumOf { it.netSat })
            assertEquals(setOf(0 to 0, 1 to 0), seen.coins.map { it.branch to it.index }.toSet())
            // the reward that was spent is no longer known as a reward: the server's history does not say what a transaction was
            assertEquals(1, seen.moves.count { it.coinbase })

            // everything goes and nothing comes back: the payment leaves the wallet no coin, and the history alone says that it is in a block
            val sweep = Planner.sweep(seen.coins.filter { it.spendableAt(seen.tip) }, script(other.address(0, 1)), 2)
            val swept = sweep.sign(wallet)
            assertEquals(2, sweep.inputs.size)
            assertEquals(swept.txid, b.client.broadcast(swept.hex))
            info = info.copy(pending = listOf(Pending(swept.txid, sweep.sentSat, sweep.feeSat, sweep.inputs.map { key(it) }.toSet())))
            b.node.mine(1, miner)                                               // tip 104
            seen = engine.fetch(info)
            assertEquals(sweep.inputs.map { key(it) }.associateWith { swept.txid }, seen.spentBy)
            assertTrue(seen.held.isEmpty() && seen.coins.isEmpty())
            assertEquals(0L, seen.moves.sumOf { it.netSat })
        }
    }

    @Test
    fun aPaymentThatDidNotGoThroughOneWhoseCoinWentElsewhereAndOneThatCameBack() {
        bench().use { b ->
            val wallet = fresh()
            val other = fresh()
            val miner = fresh().address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(2, wallet.address(0, 0))
            b.node.mine(100, miner)
            val engine = ServerEngine(Chain.REGTEST, emptyList(), b.client)
            var seen = engine.fetch(info)
            val (a, c) = seen.coins.sortedBy { it.txid }

            // a payment that was signed and never reached the network: nothing holds it, and its coin is free
            val lost = Planner.pay(listOf(a), script(other.address(0, 0)), 10 * coin, 2, wallet.script(1, 0))
            val waiting = info.copy(changeIndex = 1, pending = listOf(Pending(lost.sign(wallet).txid, lost.sentSat, lost.feeSat, setOf(key(a)))))
            seen = engine.fetch(waiting)
            assertTrue(seen.held.isEmpty() && seen.inWaiting.isEmpty() && seen.spentBy.isEmpty())
            assertEquals(2, seen.coins.size)

            // another transaction made with the same words spends the same coin. While it waits, the coin is tied up ...
            val rival = Planner.pay(listOf(a), script(other.address(0, 1)), 20 * coin, 5, wallet.script(1, 1)).sign(wallet)
            assertEquals(rival.txid, b.client.broadcast(rival.hex))
            seen = engine.fetch(waiting)
            assertEquals(setOf(key(a)), seen.inWaiting)
            assertTrue(seen.held.isEmpty() && seen.spentBy.isEmpty())
            assertEquals(listOf(key(c)), seen.coins.map { key(it) })
            // ... and once it is in a block, the coin is gone from the server's list and nothing waits any more. The
            // server does not say what took the coin, and a coin that is not listed proves nothing (a server could
            // simply leave it out): nothing is concluded. The model keeps such a payment as one that did not go through.
            b.node.mine(1, miner)
            seen = engine.fetch(waiting)
            assertTrue(key(a) !in seen.coins.map { key(it) })
            assertTrue(seen.spentBy.isEmpty() && seen.inWaiting.isEmpty() && seen.held.isEmpty())

            // a payment kept as "did not go through" reaches the network after all, and then a block
            val late = Planner.pay(listOf(c), script(other.address(0, 2)), 30 * coin, 2, wallet.script(1, 2))
            val lateSigned = late.sign(wallet)
            val kept = info.copy(changeIndex = 3, dropped = listOf(Dropped(lateSigned.txid, late.sentSat, late.feeSat, setOf(key(c)))))
            seen = engine.fetch(kept)
            assertTrue(seen.back.isEmpty() && seen.spentBy.isEmpty())
            assertEquals(lateSigned.txid, b.client.broadcast(lateSigned.hex))
            seen = engine.fetch(kept)
            assertEquals(setOf(lateSigned.txid), seen.back)
            // The wallet's own payment that waits is no stranger, and it spends another coin than the first payment did:
            // of the first payment's coin there is still nothing to say.
            seen = engine.fetch(kept.copy(pending = waiting.pending))
            assertEquals(setOf(lateSigned.txid), seen.back)
            assertTrue(seen.spentBy.isEmpty() && seen.inWaiting.isEmpty())
            b.node.mine(1, miner)
            seen = engine.fetch(kept)
            assertEquals(mapOf(key(c) to lateSigned.txid), seen.spentBy)
            assertTrue(seen.back.isEmpty())
        }
    }

    /**
     * The sixth look of the reviewer, M1 and L1. A payment D did not go through; the next payment P is built on
     * D's coin and waits for a block. A server no longer lists a coin that a waiting transaction spends, and it
     * does not know D. That does not make D beaten: the coin is tied up by P. Only P in a block decides, and
     * the coin then carries P's own id.
     */
    @Test
    fun aPaymentThatWaitsOnTheCoinOfOneThatDidNotGoThrough() {
        bench().use { b ->
            val wallet = fresh()
            val other = fresh()
            val miner = fresh().address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(100, miner)
            val engine = ServerEngine(Chain.REGTEST, emptyList(), b.client)
            val a = engine.fetch(info).coins.single()
            val d = Planner.pay(listOf(a), script(other.address(0, 0)), 10 * coin, 2, wallet.script(1, 0))
            val dId = d.sign(wallet).txid                                       // never handed over
            val p = Planner.pay(listOf(a), script(other.address(0, 0)), 10 * coin, 5, wallet.script(1, 1))
            val pSigned = p.sign(wallet)
            assertEquals(pSigned.txid, b.client.broadcast(pSigned.hex))
            val now = info.copy(
                changeIndex = 2,
                pending = listOf(Pending(pSigned.txid, p.sentSat, p.feeSat, setOf(key(a)))),
                dropped = listOf(Dropped(dId, d.sentSat, d.feeSat, setOf(key(a)))),
            )
            var seen = engine.fetch(now)
            assertTrue(seen.coins.none { key(it) == key(a) }, "a coin that a waiting transaction spends is not listed")
            assertEquals(setOf(pSigned.txid), seen.held)
            assertTrue(seen.back.isEmpty())
            assertTrue(seen.spentBy.isEmpty(), "nothing in a block has spent the coin: ${seen.spentBy}")
            assertEquals(setOf(key(a)), seen.inWaiting)

            // P in a block: that is something the server says, and it says it with P's id. D is beaten by P.
            b.node.mine(1, miner)
            seen = engine.fetch(now)
            assertEquals(mapOf(key(a) to pSigned.txid), seen.spentBy)
            assertTrue(seen.held.isEmpty() && seen.inWaiting.isEmpty() && seen.back.isEmpty())

            // A transaction made elsewhere with the same words now waits, and it spends what P brought back. That ties
            // up coins of which nothing else is known. The coin that P has spent in a block is not one of those.
            val broughtBack = seen.coins.single()
            val elsewhere = Planner.pay(listOf(broughtBack), script(other.address(0, 3)), coin, 2, wallet.script(1, 5)).sign(wallet)
            assertEquals(elsewhere.txid, b.client.broadcast(elsewhere.hex))
            seen = engine.fetch(now)
            assertTrue(seen.coins.isEmpty())
            assertEquals(mapOf(key(a) to pSigned.txid), seen.spentBy)
            assertTrue(seen.inWaiting.isEmpty(), "a coin that a block has spent is called tied up: ${seen.inWaiting}")
        }
    }

    @Test
    fun watchedAddressesAndNoWalletAtAll() {
        bench().use { b ->
            val watched = fresh().address(0, 0)
            val payer = fresh()
            val miner = fresh().address(0, 0)
            b.node.mine(3, watched)
            b.node.mine(1, payer.address(0, 0))
            b.node.mine(100, miner)                                             // tip 104
            val engine = ServerEngine(Chain.REGTEST, listOf(watched), b.client)
            var seen = engine.fetch(null)
            assertEquals(104, seen.tip)
            assertEquals(b.node.bestHash(), seen.info.bestBlockHash)
            assertEquals(3, seen.watched!!.utxos.size)
            assertEquals(seen.watchedSat, seen.watchedMoves.sumOf { it.netSat })
            assertEquals(listOf(1, 2, 3), seen.watchedMoves.map { it.height })
            assertTrue(seen.watchedMoves.all { it.coinbase })
            assertTrue(seen.coins.isEmpty() && seen.moves.isEmpty() && seen.steady)
            assertEquals(0, seen.lookedTo)

            // somebody pays the watched address: while the payment waits it is no line of the history, in a block it is
            val nobody = ServerEngine(Chain.REGTEST, emptyList(), b.client)
            val coins = nobody.fetch(WalletInfo(Chain.REGTEST, payer.watchKey().xpub, lockOnOpen = false)).coins
            val paid = Planner.pay(coins, script(watched), 5 * coin, 2, payer.script(1, 0)).sign(payer)
            assertEquals(paid.txid, b.client.broadcast(paid.hex))
            seen = engine.fetch(null)
            assertEquals(listOf(1, 2, 3), seen.watchedMoves.map { it.height })
            assertEquals(3, seen.watched!!.utxos.size)
            b.node.mine(1, miner)                                               // tip 105
            seen = engine.fetch(null)
            assertEquals(Movement(paid.txid, 105, seen.watchedMoves.last().time, false, 5 * coin, 0, null), seen.watchedMoves.last())
            assertEquals(seen.watchedSat, seen.watchedMoves.sumOf { it.netSat })

            val nothing = nobody.fetch(null)
            assertEquals(105, nothing.tip)
            assertNull(nothing.watched)
        }
    }

    @Test
    fun aBlockInTheMiddleOfALookAndACoinThatIsNotTheWallets() {
        bench().use { b ->
            val wallet = fresh()
            val other = fresh()
            val miner = fresh().address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(2, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 102
            ServerRelay(b.server.url).use { relay ->
                val engine = ServerEngine(Chain.REGTEST, emptyList(), ServerClient(relay.url, maxWaitMs = 0))
                // a block arrives right after the first answer of the first look: the look is made again, and is of one piece then
                relay.after = { path, count -> if (path == "/v1/addresses/activity" && count == 1) b.node.mine(1, miner) }
                val seen = engine.fetch(info)
                assertTrue(seen.steady)
                assertEquals(103, seen.tip)
                assertEquals(b.node.bestHash(), seen.info.bestBlockHash)
                val look = listOf("/v1/addresses/activity", "/v1/utxos", "/v1/transactions")
                assertEquals(look + look, relay.paths)

                // a server that lists a coin on a script that is not the wallet's is not believed
                relay.after = { _, _ -> }
                val mine = Format.hex(wallet.script(0, 0))
                val foreign = Format.hex(other.script(0, 0))
                relay.rewrite = { path, answer -> if (path == "/v1/utxos") answer.replaceFirst(mine, foreign) else answer }
                assertEquals(Problem.Kind.FOREIGN_OUTPUTS, assertFailsWith<EngineError> { engine.fetch(info) }.kind)
                // nor for a watched address
                val watching = ServerEngine(Chain.REGTEST, listOf(wallet.address(0, 0)), ServerClient(relay.url, maxWaitMs = 0))
                assertEquals(Problem.Kind.FOREIGN_OUTPUTS, assertFailsWith<EngineError> { watching.fetch(null) }.kind)
            }
        }
    }

    @Test
    fun whatArrivedSinceABlock() {
        bench().use { b ->
            val wallet = fresh()
            val other = fresh()
            val miner = fresh().address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 101
            val engine = ServerEngine(Chain.REGTEST, listOf(other.address(0, 0)), b.client)
            assertEquals(101 to emptyList(), engine.incomingSince(info, 101))
            assertEquals(101 to emptyList(), engine.incomingSince(info, -1))

            b.node.mine(1, wallet.address(0, 1))                                // 102: a reward for the wallet
            val first = engine.fetch(info).coins.single { it.spendableAt(102) }
            val paid = Planner.pay(listOf(first), script(other.address(0, 0)), 10 * coin, 2, wallet.script(1, 0)).sign(wallet)
            assertEquals(paid.txid, b.client.broadcast(paid.hex))
            b.node.mine(1, miner)                                               // 103: the wallet pays the watched address; its change returns
            val back = Planner.pay(listOf(Unspent(paid.txid, 0, 10 * coin, 0, 0, 103, false)), script(wallet.address(0, 2)), 3 * coin, 2, other.script(1, 0)).sign(other)
            assertEquals(back.txid, b.client.broadcast(back.hex))
            b.node.mine(1, miner)                                               // 104: a payment to the wallet

            // somebody pays to one of the wallet's change addresses: not what the owner is told about as arrived
            val rest = 10 * coin - 3 * coin - back.feeSat
            val odd = Planner.pay(listOf(Unspent(back.txid, 1, rest, 1, 0, 104, false)), script(wallet.address(1, 3)), coin, 2, other.script(1, 1)).sign(other)
            assertEquals(odd.txid, b.client.broadcast(odd.hex))
            b.node.mine(1, miner)                                               // 105

            val (tip, arrived) = engine.incomingSince(info, 101)
            assertEquals(105, tip)
            assertEquals(listOf(Triple(102, true, true), Triple(103, false, false), Triple(104, false, true)), arrived.map { Triple(it.move.height, it.move.coinbase, it.own) })
            assertEquals(listOf(10 * coin, 3 * coin), arrived.drop(1).map { it.move.receivedSat })
            assertEquals(listOf(104), engine.incomingSince(info, 103).second.map { it.move.height })
            assertEquals(105 to emptyList(), engine.incomingSince(info, 105))
        }
    }

    @Test
    fun aWalletWithMoreAddressesThanOneRequestTakes() {
        bench().use { b ->
            val wallet = fresh()
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false, lookTo = 60)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(1, wallet.address(0, 55))
            b.node.mine(100, fresh().address(0, 0))                             // tip 102
            ServerRelay(b.server.url).use { relay ->
                val engine = ServerEngine(Chain.REGTEST, emptyList(), ServerClient(relay.url, maxWaitMs = 0))
                val seen = engine.fetch(info)
                assertEquals(60, seen.lookedTo)
                // coins, used keys and history are asked for in two parts each, and everything is found
                assertEquals(setOf(0 to 0, 0 to 55), seen.coins.map { it.branch to it.index }.toSet())
                assertEquals(56 to 0, seen.nextReceive to seen.nextChange)
                assertTrue(seen.wantsFurther)
                assertTrue(seen.historyWhole)
                assertEquals(listOf(1, 2), seen.moves.map { it.height })
                assertEquals(seen.walletSat, seen.moves.sumOf { it.netSat })
                assertEquals(listOf("/v1/addresses/activity", "/v1/utxos", "/v1/transactions").flatMap { listOf(it, it) }, relay.paths)

                // a block arrives between the two parts of one question: the client refuses to put them together, and the look is made again
                relay.paths.clear()
                relay.after = { path, count -> if (path == "/v1/addresses/activity" && count == 1) b.node.mine(1, wallet.address(0, 1)) }
                val again = engine.fetch(info)
                assertTrue(again.steady)
                assertEquals(103, again.tip)
                assertEquals(8, relay.paths.size)
                assertEquals(3, again.coins.size)

                // a block arrives between the two parts of the history: the look is of two moments, and is made again
                relay.paths.clear()
                relay.after = { path, count -> if (path == "/v1/transactions" && count == 1) b.node.mine(1, wallet.address(0, 2)) }
                val third = engine.fetch(info)
                assertTrue(third.steady)
                assertEquals(104, third.tip)
                assertEquals(12, relay.paths.size)
                assertEquals(listOf(1, 2, 103, 104), third.moves.map { it.height })

                // The same for what arrived. A block between the two parts of the history brings a reward that only the
                // second part knows of: it is told the next time, with the height that all the answers have reached, and once.
                relay.paths.clear()
                relay.after = { path, count -> if (path == "/v1/transactions" && count == 1) b.node.mine(1, wallet.address(0, 56)) }
                assertEquals(104 to listOf(104), engine.incomingSince(info, 103).let { (tip, arrived) -> tip to arrived.map { it.move.height } })
                relay.after = { _, _ -> }
                assertEquals(105 to listOf(105), engine.incomingSince(info, 104).let { (tip, arrived) -> tip to arrived.map { it.move.height } })
            }
        }
    }

    /** A history answer without the lines that [leave] picks. */
    private fun without(answer: String, leave: (JsonObject) -> Boolean): String {
        val root = Json.parseToJsonElement(answer).jsonObject
        val kept = root["transactions"]!!.jsonArray.filterNot { leave(it.jsonObject) }
        return JsonObject(root + ("transactions" to JsonArray(kept))).toString()
    }

    @Test
    fun aPaymentThatTheHistoryDoesNotList() {
        bench().use { b ->
            val wallet = fresh()
            val miner = fresh().address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(2, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 102
            ServerRelay(b.server.url).use { relay ->
                val engine = ServerEngine(Chain.REGTEST, emptyList(), ServerClient(relay.url, maxWaitMs = 0))
                val before = engine.fetch(info)
                val plan = Planner.pay(before.coins.filter { it.spendableAt(before.tip) }, script(fresh().address(0, 0)), 100 * coin, 2, wallet.script(1, 0))
                val signed = plan.sign(wallet)
                assertEquals(signed.txid, b.client.broadcast(signed.hex))
                val waiting = info.copy(changeIndex = 1, pending = listOf(Pending(signed.txid, plan.sentSat, plan.feeSat, plan.inputs.map { key(it) }.toSet())))
                val look = listOf("/v1/addresses/activity", "/v1/utxos", "/v1/transactions")

                // the history lists it among those that wait: nobody is asked anything more
                relay.paths.clear()
                assertEquals(setOf(signed.txid), engine.fetch(waiting).held)
                assertEquals(look, relay.paths)

                // the node behind the server has the payment and the index has not caught up: the history does not list it yet
                relay.rewrite = { path, answer -> if (path == "/v1/transactions") without(answer) { it["block_height"] is JsonNull } else answer }
                relay.paths.clear()
                var seen = engine.fetch(waiting)
                assertEquals(setOf(signed.txid), seen.held)
                assertEquals(0L, seen.arrivingSat)
                assertEquals(look + "/v1/transactions/status", relay.paths)

                // in a block, and a history that does not reach back to it: the coin it left in the wallet says where it is
                b.node.mine(1, miner)                                           // tip 103
                relay.rewrite = { path, answer ->
                    if (path == "/v1/transactions") without(answer) { it["transaction_id"]?.jsonPrimitive?.content == signed.txid } else answer
                }
                relay.paths.clear()
                seen = engine.fetch(waiting)
                assertTrue(seen.held.isEmpty())
                assertEquals(plan.inputs.map { key(it) }.associateWith { signed.txid }, seen.spentBy)
                assertTrue(seen.moves.none { it.txid == signed.txid })
                assertEquals(look, relay.paths)
            }
        }
    }

    @Test
    fun transactionsWhoseKeysSitInTwoPartsOfTheAddresses() {
        bench().use { b ->
            val wallet = fresh()
            val other = fresh()
            val miner = fresh().address(0, 0)
            // keys 0 to 49 of both branches are one part of the addresses, keys 50 to 59 the other
            var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false, lookTo = 60)
            for (index in listOf(0, 1, 2, 55)) b.node.mine(1, wallet.address(0, index))
            b.node.mine(100, miner)                                             // tip 104
            val engine = ServerEngine(Chain.REGTEST, emptyList(), b.client)
            var seen = engine.fetch(info)
            val reward = seen.coins[0].amountSat
            fun on(branch: Int, index: Int) = seen.coins.single { it.branch == branch && it.index == index }

            // The inputs in the first part, the change to the second: the first part's line has the fee, the
            // second part's says what came back.
            val first = Planner.pay(listOf(on(0, 0)), script(other.address(0, 0)), 100 * coin, 2, wallet.script(1, 55))
            val firstSigned = first.sign(wallet)
            val firstChange = reward - 100 * coin - first.feeSat
            assertEquals(firstSigned.txid, b.client.broadcast(firstSigned.hex))
            b.node.mine(1, miner)                                               // tip 105
            info = info.copy(changeIndex = 56)
            seen = engine.fetch(info)
            assertEquals(Movement(firstSigned.txid, 105, seen.moves.last().time, false, firstChange, reward, first.feeSat), seen.moves.last())

            // An input in each part: what the wallet put in still shows, the fee does not.
            val second = Planner.pay(listOf(on(0, 1), on(0, 55)), script(other.address(0, 1)), 600 * coin, 2, wallet.script(1, 56))
            val secondSigned = second.sign(wallet)
            assertEquals(2, second.inputs.size)
            val secondChange = 2 * reward - 600 * coin - second.feeSat
            assertEquals(secondSigned.txid, b.client.broadcast(secondSigned.hex))
            b.node.mine(1, miner)                                               // tip 106
            info = info.copy(changeIndex = 57)
            seen = engine.fetch(info)
            assertEquals(Movement(secondSigned.txid, 106, seen.moves.last().time, false, secondChange, 2 * reward, null), seen.moves.last())
            assertTrue(seen.historyWhole)
            assertEquals(seen.walletSat, seen.moves.sumOf { it.netSat })

            // An input in each part again, and the second part gets back more than it put in: its line does not
            // say what it put in, and the transaction is left out of the history.
            val third = Planner.split(listOf(on(0, 2), on(1, 55)), script(other.address(0, 2)), 10 * coin, 2, wallet.script(1, 57))
            val thirdSigned = third.sign(wallet)
            val rest = reward + firstChange - 10 * coin - third.feeSat
            assertEquals(thirdSigned.txid, b.client.broadcast(thirdSigned.hex))
            info = info.copy(changeIndex = 58, pending = listOf(Pending(thirdSigned.txid, third.sentSat, third.feeSat, third.inputs.map { key(it) }.toSet())))
            seen = engine.fetch(info)
            assertEquals(setOf(thirdSigned.txid), seen.held)
            assertEquals(rest, seen.arrivingSat)
            b.node.mine(1, miner)                                               // tip 107
            seen = engine.fetch(info)
            assertTrue(seen.moves.none { it.txid == thirdSigned.txid })
            assertFalse(seen.historyWhole)
            // that the payment is in a block is known all the same, and the coins are right whatever the history says
            assertEquals(third.inputs.map { key(it) }.associateWith { thirdSigned.txid }, seen.spentBy)
            assertTrue(seen.held.isEmpty())
            assertEquals(secondChange + rest, seen.walletSat)

            // The wallet pays itself, from the second part to a receiving key of the first, and somebody else pays a key of the second.
            val own = Planner.pay(listOf(on(1, 56)), script(wallet.address(0, 3)), 5 * coin, 2, wallet.script(1, 58))
            val ownSigned = own.sign(wallet)
            assertEquals(ownSigned.txid, b.client.broadcast(ownSigned.hex))
            val gift = Planner.pay(listOf(Unspent(firstSigned.txid, 0, 100 * coin, 0, 0, 105, false)), script(wallet.address(0, 56)), 7 * coin, 2, other.script(1, 0)).sign(other)
            assertEquals(gift.txid, b.client.broadcast(gift.hex))
            b.node.mine(1, miner)                                               // tip 108
            info = info.copy(changeIndex = 59, pending = emptyList())
            seen = engine.fetch(info)
            // the first part saw the wallet's payment to itself come in, the second saw it go out: together, only the fee went
            val toItself = seen.moves.single { it.txid == ownSigned.txid }
            assertEquals(-own.feeSat to own.feeSat, toItself.netSat to toItself.feeSat)
            // and it is not what the owner is told about as arrived; the payment from somebody else is
            val (tip, arrived) = engine.incomingSince(info, 107)
            assertEquals(108, tip)
            assertEquals(listOf(gift.txid), arrived.map { it.move.txid })
            assertEquals(Triple(7 * coin, false, true), arrived.single().let { Triple(it.move.receivedSat, it.move.coinbase, it.own) })
        }
    }

    @Test
    fun aPartOfTheAddressesWhoseHistoryIsCutShort() {
        bench().use { b ->
            val wallet = fresh()
            val other = fresh()
            val miner = fresh().address(0, 0)
            var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false, lookTo = 60)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 101
            // one page of history for each part of the addresses, and no more
            val engine = ServerEngine(Chain.REGTEST, emptyList(), b.client, most = ServerClient.PAGE)
            var seen = engine.fetch(info)
            assertTrue(seen.historyWhole)
            val reward = seen.coins.single().amountSat
            // a payment from the first part of the addresses, with its change to a key of the second
            val plan = Planner.pay(seen.coins, script(other.address(0, 0)), 100 * coin, 2, wallet.script(1, 55))
            val signed = plan.sign(wallet)
            val change = reward - 100 * coin - plan.feeSat
            assertEquals(signed.txid, b.client.broadcast(signed.hex))
            b.node.mine(1, miner)                                               // tip 102
            info = info.copy(changeIndex = 56)
            // somebody pays a key of the second part, in the first of many blocks that pay their reward to a key of the first
            val gift = Planner.pay(listOf(Unspent(signed.txid, 0, 100 * coin, 0, 0, 102, false)), script(wallet.address(0, 55)), coin, 2, other.script(1, 0)).sign(other)
            assertEquals(gift.txid, b.client.broadcast(gift.hex))
            b.node.mine(ServerClient.PAGE - 1, wallet.address(0, 1))            // blocks 103 to 301

            // The first part has one line more than a page, and its page ends with the payment in block 102. The
            // history is not whole, and every line that was read is told as it is: the payment by both parts.
            seen = engine.fetch(info)
            assertFalse(seen.historyWhole)
            assertEquals(Movement(signed.txid, 102, seen.moves.first().time, false, change, reward, plan.feeSat), seen.moves.first())
            assertEquals(coin, seen.moves.single { it.txid == gift.txid }.netSat)
            assertEquals(ServerClient.PAGE + 1, seen.moves.size)

            // One more reward, and the first part's page ends in block 103. The payment in block 102 is now told
            // by the second part alone, as change that came in. Without the first part's line that would read as
            // a gain, so it is left out. So is what came in block 103, where the first part stopped.
            b.node.mine(1, wallet.address(0, 1))                                // tip 302
            seen = engine.fetch(info)
            assertFalse(seen.historyWhole)
            assertTrue(seen.moves.none { it.txid == signed.txid || it.txid == gift.txid })
            assertEquals((103..302).toList(), seen.moves.map { it.height })
            assertTrue(engine.incomingSince(info, 101).second.none { it.move.txid == gift.txid })

            // what is above that block is told, whichever part it is in, and the coins are all there whatever the history says
            b.node.mine(1, wallet.address(0, 56))                               // tip 303
            seen = engine.fetch(info)
            assertEquals((103..303).toList(), seen.moves.map { it.height })
            assertEquals(ServerClient.PAGE + 3, seen.coins.size)
            assertEquals(setOf(change, coin), seen.coins.filter { !it.coinbase }.map { it.amountSat }.toSet())
        }
    }
}
