package org.polarka.wallet

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
 * What the app needs to know: where the node or the wallet server is, how to log in to it, and which addresses
 * to watch.
 *
 * There are two kinds of node. A node of one's own has an account ([user], [password]) that can only read the
 * chain and pass on finished transactions; the password is still kept encrypted (see Store.kt) and is never
 * shown on the screen. A public node has no account: [user] and [password] are empty, and the wallet names its
 * addresses to it one by one instead of handing over the key that shows the whole wallet.
 *
 * A wallet server ([server]) is not a node. It stands in front of one, keeps an index of addresses and is
 * asked in its own way (see ServerEngine). It has no account either.
 *
 * It holds no key that can spend.
 */
data class Setup(
    val url: String, val user: String, val password: String, val chain: Chain, val addresses: List<String>,
    val server: Boolean = false,
) {
    val host: String get() = URI(url).host
    val chainName: String get() = nameOf(chain)

    /** A public node: no account, so nothing to log in with. A wallet server is not counted here. */
    val isPublic: Boolean get() = user.isEmpty() && !server

    fun toJson(): String = buildJsonObject {
        put("format", JsonPrimitive(if (server) FORMAT_3 else if (isPublic || addresses.isEmpty()) FORMAT_2 else FORMAT))
        if (server) {
            put("server", buildJsonObject { put("url", JsonPrimitive(url)) })
        } else {
            put("node", buildJsonObject {
                put("url", JsonPrimitive(url))
                if (!isPublic) {
                    put("user", JsonPrimitive(user))
                    put("password", JsonPrimitive(password))
                }
            })
        }
        put("chain", JsonPrimitive(chainName))
        put("addresses", buildJsonArray { addresses.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    override fun toString(): String =
        "Setup(url=$url, user=$user, password=<hidden>, chain=$chainName, addresses=$addresses" + (if (server) ", server" else "") + ")"

    companion object {
        /** The first form: a node of one's own with at least one address to watch. */
        const val FORMAT = "north-watch-setup/1"

        /** The second form: the account may be left out (a public node), and so may the addresses. */
        const val FORMAT_2 = "polarka-setup/2"

        /** The third form: a wallet server in place of a node. Only its address is named. */
        const val FORMAT_3 = "polarka-setup/3"

        /**
         * The wallet server offered on the first screen: the public server of the Northbound project, named
         * here at its author's request. The owner may type another one.
         */
        const val DEFAULT_SERVER = "https://api.northboundwallet.org"
        const val MAX_CHARS = 16 * 1024
        const val MAX_ADDRESSES = 20

        fun nameOf(chain: Chain): String = when (chain) {
            Chain.MAIN -> "main"
            Chain.REGTEST -> "regtest"
        }

        /** Only a plain https address is taken: a host, perhaps a port, nothing else. */
        private fun checkedUrl(url: String?): String {
            if (url == null) throw SetupError(SetupError.Reason.BAD_URL)
            val uri = try { URI(url) } catch (e: Exception) { throw SetupError(SetupError.Reason.BAD_URL) }
            if (uri.scheme != "https") throw SetupError(SetupError.Reason.NOT_HTTPS)
            val plainPath = uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || !plainPath ||
                uri.port == 0 || uri.port > 65535
            ) throw SetupError(SetupError.Reason.BAD_URL)
            return url
        }

        /** A public node typed in by hand: only its address is needed. */
        fun publicNode(url: String, chain: Chain = Chain.MAIN): Setup {
            val typed = url.trim()
            if (typed.length > 300) throw SetupError(SetupError.Reason.BAD_URL)
            return Setup(checkedUrl(if ("://" in typed) typed else "https://$typed"), "", "", chain, emptyList())
        }

        /** A wallet server typed in by hand, or the one the app offers: only its address is needed. */
        fun server(url: String, chain: Chain = Chain.MAIN): Setup {
            val typed = url.trim()
            if (typed.length > 300) throw SetupError(SetupError.Reason.BAD_URL)
            return Setup(checkedUrl(if ("://" in typed) typed else "https://$typed"), "", "", chain, emptyList(), server = true)
        }

        /** Reads a setup text. Everything is checked here, so that nothing half-valid is ever stored. */
        fun parse(text: String): Setup {
            if (text.length > MAX_CHARS) throw SetupError(SetupError.Reason.TOO_LONG)
            val root = try {
                Json.parseToJsonElement(text.trim()) as JsonObject
            } catch (e: Exception) {
                throw SetupError(SetupError.Reason.NOT_JSON)
            }
            val format = root.string("format")
            if (format != FORMAT && format != FORMAT_2 && format != FORMAT_3) throw SetupError(SetupError.Reason.WRONG_FORMAT, format?.take(40) ?: "")
            val second = format == FORMAT_2
            val third = format == FORMAT_3
            // a setup names a node or a wallet server, never both: which of the two is asked must not be a guess
            if (third == root.containsKey("node") || third != root.containsKey("server")) {
                throw SetupError(SetupError.Reason.WRONG_FORMAT, if (third) "server" else "node")
            }
            val node = root[if (third) "server" else "node"] as? JsonObject ?: throw SetupError(SetupError.Reason.WRONG_FORMAT, if (third) "server" else "node")
            val url = checkedUrl(node.string("url"))

            // an account is a user name together with a password; the second form may come without one, and a
            // wallet server has none: a text that gives one to a server is not what it says it is
            if (third && (node.containsKey("user") || node.containsKey("password"))) throw SetupError(SetupError.Reason.WRONG_FORMAT, "server")
            val anonymous = third || (second && !node.containsKey("user") && !node.containsKey("password"))
            val user = node.string("user").orEmpty()
            val password = node.string("password").orEmpty()
            if (!anonymous) {
                if (user.isEmpty() || user.length > 64 || user.any { it <= ' ' || it > '~' || it == ':' }) throw SetupError(SetupError.Reason.BAD_USER)
                if (password.isEmpty() || password.length > 256 || password.any { it < ' ' || it == '\u007f' }) throw SetupError(SetupError.Reason.BAD_PASSWORD)
            }

            val chain = when (root.string("chain")) {
                "main" -> Chain.MAIN
                "regtest" -> Chain.REGTEST
                else -> throw SetupError(SetupError.Reason.BAD_CHAIN, root.string("chain")?.take(40) ?: "")
            }
            val listed = root["addresses"]
            if (listed != null && listed !is JsonArray) throw SetupError(SetupError.Reason.BAD_ADDRESS)
            val list = (listed as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw SetupError(SetupError.Reason.BAD_ADDRESS) }
            if (list.isEmpty() && !second && !third) throw SetupError(SetupError.Reason.NO_ADDRESS)
            if (list.size > MAX_ADDRESSES) throw SetupError(SetupError.Reason.TOO_MANY_ADDRESSES)
            for (a in list) {
                try { Qrh.scriptForAddress(chain, a) } catch (e: Exception) { throw SetupError(SetupError.Reason.BAD_ADDRESS, a.take(24)) }
            }
            return Setup(url, user, password, chain, list.distinct(), server = third)
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
