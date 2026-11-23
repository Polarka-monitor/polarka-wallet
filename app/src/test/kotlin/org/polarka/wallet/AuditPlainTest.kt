package org.polarka.wallet

import north.core.Account
import north.core.Chain
import north.core.Words
import java.math.BigDecimal
import java.net.URI
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent audit of 0.3.1: checks that need neither a phone nor a real node.
 * Every test describes behaviour that is expected to hold; a failure is a finding (see AUDIT.md for which).
 */
class AuditPlainTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val account = Account.fromWords(words, Chain.REGTEST)
    private val watch = account.watchKey()
    private val info = WalletInfo(Chain.REGTEST, watch.xpub)
    private val somebody = Account.fromWords(Words.generate(), Chain.REGTEST).address(0, 0)

    /** F-09. The node is trusted to say what exists, but an amount above anything that can exist must not get through. */
    @Test
    fun theNodeCannotMakeTheTotalsOverflow() {
        val huge = "92233720368.54775807"
        AuditFakeNode { method, _ ->
            when (method) {
                "getblockchaininfo" -> "{\"chain\":\"regtest\",\"blocks\":50,\"headers\":50,\"initialblockdownload\":false,\"bestblockhash\":\"${"0".repeat(64)}\"}"
                "scantxoutset" -> "{\"success\":true,\"height\":50,\"unspents\":[" +
                    "{\"txid\":\"${"11".repeat(32)}\",\"vout\":0,\"scriptPubKey\":\"${Format.hex(watch.script(0, 0))}\",\"amount\":$huge,\"height\":5,\"coinbase\":false}," +
                    "{\"txid\":\"${"22".repeat(32)}\",\"vout\":0,\"scriptPubKey\":\"${Format.hex(watch.script(0, 1))}\",\"amount\":$huge,\"height\":6,\"coinbase\":false}]}"
                "scanblocks" -> "{\"from_height\":0,\"to_height\":50,\"relevant_blocks\":[],\"completed\":true}"
                "getnetworkhashps" -> "1"
                else -> "null"
            }
        }.use { fake ->
            val outcome = runCatching { Engine(fake.setup()).fetch(info) }
            val onScreen = outcome.getOrNull()?.let { seen ->
                runCatching { Format.coins(seen.totalSat) }.fold({ it }, { "Format.coins throws ${it.javaClass.simpleName}, so the overview cannot be drawn" })
            }
            assertTrue(outcome.isFailure, "two coins of 92 233 720 368 NORTH each were taken; total = ${outcome.getOrNull()?.totalSat} base units; on the screen: $onScreen")
        }
    }

    /** F-10. The id that the app shows and remembers for a payment must be the id of the transaction it signed. */
    @Test
    fun theIdOfAPaymentIsTheIdOfWhatWasSigned() {
        val fee = java.util.concurrent.atomic.AtomicReference("0")
        AuditFakeNode { method, _ ->
            when (method) {
                "scantxoutset" -> "{\"success\":true,\"height\":50,\"unspents\":[" +
                    "{\"txid\":\"${"11".repeat(32)}\",\"vout\":0,\"scriptPubKey\":\"${Format.hex(watch.script(0, 0))}\",\"amount\":5.00000000,\"height\":5,\"coinbase\":false}]}"
                "testmempoolaccept" -> "[{\"allowed\":true,\"vsize\":1,\"fees\":{\"base\":${fee.get()}}}]"
                "sendrawtransaction" -> "\"this is not the id of the transaction\""
                // Adapted after the fix: before it plans a payment the app now asks the node whether a waiting
                // transaction already spends the coin (gettxout). This node says that the coin is free.
                "gettxout" -> "{\"value\":5.00000000,\"confirmations\":45}"
                else -> "null"
            }
        }.use { fake ->
            val engine = Engine(fake.setup())
            val plan = engine.plan(info, somebody, 1_00000000L, 2)
            fee.set(BigDecimal(plan.feeSat).movePointLeft(8).toPlainString())
            val signedId = plan.sign(account).txid                  // the id does not depend on the signatures
            val shown = engine.send(info.xpub, plan, words)
            assertEquals(signedId, shown, "the app takes whatever text the node returns as the id of the payment")
        }
    }

    /** F-11. The price is stored as typed and used every time the overview is drawn; no text may throw there. */
    @Test
    fun aPriceTextCannotBreakTheOverview() {
        val broken = ArrayList<String>()
        for (price in listOf("0.02", "1e3", "0.000000000000000000001", "1e999999999", "1e-999999999", "1E+2147483647")) {
            val outcome = runCatching { Format.value(123_00000000L, price, "USD") }
            if (outcome.isFailure) broken.add("'$price' -> ${outcome.exceptionOrNull()}")
        }
        assertTrue(broken.isEmpty(), "Format.value throws while the overview is composed: $broken")
    }

    /**
     * F-12. Emptying a paper address always takes every coin in one transaction, and one transaction takes at
     * most 1000 coins. An address that collected more block rewards than that cannot be emptied by the app.
     */
    @Test
    fun aPaperAddressWithManyRewardsCanStillBeEmptied() {
        val coins = List(1001) { north.core.Unspent("%064x".format(it + 1), 0, 512_00000000L, 0, 0, 5, true) }
        val found = PaperFound(account.address(0, 0), 2000, coins)
        val engine = Engine(Setup("https://127.0.0.1:1/", "phone", "pw", Chain.REGTEST, emptyList()))
        val whole = runCatching { engine.paperPlan(found, somebody, 1) }
        val split = runCatching { engine.paperPlan(found, somebody, 1, 10_00000000L, somebody) }
        assertTrue(whole.isSuccess || split.isSuccess, "1001 rewards on a paper address: everything -> ${whole.exceptionOrNull()?.let { Problem.of(it) }}, " +
            "a part -> ${split.exceptionOrNull()?.let { Problem.of(it) }}; the screen offers nothing else")
    }

    /** Sound: a setup is taken only with a plain https address, and the host shown is the host that is called. */
    @Test
    fun onlyAPlainHttpsAddressIsTakenForTheNode() {
        val address = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"
        fun text(url: String) = Setup("https://x.example/", "phone", "Pass-1", Chain.MAIN, listOf(address)).toJson()
            .replace("https://x.example/", url.replace("\\", "\\\\").replace("\"", "\\\""))
        val refused = listOf(
            "http://node.example.org/", "http://127.0.0.1:9554/", "https://user:pw@node.example.org/", "https://user@node.example.org/",
            "https://node.example.org/rpc", "https://node.example.org/?a=b", "https://node.example.org/#x", "https://node.example.org:0/",
            "https://node.example.org:65536/", "https://node.example.org:-1/", "https:///", "https://", "HTTPS://node.example.org/",
            "https://node_example.org/", "https://node.example.org\\@evil.org/", "https://evil.org\\.example.org/", "//node.example.org/",
            "https:node.example.org", "https://[::1", "https://exa mple.org/", "javascript:alert(1)", "file:///etc/passwd",
            "https://node.example.org:8443@evil.org/", "https://evil.org#@node.example.org/", "https://evil.org?@node.example.org/",
            "https://node.example.org%2f.evil.org/", "https://node.example.org/..", " https://node.example.org/", "https://nöde.example.org/", "",
        )
        val taken = refused.filter { url -> runCatching { Setup.parse(text(url)) }.isSuccess }
        assertTrue(taken.isEmpty(), "taken as a node address: $taken")
        for (url in listOf("https://node.example.org", "https://node.example.org/", "https://node.example.org:9443/", "https://100.101.102.103:9443/", "https://[fd7a:115c:a1e0::1]:9443/")) {
            val setup = Setup.parse(text(url))
            assertEquals(URI(url).toURL().host, setup.host, url)
        }
    }

    /** Sound: a typed amount is read exactly or not at all. Compared with an independent reading of the same rule. */
    @Test
    fun typedAmountsAreReadExactlyOrRefused() {
        val random = Random(99)
        val chars = "0123456789012345678901234567890123456789..,   -+eE"
        var read = 0
        repeat(300_000) {
            val text = buildString { repeat(1 + random.nextInt(22)) { append(chars[random.nextInt(chars.length)]) } }
            val plain = text.filter { !it.isWhitespace() && !Character.isSpaceChar(it) }.replace(',', '.')
            val expected = if (!Regex("[0-9]{1,11}(\\.[0-9]{0,8})?").matches(plain)) null else {
                val sat = BigDecimal(if (plain.endsWith(".")) plain.dropLast(1) else plain).movePointRight(8)
                // no more than all the coins that can exist (2 000 000 000 NORTH, since the ninth look, M-3)
                if (sat.signum() > 0 && sat <= BigDecimal(north.node.NodeClient.MAX_SAT)) sat.longValueExact() else null
            }
            assertEquals(expected, Format.parseCoins(text), "'$text'")
            if (expected != null) read++
        }
        assertTrue(read > 1000, "the random texts must contain amounts: $read")
        for (odd in listOf("١٢", "１２", "1e3", "0x10", "1_000", "1'000", "--1", "1..2", "1,2,3", ".", ",", "")) assertEquals(null, Format.parseCoins(odd), odd)
        println("AUDIT app: 300000 random texts as amounts, $read of them amounts, all read exactly as an independent reading does")
    }
}
