package io.github.severka_monitor.northwatch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import north.core.Chain
import north.core.Qrh
import java.net.URI

/** Why a setup text was refused. The screen turns the reason into a sentence in the user's language. */
class SetupError(val reason: Reason, val detail: String = "") : Exception("$reason $detail".trim()) {
    enum class Reason { TOO_LONG, NOT_JSON, WRONG_FORMAT, BAD_URL, NOT_HTTPS, BAD_USER, BAD_PASSWORD, BAD_CHAIN, NO_ADDRESS, TOO_MANY_ADDRESSES, BAD_ADDRESS }
}

/**
 * What the app needs to know: where the user's own node is, how to log in to it, and which addresses to watch.
 *
 * It holds no key that can spend. The password opens a node account that can only read the chain and pass on
 * finished transactions; it is still kept encrypted (see Store.kt) and is never shown on the screen.
 */
data class Setup(val url: String, val user: String, val password: String, val chain: Chain, val addresses: List<String>) {
    val host: String get() = URI(url).host
    val chainName: String get() = nameOf(chain)

    fun toJson(): String = buildJsonObject {
        put("format", JsonPrimitive(FORMAT))
        put("node", buildJsonObject {
            put("url", JsonPrimitive(url))
            put("user", JsonPrimitive(user))
            put("password", JsonPrimitive(password))
        })
        put("chain", JsonPrimitive(chainName))
        put("addresses", buildJsonArray { addresses.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    override fun toString(): String = "Setup(url=$url, user=$user, password=<hidden>, chain=$chainName, addresses=$addresses)"

    companion object {
        const val FORMAT = "north-watch-setup/1"
        const val MAX_CHARS = 16 * 1024
        const val MAX_ADDRESSES = 20

        fun nameOf(chain: Chain): String = when (chain) {
            Chain.MAIN -> "main"
            Chain.REGTEST -> "regtest"
        }

        /** Reads a setup text. Everything is checked here, so that nothing half-valid is ever stored. */
        fun parse(text: String): Setup {
            if (text.length > MAX_CHARS) throw SetupError(SetupError.Reason.TOO_LONG)
            val root = try {
                Json.parseToJsonElement(text.trim()) as JsonObject
            } catch (e: Exception) {
                throw SetupError(SetupError.Reason.NOT_JSON)
            }
            if (root.string("format") != FORMAT) throw SetupError(SetupError.Reason.WRONG_FORMAT, root.string("format") ?: "")
            val node = root["node"] as? JsonObject ?: throw SetupError(SetupError.Reason.WRONG_FORMAT, "node")

            val url = node.string("url") ?: throw SetupError(SetupError.Reason.BAD_URL)
            val uri = try { URI(url) } catch (e: Exception) { throw SetupError(SetupError.Reason.BAD_URL) }
            if (uri.scheme != "https") throw SetupError(SetupError.Reason.NOT_HTTPS)
            val plainPath = uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || !plainPath ||
                uri.port == 0 || uri.port > 65535
            ) throw SetupError(SetupError.Reason.BAD_URL)

            val user = node.string("user").orEmpty()
            if (user.isEmpty() || user.length > 64 || user.any { it <= ' ' || it > '~' || it == ':' }) throw SetupError(SetupError.Reason.BAD_USER)
            val password = node.string("password").orEmpty()
            if (password.isEmpty() || password.length > 256 || password.any { it < ' ' || it == '\u007f' }) throw SetupError(SetupError.Reason.BAD_PASSWORD)

            val chain = when (root.string("chain")) {
                "main" -> Chain.MAIN
                "regtest" -> Chain.REGTEST
                else -> throw SetupError(SetupError.Reason.BAD_CHAIN, root.string("chain") ?: "")
            }
            val list = (root["addresses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw SetupError(SetupError.Reason.BAD_ADDRESS) }
            if (list.isNullOrEmpty()) throw SetupError(SetupError.Reason.NO_ADDRESS)
            if (list.size > MAX_ADDRESSES) throw SetupError(SetupError.Reason.TOO_MANY_ADDRESSES)
            for (a in list) {
                try { Qrh.scriptForAddress(chain, a) } catch (e: Exception) { throw SetupError(SetupError.Reason.BAD_ADDRESS, a.take(24)) }
            }
            return Setup(url, user, password, chain, list.distinct())
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
