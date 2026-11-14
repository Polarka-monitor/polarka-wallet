package org.polarka.wallet

import north.core.Account
import north.core.Chain
import north.core.Planner
import north.core.Qrh
import north.core.Unspent
import north.core.Words
import north.node.ServerClient
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paying through a wallet server: what [ServerEngine] does from the plan of a payment to its hand-over. The
 * stand-in server stands on a real TrueNorth Core on regtest, and a relay between the wallet and the server
 * can lose an answer, give one of its own, or change one. Skipped when TN_BIN does not name the node's
 * programs or python3 is missing.
 */
class ServerPayTest {
    private val coin = 100_000_000L

    private fun script(address: String) = Qrh.scriptForAddress(Chain.REGTEST, address)

    private fun key(coin: Unspent) = coin.txid + ":" + coin.vout

    private fun fresh(): Pair<Account, List<String>> {
        val words = Words.generate()
        return Account.fromWords(words, Chain.REGTEST) to words
    }

    /** A node, the stand-in server on it, a relay in front of that, and an engine that asks through the relay and does not really wait. */
    private class Bench(val node: ServerNode, val server: StandIn) : AutoCloseable {
        val relay = ServerRelay(server.url)
        val pauses = ArrayList<Long>()
        val direct = ServerClient(server.url, maxWaitMs = 0)
        val engine = ServerEngine(Chain.REGTEST, emptyList(), ServerClient(relay.url, maxWaitMs = 0), pause = { pauses.add(it) })
        override fun close() {
            relay.close()
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

    private val ask = listOf("/v1/transactions/validate")
    private val hand = listOf("/v1/transactions/broadcast", "/v1/transactions/status")

    @Test
    fun aPaymentFromItsPlanToABlock() {
        bench().use { b ->
            val (wallet, words) = fresh()
            val (other, otherWords) = fresh()
            val miner = fresh().first.address(0, 0)
            var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(2, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 102
            val reward = b.engine.fetch(info).coins[0].amountSat

            // the plan is made from a fresh list of the coins, and the change goes to the wallet's next change key
            b.relay.paths.clear()
            val plan = b.engine.plan(info, other.address(0, 0), 100 * coin, 2)
            assertEquals(listOf("/v1/utxos"), b.relay.paths)
            assertEquals(1, plan.inputs.size)
            assertEquals(reward - 100 * coin - plan.feeSat, plan.changeSat)
            assertTrue(plan.payments.any { it.script.contentEquals(wallet.script(1, 0)) && it.amountSat == plan.changeSat })
            assertEquals(Problem.Kind.BAD_ADDRESS, assertFailsWith<EngineError> { b.engine.plan(info, "north1zzz", 50 * coin, 2) }.kind)

            // signing asks nobody: the transaction stays on the phone
            b.relay.paths.clear()
            assertEquals(Problem.Kind.WRONG_WORDS, assertFailsWith<EngineError> { b.engine.sign(info.xpub, plan, otherWords) }.kind)
            assertEquals(Problem.Kind.BAD_WORDS, assertFailsWith<EngineError> { b.engine.sign(info.xpub, plan, words.dropLast(1) + "zzz") }.kind)
            val signed = b.engine.sign(info.xpub, plan, words)
            assertTrue(b.relay.paths.isEmpty())

            // the hand-over: the node is asked whether it would take the transaction, and then it is given it
            assertTrue(b.engine.deliver(signed))
            assertEquals(ask + "/v1/transactions/broadcast", b.relay.paths)
            assertEquals(listOf(signed.txid), b.node.mempool())
            info = info.copy(changeIndex = 1, pending = listOf(Pending(signed.txid, plan.sentSat, plan.feeSat, plan.inputs.map { key(it) }.toSet())))
            assertEquals(setOf(signed.txid), b.engine.fetch(info).held)

            // handed over a second time, it is there already, and that is no refusal
            b.relay.paths.clear()
            assertTrue(b.engine.deliver(signed))
            assertEquals(ask, b.relay.paths)

            // the coin of a waiting payment is not in the server's list, so the next payment is not built on it
            val next = b.engine.plan(info, other.address(0, 1), 50 * coin, 2)
            assertTrue(next.inputs.none { c -> plan.inputs.any { key(it) == key(c) } })
            // and the coins the app knows to be tied up are left out whatever the server lists
            val tied = next.inputs.map { key(it) }.toSet()
            assertEquals(Problem.Kind.NOTHING_TO_SPEND, Problem.of(assertFailsWith<Exception> { b.engine.plan(info, other.address(0, 1), 50 * coin, 2, tied) }).kind)

            b.node.mine(1, wallet.address(0, 0))                                // tip 103, with a reward for the wallet that is not ripe
            val seen = b.engine.fetch(info)
            assertEquals(-(100 * coin + plan.feeSat), seen.moves.single { it.txid == signed.txid }.netSat)
            assertEquals(3, seen.coins.size)

            // everything: the two coins that can be spent go, the unripe reward stays, and nothing comes back
            val all = b.engine.plan(info, other.address(0, 2), null, 2)
            assertEquals(2 to 0L, all.inputs.size to all.changeSat)
            assertEquals(listOf(b.engine.send(info.xpub, all, words)), b.node.mempool())
        }
    }

    @Test
    fun aPaymentThatTheNodeBehindTheServerWillNotTake() {
        bench().use { b ->
            val (wallet, words) = fresh()
            val other = fresh().first
            val miner = fresh().first.address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 101
            val plan = b.engine.plan(info, other.address(0, 0), 100 * coin, 2)
            val signed = b.engine.sign(info.xpub, plan, words)
            // meanwhile the same coin is spent elsewhere with the same words, and that payment is in a block
            val rival = Planner.pay(plan.inputs, script(other.address(0, 1)), 200 * coin, 2, wallet.script(1, 5)).sign(wallet)
            assertEquals(rival.txid, b.direct.broadcast(rival.hex))
            b.node.mine(1, miner)

            // the node says no before anything is handed over for sending: that is a refusal, with the node's reason
            b.relay.paths.clear()
            val refusal = assertFailsWith<EngineError> { b.engine.deliver(signed) }
            assertEquals(Problem.Kind.REJECTED to "missing-inputs", refusal.kind to refusal.detail)
            assertEquals(ask, b.relay.paths)
            assertTrue(b.node.mempool().isEmpty())

            // a server that is too busy to be asked: thrown as what it is, and nothing was handed over either
            b.relay.paths.clear()
            b.relay.instead = { path, _ -> if (path == "/v1/transactions/validate") 503 to "{\"message\":\"index is not ready\"}" else null }
            assertEquals(Problem.Kind.BUSY, assertFailsWith<EngineError> { b.engine.deliver(signed) }.kind)
            assertEquals(ask, b.relay.paths)

            // and a server that is not there
            val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val away = ServerEngine(Chain.REGTEST, emptyList(), ServerClient("http://127.0.0.1:$nobody", timeoutMs = 3000, maxWaitMs = 0))
            assertEquals(Problem.Kind.UNREACHABLE, assertFailsWith<EngineError> { away.deliver(signed) }.kind)
        }
    }

    @Test
    fun aHandOverWhoseOutcomeNobodyTells() {
        bench().use { b ->
            val (wallet, words) = fresh()
            val other = fresh().first
            val miner = fresh().first.address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(3, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 103
            val coins = b.engine.fetch(info).coins.sortedBy { it.txid }
            fun payment(n: Int) = b.engine.sign(info.xpub, Planner.pay(listOf(coins[n]), script(other.address(0, n)), 10 * coin, 2, wallet.script(1, n)), words)

            // The server takes the transaction and does not pass it on: every hand-over fails, and nobody knows the
            // transaction. Three tries with a pause between them, and then the outcome is not known. Nothing is thrown.
            b.server.set("{\"hold\":true}")
            val first = payment(0)
            b.relay.paths.clear()
            assertFalse(b.engine.deliver(first))
            assertEquals(ask + hand + hand + hand, b.relay.paths)
            assertEquals(listOf(Engine.DELIVERY_PAUSE_MILLIS, Engine.DELIVERY_PAUSE_MILLIS), b.pauses)
            assertTrue(b.node.mempool().isEmpty())

            // No try is started later than forty seconds after the first. Here the pause after the first try takes that long.
            var time = 0L
            val slow = ServerEngine(Chain.REGTEST, emptyList(), ServerClient(b.relay.url, maxWaitMs = 0), pause = { time += 41_000_000_000L }, clock = { time })
            b.relay.paths.clear()
            assertFalse(slow.deliver(first))
            assertEquals(ask + hand + hand, b.relay.paths)
            b.server.set("{\"hold\":false}")

            // The node gets the transaction and the answer is lost on the way: the server is asked, knows it, and that is enough.
            val second = payment(1)
            b.relay.paths.clear()
            b.relay.lost = { path, _ -> if (path == "/v1/transactions/broadcast") 502 else null }
            assertTrue(b.engine.deliver(second))
            assertEquals(ask + hand, b.relay.paths)
            assertEquals(listOf(second.txid), b.node.mempool())
            b.relay.lost = { _, _ -> null }

            // A server that answers with the name of another transaction and passes nothing on is not believed.
            val third = payment(2)
            b.relay.paths.clear()
            b.relay.instead = { path, _ -> if (path == "/v1/transactions/broadcast") 200 to "{\"transaction_id\":\"${second.txid}\"}" else null }
            assertFalse(b.engine.deliver(third))
            assertEquals(ask + hand + hand + hand, b.relay.paths)
            assertEquals(listOf(second.txid), b.node.mempool())
            // One that passes ours on and then names another: the server knows ours, so it is out.
            b.relay.instead = { _, _ -> null }
            b.relay.rewrite = { path, answer -> if (path == "/v1/transactions/broadcast") answer.replace(third.txid, second.txid) else answer }
            b.relay.paths.clear()
            assertTrue(b.engine.deliver(third))
            assertEquals(ask + hand, b.relay.paths)
            assertEquals(setOf(second.txid, third.txid), b.node.mempool().toSet())
        }
    }

    @Test
    fun aServerThatLiesAboutACoin() {
        bench().use { b ->
            val (wallet, words) = fresh()
            val other = fresh().first
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(100, fresh().first.address(0, 0))                       // tip 101
            val reward = b.engine.fetch(info).coins.single().amountSat

            // The server says that the coin is half of what it is. A payment worked out from that would give the
            // other half away as a fee. The signature covers the amounts of the coins it spends, so the node does
            // not take the transaction, and it is never handed over for sending.
            val amount = Regex("\"amount\"\\s*:\\s*$reward\\b")
            b.relay.rewrite = { path, answer -> if (path == "/v1/utxos") answer.replace(amount, "\"amount\":${reward / 2}") else answer }
            val plan = b.engine.plan(info, other.address(0, 0), 100 * coin, 2)
            assertEquals(reward / 2, plan.inputs.single().amountSat)
            val signed = b.engine.sign(info.xpub, plan, words)
            b.relay.paths.clear()
            assertEquals(Problem.Kind.REJECTED, assertFailsWith<EngineError> { b.engine.deliver(signed) }.kind)
            assertEquals(ask, b.relay.paths)
            assertTrue(b.node.mempool().isEmpty())

            // a coin on somebody else's script is not planned with at all
            val mine = Format.hex(wallet.script(0, 0))
            val foreign = Format.hex(other.script(0, 0))
            b.relay.rewrite = { path, answer -> if (path == "/v1/utxos") answer.replace(mine, foreign) else answer }
            assertEquals(Problem.Kind.FOREIGN_OUTPUTS, assertFailsWith<EngineError> { b.engine.plan(info, other.address(0, 0), 100 * coin, 2) }.kind)
        }
    }

    @Test
    fun aPaperAddressEmptiedThroughTheServer() {
        bench().use { b ->
            val wallet = fresh().first
            val (paper, paperWords) = fresh()
            val otherWords = fresh().second
            val second = fresh().first
            val miner = fresh().first.address(0, 0)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(2, paper.address(0, 0))
            b.node.mine(1, paper.address(1, 3))                                 // a coin on another key of the same words
            b.node.mine(100, miner)                                             // tip 103
            b.node.mine(1, paper.address(0, 0))                                 // tip 104: a reward that is not ripe yet

            assertEquals(Problem.Kind.BAD_WORDS, assertFailsWith<EngineError> { b.engine.paperLook(paperWords.dropLast(1) + "zzz") }.kind)
            b.relay.paths.clear()
            val found = b.engine.paperLook(paperWords)
            assertEquals(listOf("/v1/utxos"), b.relay.paths)                    // forty addresses in one question
            assertEquals(paper.address(0, 0), found.address)
            assertEquals(3 to 1, found.spendable.size to found.immature.size)
            val reward = found.spendable[0].amountSat
            // coins sent away in an earlier part of the same emptying are left out
            assertTrue(b.engine.paperLook(paperWords, found.spendable.map { key(it) }.toSet()).spendable.isEmpty())

            // a part goes to the wallet and the rest to another paper address
            assertEquals(Problem.Kind.BAD_ADDRESS, assertFailsWith<EngineError> { b.engine.paperPlan(found, wallet.address(0, 0), 2, 5 * coin) }.kind)
            val plan = b.engine.paperPlan(found, wallet.address(0, 0), 2, 5 * coin, second.address(0, 0))
            assertEquals(Problem.Kind.WRONG_WORDS, assertFailsWith<EngineError> { b.engine.paperSign(found, plan, otherWords) }.kind)
            val txid = b.engine.paperSend(found, plan, paperWords)
            assertEquals(listOf(txid), b.node.mempool())
            // what a waiting transaction spends is no longer listed for the paper address
            val left = b.engine.paperLook(paperWords)
            assertEquals(0 to 1, left.spendable.size to left.immature.size)
            b.node.mine(1, miner)                                               // tip 105
            assertEquals(5 * coin, b.engine.fetch(info).walletSat)
            assertEquals(3 * reward - 5 * coin - plan.feeSat, ServerEngine(Chain.REGTEST, listOf(second.address(0, 0)), b.direct).fetch(null).watchedSat)

            // everything that is left goes to the wallet once it is ripe
            b.node.mine(100, miner)                                             // tip 205
            val ripe = b.engine.paperLook(paperWords)
            val last = b.engine.paperPlan(ripe, wallet.address(0, 1), 2)
            assertEquals(1 to 0L, last.inputs.size to last.changeSat)
            assertEquals(listOf(b.engine.paperSend(ripe, last, paperWords)), b.node.mempool())
        }
    }

    @Test
    fun aPaymentAfterOneThatDidNotGoThrough() {
        bench().use { b ->
            val (wallet, words) = fresh()
            val other = fresh().first
            val miner = fresh().first.address(0, 0)
            var info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            b.node.mine(1, wallet.address(0, 0))
            b.node.mine(100, miner)                                             // tip 101
            // two coins of different size: the reward is split into ten coins and the rest
            val whole = b.engine.fetch(info).coins.single()
            val split = Planner.pay(listOf(whole), script(wallet.address(0, 1)), 10 * coin, 2, wallet.script(1, 0)).sign(wallet)
            assertEquals(split.txid, b.direct.broadcast(split.hex))
            b.node.mine(1, miner)                                               // tip 102
            info = info.copy(receiveIndex = 2, changeIndex = 1)
            val coins = b.engine.fetch(info).coins
            val small = coins.single { it.amountSat == 10 * coin }
            val large = coins.single { it.amountSat != 10 * coin }

            // by itself a payment of twenty coins takes the large coin alone
            assertEquals(listOf(key(large)), b.engine.plan(info, other.address(0, 1), 20 * coin, 2).inputs.map { key(it) })
            // after a payment of the small coin that did not go through, it takes the small coin as well:
            // whatever becomes of the earlier payment, only one of the two can be in a block
            val gone = Planner.pay(listOf(small), script(other.address(0, 0)), coin, 2, wallet.script(1, 1))
            val goneSigned = gone.sign(wallet)
            val after = info.copy(changeIndex = 2, dropped = listOf(Dropped(goneSigned.txid, gone.sentSat, gone.feeSat, setOf(key(small)))))
            val next = b.engine.plan(after, other.address(0, 1), 20 * coin, 2)
            assertEquals(setOf(key(small), key(large)), next.inputs.map { key(it) }.toSet())
            assertTrue(next.payments.any { it.script.contentEquals(wallet.script(1, 2)) })     // the change goes to the wallet's next change key
            assertTrue(b.engine.deliver(b.engine.sign(info.xpub, next, words)))
            assertEquals(Problem.Kind.REJECTED, assertFailsWith<EngineError> { b.engine.deliver(goneSigned) }.kind)

            // while the coin of the earlier payment cannot be had, no payment is made beside it
            assertEquals(Problem.Kind.EARLIER_PAYMENT, assertFailsWith<EngineError> { b.engine.plan(after, other.address(0, 2), coin, 2) }.kind)
        }
    }
}
