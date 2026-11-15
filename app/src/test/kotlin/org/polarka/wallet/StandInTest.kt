package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import north.core.Account
import north.core.Chain
import north.core.Planner
import north.core.Qrh
import north.core.Unspent
import north.core.Words
import north.node.NodeError
import north.node.ServerClient
import north.node.ServerFee
import north.node.ServerMove
import north.node.ServerVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client for a Northbound wallet server against the stand-in server, which answers from a real TrueNorth
 * Core on regtest. One wallet's story is told through the server: rewards, a payment that waits and then is
 * in a block, a payment with inputs from two sets of addresses, a refusal, a server that passes nothing on,
 * an index that is behind, a server that asks to slow down, a history of more than one page, another branch.
 *
 * It checks two things at once: that the client reads a real server's answers, and that the stand-in does
 * what the real server's sources say. Skipped when TN_BIN does not name the node's programs or python3 is missing.
 */
class StandInTest {
    private val coin = 100_000_000L

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun script(address: String) = Qrh.scriptForAddress(Chain.REGTEST, address)

    /** The wallet's coins as the server lists them, each placed on its own key, the way a wallet has to do it. */
    private fun coinsOf(client: ServerClient, account: Account, window: Int = 4): List<Unspent> {
        val places = HashMap<String, Pair<Int, Int>>()
        for (branch in 0..1) for (index in 0 until window) places[hex(account.script(branch, index))] = branch to index
        val found = client.utxos((0..1).flatMap { branch -> (0 until window).map { account.address(branch, it) } })
        return found.utxos.map { u ->
            val place = places.getValue(u.script)
            Unspent(u.txid, u.vout, u.amountSat, place.first, place.second, u.height, u.coinbase)
        }.filter { it.spendableAt(found.tip) }
    }

    @Test
    fun oneWalletsStoryThroughTheServer() {
        ServerNode.start().use { node ->
            StandIn.start(node).use { server ->
                val client = ServerClient(server.url, maxWaitMs = 0)
                val wallet = Account.fromWords(Words.generate(), Chain.REGTEST)
                val other = Account.fromWords(Words.generate(), Chain.REGTEST)
                val miner = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)
                val w00 = wallet.address(0, 0)
                val w10 = wallet.address(1, 0)
                val w11 = wallet.address(1, 1)
                node.mine(2, w00)
                node.mine(100, miner)                                               // tip 102

                assertEquals(102, client.tipHeight())
                assertEquals(ServerFee(1, 6), client.feeEstimate())

                // two block rewards, the older one first, each as deep as its block says
                val first = client.utxos(listOf(w00, w10))
                assertEquals(102 to node.bestHash(), first.tip to first.tipHash)
                assertEquals(listOf(1, 2), first.utxos.map { it.height })
                assertTrue(first.utxos.all { it.coinbase && it.script == hex(wallet.script(0, 0)) })
                val reward = first.utxos[0].amountSat
                assertEquals(setOf(w00), client.activity(listOf(w00, w10, other.address(0, 0))).used)
                var history = client.history(listOf(w00, w10))
                assertEquals(listOf(2, 1), history.moves.map { it.height })
                assertEquals(listOf(101, 102), history.moves.map { it.confirmations })
                assertTrue(history.moves.all { it.netSat == reward && it.feeSat == null })              // a block reward has no fee

                // a payment of 100 coins; the rest comes back to the first change address
                val plan = Planner.pay(coinsOf(client, wallet), script(other.address(0, 0)), 100 * coin, 2, wallet.script(1, 0))
                val signed = plan.sign(wallet)
                val change = reward - 100 * coin - plan.feeSat
                assertEquals(ServerVerdict(true, null), client.validate(signed.hex))
                assertFalse(client.known(signed.txid))
                assertEquals(signed.txid, client.broadcast(signed.hex))
                assertEquals(listOf(signed.txid), node.mempool())
                assertTrue(client.known(signed.txid))

                // while it waits for a block: the coin it spends is gone from the list, and its change is not there yet
                val waiting = client.utxos(listOf(w00, w10))
                assertEquals(listOf(reward), waiting.utxos.map { it.amountSat })
                assertTrue(waiting.utxos.none { it.txid == plan.inputs[0].txid })
                assertEquals(setOf(w00, w10), client.activity(listOf(w00, w10, w11)).used)      // the change address counts as used at once
                // asked about all the wallet's addresses: the wallet loses what went elsewhere and the fee
                history = client.history(listOf(w00, w10))
                assertEquals(3, history.moves.size)
                assertEquals(ServerMove(signed.txid, -(100 * coin + plan.feeSat), plan.feeSat, null, 0, history.moves[0].time), history.moves[0])
                // asked about the paying address alone: it loses the whole coin
                val alone = client.history(listOf(w00)).moves[0]
                assertEquals(ServerMove(signed.txid, -reward, plan.feeSat, null, 0, alone.time), alone)
                // asked about the change address alone: it gains the change, and the fee beside it is still that of the whole transaction
                val back = client.history(listOf(w10)).moves.single()
                assertEquals(ServerMove(signed.txid, change, plan.feeSat, null, 0, back.time), back)
                // the lines of the two requests add up to the line of the one
                assertEquals(history.moves[0].netSat, alone.netSat + back.netSat)
                assertEquals(100 * coin, client.history(listOf(other.address(0, 0))).moves.single().netSat)

                node.mine(1, miner)                                                 // tip 103
                history = client.history(listOf(w00, w10))
                assertEquals(listOf(103, 2, 1), history.moves.map { it.height })
                assertEquals(1, history.moves[0].confirmations)
                val after = client.utxos(listOf(w00, w10))
                assertEquals(setOf(reward, change), after.utxos.map { it.amountSat }.toSet())
                assertEquals(listOf(false), after.utxos.filter { it.height == 103 }.map { it.coinbase })

                // a payment with inputs on two addresses: the second reward (w00) and the change (w10) pay 10 coins on, the rest goes to w11
                val second = Planner.split(coinsOf(client, wallet), script(other.address(0, 1)), 10 * coin, 2, wallet.script(1, 1)).sign(wallet)
                val rest = reward + change - 10 * coin - second.feeSat
                assertEquals(second.txid, client.broadcast(second.hex))
                node.mine(1, miner)                                                 // tip 104
                // asked about all three addresses: the wallet loses what went elsewhere and the fee
                val full = client.history(listOf(w00, w10, w11)).moves[0]
                assertEquals(ServerMove(second.txid, -(10 * coin + second.feeSat), second.feeSat, 104, 1, full.time), full)
                // asked about w00 alone: one input is its own, one is not, and it loses what it put in
                val part = client.history(listOf(w00)).moves[0]
                assertEquals(ServerMove(second.txid, -reward, second.feeSat, 104, 1, part.time), part)
                // asked about the two change addresses: they get more than they put in, and the line says by how much.
                // With the line for w00 it adds up to the whole, which is why a history can be put together from parts.
                val mixed = client.history(listOf(w10, w11)).moves[0]
                assertEquals(ServerMove(second.txid, rest - change, second.feeSat, 104, 1, mixed.time), mixed)
                assertEquals(full.netSat, part.netSat + mixed.netSat)

                // the change of the first payment again, which the second payment has spent: asked to check, the server passes the node's refusal on ...
                val unspent = listOf(Unspent(second.txid, 1, rest, 1, 1, 104, false))       // the rest of the second payment, on w11
                val gone = Planner.sweep(listOf(Unspent(signed.txid, 1, change, 1, 0, 103, false)), script(other.address(0, 2)), 2).sign(wallet)
                val verdict = client.validate(gone.hex)
                assertFalse(verdict.allowed)
                assertEquals("missing-inputs", verdict.reason)
                // ... but at sending, a refusal looks like a node that is down
                assertEquals(502, assertFailsWith<NodeError> { client.broadcast(gone.hex) }.httpStatus)
                assertFalse(client.known(gone.txid))

                // a server that takes a transaction and does not pass it on answers with the same error
                val third = Planner.sweep(unspent, script(other.address(0, 3)), 2).sign(wallet)
                server.set("{\"hold\":true}")
                assertEquals(502, assertFailsWith<NodeError> { client.broadcast(third.hex) }.httpStatus)
                assertFalse(client.known(third.txid))
                assertTrue(node.mempool().isEmpty())
                server.set("{\"hold\":false}")
                assertEquals(third.txid, client.broadcast(third.hex))
                assertTrue(client.known(third.txid))

                // an index that is behind answers nothing about addresses; what comes straight from the node still answers
                server.set("{\"stale\":true}")
                assertEquals(503, assertFailsWith<NodeError> { client.utxos(listOf(w00)) }.httpStatus)
                assertEquals(503, assertFailsWith<NodeError> { client.history(listOf(w00)) }.httpStatus)
                assertEquals(104, client.tipHeight())
                // a server that asks to slow down is waited for
                server.set("{\"stale\":false,\"busy\":2}")
                val pauses = ArrayList<Long>()
                assertEquals(104, ServerClient(server.url, pause = { pauses.add(it) }).tipHeight())
                assertEquals(2, pauses.size)

                // addresses of another network and too many addresses are refused by the server itself
                val elsewhere = Account.fromWords(Words.generate(), Chain.MAIN).address(0, 0)
                val refusal = assertFailsWith<NodeError> { client.utxos(listOf(elsewhere)) }
                assertEquals(400 to "address belongs to a different network", refusal.httpStatus to refusal.message)
                val many = (1..101).joinToString(",") { "\"$w00\"" }
                assertEquals(400, server.post("/v1/utxos", "{\"addresses\":[$many]}").first)

                // a history cursor holds for one moment of the index: after a block the server says that it changed
                val page = server.post("/v1/transactions", "{\"addresses\":[\"$miner\"],\"limit\":2}")
                assertEquals(200, page.first)
                val cursor = Json.parseToJsonElement(page.second).jsonObject["next_cursor"]!!.jsonPrimitive.content
                val next = "{\"addresses\":[\"$miner\"],\"limit\":2,\"cursor\":\"$cursor\"}"
                assertEquals(200, server.post("/v1/transactions", next).first)
                node.mine(1, miner)                                                 // tip 105, with the third payment
                assertEquals(409, server.post("/v1/transactions", next).first)

                // a history of more than one page is read whole, newest first
                node.mine(120, miner)                                               // tip 225
                val long = client.history(listOf(miner))
                assertEquals(223, long.moves.size)
                assertTrue(long.complete)
                assertEquals(long.moves.map { it.height!! }.sortedDescending(), long.moves.map { it.height })
                assertEquals(225, long.tip)

                // coins come a page at a time: with fifty to a page the client reads five pages and has them all, in the same order
                val whole = client.utxos(listOf(miner))
                server.set("{\"page\":50}")
                val paged = client.utxos(listOf(miner))
                assertEquals(223, paged.utxos.size)
                assertEquals(whole.utxos, paged.utxos)
                // a cursor of coins holds for one moment of the index, as a cursor of history does
                val some = server.post("/v1/utxos", "{\"addresses\":[\"$miner\"]}")
                assertEquals(200, some.first)
                val further = Json.parseToJsonElement(some.second).jsonObject["next_cursor"]!!.jsonPrimitive.content
                val following = "{\"addresses\":[\"$miner\"],\"cursor\":\"$further\"}"
                assertEquals(200, server.post("/v1/utxos", following).first)
                node.mine(1, miner)                                                 // tip 226
                assertEquals(409, server.post("/v1/utxos", following).first)
                assertEquals(224, client.utxos(listOf(miner)).utxos.size)
                server.set("{\"page\":0}")

                // another branch wins: the stand-in follows its node
                val before = client.utxos(listOf(miner))
                node.cli("invalidateblock", node.bestHash())
                val shorter = client.utxos(listOf(miner))
                assertEquals(before.tip - 1 to before.utxos.size - 1, shorter.tip to shorter.utxos.size)
                node.mine(2, w00)
                val longer = client.utxos(listOf(miner, w00))
                assertEquals(before.tip + 1, longer.tip)
                assertEquals(before.utxos.size - 1, longer.utxos.count { it.script == hex(script(miner)) })
                assertEquals(2, longer.utxos.count { it.script == hex(wallet.script(0, 0)) })
            }
        }
    }
}
