package north.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/*
 * What a whole wallet needs from its node, beyond single addresses. The node still has no wallet: it is given
 * the wallet's public descriptors (or, for a public node, its addresses) with each request and keeps nothing.
 */

/** Blocks that may touch the wallet, up to the height the node had reached. */
data class RelevantBlocks(val toHeight: Int, val hashes: List<String>)

data class TxInput(val txid: String, val vout: Long)
data class TxOutput(val vout: Long, val amountSat: Long, val script: String)
data class ChainTx(val txid: String, val coinbase: Boolean, val inputs: List<TxInput>, val outputs: List<TxOutput>)
data class ChainBlock(val hash: String, val height: Int, val time: Long, val transactions: List<ChainTx>)

/** What one transaction did to the wallet. The fee is known only when every input was the wallet's own. */
data class Movement(
    val txid: String, val height: Int, val time: Long, val coinbase: Boolean,
    val receivedSat: Long, val spentSat: Long, val feeSat: Long?,
) {
    val netSat: Long get() = receivedSat - spentSat
}

/** The most descriptors sent in one scan. A longer list goes out in parts; a public node takes no more at once. */
const val SCAN_CHUNK = 250

private fun ranged(descriptors: List<String>, window: Int): JsonArray {
    require(descriptors.isNotEmpty()) { "no descriptor to look up" }
    require(window in 1..100_000) { "the range of keys is out of bounds" }
    require(descriptors.all { d -> d.all { it in '!'..'~' && it != '"' && it != '\\' } }) { "a descriptor contains a character that cannot be in a descriptor" }
    return buildJsonArray {
        descriptors.forEach { d ->
            // a descriptor of one fixed address (addr(...)) has no key numbers, and the node refuses a range for it
            if ('*' !in d) add(JsonPrimitive(d)) else add(buildJsonObject {
                put("desc", JsonPrimitive(d))
                put("range", buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(window - 1)) })
            })
        }
    }
}

private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content

/** Everything unspent that the descriptors reach with key numbers below [window]. */
fun NodeClient.scanDescriptors(descriptors: List<String>, window: Int): Balance = scanning {
    var tip = Int.MAX_VALUE
    val all = ArrayList<Utxo>()
    val seen = HashSet<String>()
    for (part in descriptors.distinct().chunked(SCAN_CHUNK)) {
        val request = buildJsonArray { add(JsonPrimitive("start")); add(ranged(part, window)) }
        shaped("scantxoutset") {
            val r = call("scantxoutset", request, scanTimeoutMs).jsonObject
            if (r["success"]?.jsonPrimitive?.boolean != true) throw NodeError(200, null, "the node could not finish the scan")
            tip = minOf(tip, NodeClient.height(r["height"]!!))
            for (u in NodeClient.utxos(r["unspents"]!!.jsonArray)) {
                if (!seen.add(u.txid + ":" + u.vout)) throw NodeError(200, null, "the node listed the same output twice")
                all.add(u)
            }
        }
    }
    Balance(tip, all)
}

/**
 * The blocks from [fromHeight] on that may contain something of the wallet. The node answers from its block
 * filter index (it must run with blockfilterindex=1); a filter can name a block that has nothing in it, never
 * miss one that has.
 */
fun NodeClient.relevantBlocks(descriptors: List<String>, window: Int, fromHeight: Int = 0): RelevantBlocks = scanning {
    require(fromHeight >= 0) { "a height is not negative" }
    var to = Int.MAX_VALUE
    val hashes = LinkedHashSet<String>()
    for (part in descriptors.distinct().chunked(SCAN_CHUNK)) {
        val request = buildJsonArray { add(JsonPrimitive("start")); add(ranged(part, window)); add(JsonPrimitive(fromHeight)) }
        shaped("scanblocks") {
            val r = call("scanblocks", request, scanTimeoutMs).jsonObject
            if (r["completed"]?.jsonPrimitive?.boolean == false) throw NodeError(200, null, "the node could not finish the scan of blocks")
            to = minOf(to, NodeClient.height(r["to_height"]!!))
            r["relevant_blocks"]!!.jsonArray.forEach { hashes.add(NodeClient.id(it.jsonPrimitive.content)) }
        }
    }
    RelevantBlocks(to, hashes.toList())
}

/** One block with its transactions, reduced to what a wallet needs. */
fun NodeClient.block(hash: String): ChainBlock {
    require(NodeClient.isId(hash)) { "this is not a block hash" }
    return shaped("getblock") {
        val b = call("getblock", buildJsonArray { add(JsonPrimitive(hash)); add(JsonPrimitive(2)) }).jsonObject
        val txs = b["tx"]!!.jsonArray.map { it.jsonObject }.map { t ->
            val vin = t["vin"]!!.jsonArray.map { it.jsonObject }
            val coinbase = vin.any { it.containsKey("coinbase") }
            ChainTx(
                NodeClient.id(t.str("txid")), coinbase,
                if (coinbase) emptyList() else vin.map { TxInput(NodeClient.id(it.str("txid")), outputNumber(it["vout"]!!.jsonPrimitive.long)) },
                t["vout"]!!.jsonArray.map { it.jsonObject }.map { o ->
                    TxOutput(outputNumber(o["n"]!!.jsonPrimitive.long), NodeClient.sats(o["value"]!!), o["scriptPubKey"]!!.jsonObject.str("hex"))
                },
            )
        }
        if (NodeClient.id(b.str("hash")) != hash) throw NodeError(200, null, "the node sent another block than the one asked for")
        ChainBlock(hash, NodeClient.height(b["height"]!!), b["time"]!!.jsonPrimitive.long, txs)
    }
}

private fun outputNumber(n: Long): Long = if (n in 0..NodeClient.MAX_VOUT) n else throw NodeError(200, null, "the node sent an output number that cannot exist")

/** The hash of the block at [height] on the node's chain. */
fun NodeClient.blockHash(height: Int): String {
    require(height >= 0) { "a height is not negative" }
    return shaped("getblockhash") { NodeClient.id(call("getblockhash", buildJsonArray { add(JsonPrimitive(height)) }).jsonPrimitive.content) }
}

/** The network's estimated hash rate in hashes per second, from the last [blocks] blocks. */
fun NodeClient.networkHashps(blocks: Int = 120): Double {
    require(blocks in 1..100_000) { "the number of blocks is out of bounds" }
    return shaped("getnetworkhashps") { call("getnetworkhashps", buildJsonArray { add(JsonPrimitive(blocks)) }).jsonPrimitive.double }
}

/** The time written in the block at [height], in seconds since 1970. */
fun NodeClient.blockTime(height: Int): Long {
    val hash = blockHash(height)
    return shaped("getblockheader") { call("getblockheader", buildJsonArray { add(JsonPrimitive(hash)) }).jsonObject["time"]!!.jsonPrimitive.long }
}

object History {
    private fun plus(a: Long, b: Long): Long = NodeClient.total(listOf(a, b))

    /**
     * What the wallet received and spent, transaction by transaction, oldest first.
     * [ours] are the wallet's output scripts in hex. The blocks must be all the blocks that touch the wallet;
     * a coin received in a block that is missing would not be recognized when it is spent later.
     */
    fun movements(blocks: List<ChainBlock>, ours: Set<String>): List<Movement> {
        val owned = HashMap<String, Long>()
        val result = ArrayList<Movement>()
        val listed = HashSet<String>()
        for (block in blocks.distinctBy { it.hash }.sortedBy { it.height }) {
            for (tx in block.transactions) {
                var spent = 0L
                var foreignInput = false
                for (input in tx.inputs) {
                    val amount = owned.remove(input.txid + ":" + input.vout)
                    if (amount == null) foreignInput = true else spent = plus(spent, amount)
                }
                var received = 0L
                for (output in tx.outputs) {
                    if (output.script.lowercase() in ours) {
                        received = plus(received, output.amountSat)
                        owned[tx.txid + ":" + output.vout] = output.amountSat
                    }
                }
                if (spent == 0L && received == 0L) continue
                // one transaction is listed once, even if the node names two blocks that hold it (an abandoned branch)
                if (!listed.add(tx.txid)) continue
                val fee = if (!tx.coinbase && !foreignInput && spent > 0) spent - NodeClient.total(tx.outputs.map { it.amountSat }) else null
                result.add(Movement(tx.txid, block.height, block.time, tx.coinbase, received, spent, fee?.takeIf { it >= 0 }))
            }
        }
        return result
    }

    /**
     * Which transaction in these blocks spends each of [coins] ("txid:n"): the coin and the id of that
     * transaction. A coin that no transaction in these blocks spends is not in the answer.
     */
    fun spenders(blocks: Collection<ChainBlock>, coins: Set<String>): Map<String, String> {
        val found = HashMap<String, String>()
        if (coins.isEmpty()) return found
        for (block in blocks) for (tx in block.transactions) for (input in tx.inputs) {
            val coin = input.txid + ":" + input.vout
            if (coin in coins) found[coin] = tx.txid
        }
        return found
    }

    /** Every output script that appears in these blocks: what the search for used keys of a wallet looks through. */
    fun paidScripts(blocks: Collection<ChainBlock>): Set<String> {
        val scripts = HashSet<String>()
        for (block in blocks) for (tx in block.transactions) for (output in tx.outputs) scripts.add(output.script.lowercase())
        return scripts
    }
}
