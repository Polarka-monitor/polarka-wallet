package north.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/*
 * What a whole wallet needs from its node, beyond single addresses. The node still has no wallet: it is given
 * the wallet's public descriptors with each request and forgets them afterwards.
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
fun NodeClient.scanDescriptors(descriptors: List<String>, window: Int): Balance {
    val r = call("scantxoutset", buildJsonArray { add(JsonPrimitive("start")); add(ranged(descriptors, window)) }).jsonObject
    if (r["success"]?.jsonPrimitive?.boolean != true) throw NodeError(200, null, "the node could not finish the scan")
    val utxos = r["unspents"]!!.jsonArray.map { it.jsonObject }.map { u ->
        Utxo(
            u.str("txid"), u["vout"]!!.jsonPrimitive.long, NodeClient.sats(u["amount"]!!),
            u["height"]!!.jsonPrimitive.int, u["coinbase"]?.jsonPrimitive?.boolean ?: false, u.str("scriptPubKey"),
        )
    }
    return Balance(r["height"]!!.jsonPrimitive.int, utxos)
}

/**
 * The blocks from [fromHeight] on that may contain something of the wallet. The node answers from its block
 * filter index (it must run with blockfilterindex=1); a filter can name a block that has nothing in it, never
 * miss one that has.
 */
fun NodeClient.relevantBlocks(descriptors: List<String>, window: Int, fromHeight: Int = 0): RelevantBlocks {
    require(fromHeight >= 0) { "a height is not negative" }
    val r = call("scanblocks", buildJsonArray {
        add(JsonPrimitive("start")); add(ranged(descriptors, window)); add(JsonPrimitive(fromHeight))
    }).jsonObject
    if (r["completed"]?.jsonPrimitive?.boolean == false) throw NodeError(200, null, "the node could not finish the scan of blocks")
    return RelevantBlocks(r["to_height"]!!.jsonPrimitive.int, r["relevant_blocks"]!!.jsonArray.map { it.jsonPrimitive.content })
}

/** One block with its transactions, reduced to what a wallet needs. */
fun NodeClient.block(hash: String): ChainBlock {
    require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) { "this is not a block hash" }
    val b = call("getblock", buildJsonArray { add(JsonPrimitive(hash)); add(JsonPrimitive(2)) }).jsonObject
    val txs = b["tx"]!!.jsonArray.map { it.jsonObject }.map { t ->
        val vin = t["vin"]!!.jsonArray.map { it.jsonObject }
        val coinbase = vin.any { it.containsKey("coinbase") }
        ChainTx(
            t.str("txid"), coinbase,
            if (coinbase) emptyList() else vin.map { TxInput(it.str("txid"), it["vout"]!!.jsonPrimitive.long) },
            t["vout"]!!.jsonArray.map { it.jsonObject }.map { o ->
                TxOutput(o["n"]!!.jsonPrimitive.long, NodeClient.sats(o["value"]!!), o["scriptPubKey"]!!.jsonObject.str("hex"))
            },
        )
    }
    return ChainBlock(b.str("hash"), b["height"]!!.jsonPrimitive.int, b["time"]!!.jsonPrimitive.long, txs)
}

/** The network's estimated hash rate in hashes per second, from the last [blocks] blocks. */
fun NodeClient.networkHashps(blocks: Int = 120): Double {
    require(blocks in 1..100_000) { "the number of blocks is out of bounds" }
    return call("getnetworkhashps", buildJsonArray { add(JsonPrimitive(blocks)) }).jsonPrimitive.double
}

/** The time written in the block at [height], in seconds since 1970. */
fun NodeClient.blockTime(height: Int): Long {
    require(height >= 0) { "a height is not negative" }
    val hash = call("getblockhash", buildJsonArray { add(JsonPrimitive(height)) }).jsonPrimitive.content
    return call("getblockheader", buildJsonArray { add(JsonPrimitive(hash)) }).jsonObject["time"]!!.jsonPrimitive.long
}

object History {
    /**
     * What the wallet received and spent, transaction by transaction, oldest first.
     * [ours] are the wallet's output scripts in hex. The blocks must be all the blocks that touch the wallet;
     * a coin received in a block that is missing would not be recognized when it is spent later.
     */
    fun movements(blocks: List<ChainBlock>, ours: Set<String>): List<Movement> {
        val owned = HashMap<String, Long>()
        val result = ArrayList<Movement>()
        for (block in blocks.distinctBy { it.hash }.sortedBy { it.height }) {
            for (tx in block.transactions) {
                var spent = 0L
                var foreignInput = false
                for (input in tx.inputs) {
                    val amount = owned.remove(input.txid + ":" + input.vout)
                    if (amount == null) foreignInput = true else spent += amount
                }
                var received = 0L
                for (output in tx.outputs) {
                    if (output.script.lowercase() in ours) {
                        received += output.amountSat
                        owned[tx.txid + ":" + output.vout] = output.amountSat
                    }
                }
                if (spent == 0L && received == 0L) continue
                val fee = if (!tx.coinbase && !foreignInput && spent > 0) spent - tx.outputs.sumOf { it.amountSat } else null
                result.add(Movement(tx.txid, block.height, block.time, tx.coinbase, received, spent, fee))
            }
        }
        return result
    }
}
