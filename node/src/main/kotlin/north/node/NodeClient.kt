package north.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Base64

/** The node answered, but with an error; or it could not be reached (httpStatus 0). */
class NodeError(val httpStatus: Int, val code: Int?, message: String) : Exception(message)

/** One unspent output of a watched address. */
data class Utxo(val txid: String, val vout: Long, val amountSat: Long, val height: Int, val coinbase: Boolean, val script: String)

/**
 * What an address holds, as the node sees it at block height [tip].
 * A block reward counts as spendable once it is 100 blocks deep, the rule TrueNorth Core's own wallet shows.
 */
data class Balance(val tip: Int, val utxos: List<Utxo>) {
    val spendable: List<Utxo> get() = utxos.filter { !it.coinbase || it.height <= tip - MATURITY }
    val immature: List<Utxo> get() = utxos.filter { it.coinbase && it.height > tip - MATURITY }
    val spendableSat: Long get() = spendable.sumOf { it.amountSat }
    val immatureSat: Long get() = immature.sumOf { it.amountSat }
    val totalSat: Long get() = utxos.sumOf { it.amountSat }

    companion object {
        const val MATURITY = 100
    }
}

data class ChainInfo(val chain: String, val blocks: Int, val headers: Int, val syncing: Boolean, val bestBlockHash: String)

data class Verdict(val allowed: Boolean, val vsize: Int?, val feeSat: Long?, val rejectReason: String?)

/**
 * A small JSON-RPC client for a TrueNorth Core node of one's own.
 *
 * It uses only the calls a wallet needs and nothing that touches a wallet on the node:
 * getblockcount, getblockchaininfo, scantxoutset, testmempoolaccept, sendrawtransaction, getrawtransaction.
 * The node should give this user exactly those rights (-rpcwhitelist).
 *
 * The password travels in every request, so the address must be https, or plain http only to this machine or
 * to a private network address (which includes a Tailscale address). Anything else is refused here.
 */
class NodeClient(url: String, private val user: String, private val password: String, private val timeoutMs: Int = 60_000) {
    private val endpoint: URI = URI(url)

    init {
        val host = endpoint.host ?: throw IllegalArgumentException("the node address has no host")
        when (endpoint.scheme) {
            "https" -> Unit
            "http" -> require(isPrivate(host)) { "plain http is only allowed to a private or Tailscale address; use https for $host" }
            else -> throw IllegalArgumentException("the node address must start with https:// or http://")
        }
    }

    fun call(method: String, params: JsonArray = JsonArray(emptyList())): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("1.0"))
            put("id", JsonPrimitive("north"))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString().encodeToByteArray()
        val status: Int
        val text: String
        try {
            val c = endpoint.toURL().openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false           // never carry the password to another address
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$password".encodeToByteArray()))
            c.outputStream.use { it.write(body) }
            status = c.responseCode
            text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            c.disconnect()
        } catch (e: java.io.IOException) {
            throw NodeError(0, null, "the node cannot be reached (${e.javaClass.simpleName})")
        }
        when (status) {
            401 -> throw NodeError(status, null, "the node refused the user name or password")
            403 -> throw NodeError(status, null, "the node does not allow this user to call $method")
        }
        val json = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw NodeError(status, null, "the node sent an answer that is not JSON (HTTP $status)")
        }
        val error = json["error"]
        if (error != null && error !is JsonNull) {
            val e = error.jsonObject
            throw NodeError(status, e["code"]?.jsonPrimitive?.int, e["message"]?.jsonPrimitive?.content ?: "error")
        }
        return json["result"] ?: JsonNull
    }

    fun blockCount(): Int = call("getblockcount").jsonPrimitive.int

    fun chainInfo(): ChainInfo {
        val r = call("getblockchaininfo").jsonObject
        return ChainInfo(
            r.text("chain"), r["blocks"]!!.jsonPrimitive.int, r["headers"]!!.jsonPrimitive.int,
            r["initialblockdownload"]!!.jsonPrimitive.boolean, r.text("bestblockhash"),
        )
    }

    /** Everything unspent on these addresses. The node needs no wallet for it; it scans its set of unspent outputs. */
    fun balance(addresses: List<String>): Balance {
        require(addresses.isNotEmpty()) { "no address to look up" }
        require(addresses.all { a -> a.all { it.isLetterOrDigit() } }) { "an address contains a character that cannot be in an address" }
        val r = call("scantxoutset", buildJsonArray {
            add(JsonPrimitive("start"))
            add(buildJsonArray { addresses.forEach { add(JsonPrimitive("addr($it)")) } })
        }).jsonObject
        if (r["success"]?.jsonPrimitive?.boolean != true) throw NodeError(200, null, "the node could not finish the scan")
        val utxos = r["unspents"]!!.jsonArray.map { it.jsonObject }.map { u ->
            Utxo(
                u.text("txid"), u["vout"]!!.jsonPrimitive.content.toLong(), sats(u["amount"]!!),
                u["height"]!!.jsonPrimitive.int, u["coinbase"]?.jsonPrimitive?.boolean ?: false, u.text("scriptPubKey"),
            )
        }
        return Balance(r["height"]!!.jsonPrimitive.int, utxos)
    }

    /** Asks the node whether it would accept this transaction, without sending it. */
    fun testAccept(hex: String): Verdict {
        val r = call("testmempoolaccept", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(hex)) }) }).jsonArray[0].jsonObject
        val fees = r["fees"]?.jsonObject
        return Verdict(
            r["allowed"]!!.jsonPrimitive.boolean, r["vsize"]?.jsonPrimitive?.int,
            fees?.get("base")?.let { sats(it) }, r["reject-reason"]?.jsonPrimitive?.content,
        )
    }

    /** Sends a signed transaction to the network. Returns its id. */
    fun broadcast(hex: String): String = call("sendrawtransaction", buildJsonArray { add(JsonPrimitive(hex)) }).jsonPrimitive.content

    /** Confirmations of a transaction the node knows (0 while it waits in the mempool), or null if it does not know it. */
    fun confirmations(txid: String): Int? = try {
        val r = call("getrawtransaction", buildJsonArray { add(JsonPrimitive(txid)); add(JsonPrimitive(1)) }).jsonObject
        r["confirmations"]?.jsonPrimitive?.int ?: 0
    } catch (e: NodeError) {
        if (e.code == -5) null else throw e
    }

    private fun JsonObject.text(name: String): String = this[name]!!.jsonPrimitive.content

    companion object {
        /** An amount as the node prints it (a decimal number of coins) to base units, exactly. */
        fun sats(e: JsonElement): Long = BigDecimal(e.jsonPrimitive.content).movePointRight(8).longValueExact()

        /** Loopback, the private ranges of RFC 1918, link-local, and 100.64.0.0/10 (Tailscale). Only literal addresses and "localhost". */
        fun isPrivate(host: String): Boolean {
            if (host == "localhost") return true
            if (!host.all { it.isDigit() || it == '.' } && !host.contains(':')) return false     // a name: never looked up here
            val a = try { InetAddress.getByName(host.trim('[', ']')) } catch (e: Exception) { return false }
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
        }
    }
}
