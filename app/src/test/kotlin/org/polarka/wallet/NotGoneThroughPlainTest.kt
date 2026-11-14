package org.polarka.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import north.core.Account
import north.core.Chain
import north.core.Words
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 0.4.2: the payments that did not go through, as the wallet's file keeps them. No node and no phone is needed here. */
class NotGoneThroughPlainTest {
    private val xpub = Account.fromWords(Words.generate(), Chain.MAIN).watchKey().xpub
    private fun id(n: Int) = "%064x".format(n)
    private val gone = Dropped(id(1), 5_00000000L, 326, setOf(id(7) + ":0", id(8) + ":1"), 1_794_000_000_000L)

    private fun fileWith(dropped: String): String =
        JsonObject(Json.parseToJsonElement(WalletInfo(Chain.MAIN, xpub).toJson()).jsonObject + ("dropped" to Json.parseToJsonElement(dropped))).toString()

    private fun entry(txid: String, sent: Long, inputs: String) = """{"txid":"$txid","sent":$sent,"fee":1,"inputs":[$inputs],"at":1}"""

    @Test
    fun theyAreKeptInTheWalletsFile() {
        val info = WalletInfo(Chain.MAIN, xpub, pending = listOf(Pending(id(2), 1, 1, setOf(id(9) + ":0"), unsure = true, atMillis = 5)), dropped = listOf(gone))
        assertEquals(info, WalletInfo.parse(info.toJson()))
        // a file written before 0.4.2 has no such list, and is read as before
        val old = JsonObject(Json.parseToJsonElement(info.toJson()).jsonObject.filterKeys { it != "dropped" }).toString()
        assertEquals(info.copy(dropped = emptyList()), WalletInfo.parse(old))
    }

    @Test
    fun aDamagedListIsRefusedAsAWhole() {
        val coin = "\"" + id(7) + ":0\""
        assertEquals(1, WalletInfo.parse(fileWith("[" + entry(id(1), 1, coin) + "]")).dropped.size)
        assertFailsWith<Exception>("not an id") { WalletInfo.parse(fileWith("[" + entry("xyz", 1, coin) + "]")) }
        assertFailsWith<Exception>("no coin") { WalletInfo.parse(fileWith("[" + entry(id(1), 1, "") + "]")) }
        assertFailsWith<Exception>("an amount that cannot exist") { WalletInfo.parse(fileWith("[" + entry(id(1), -1, coin) + "]")) }
        assertFailsWith<Exception>("not a coin") { WalletInfo.parse(fileWith("[" + entry(id(1), 1, "\"" + id(7) + "\"") + "]")) }
        val full = (1..WalletInfo.MAX_DROPPED).joinToString(",") { entry(id(it), 1, "\"" + id(1000 + it) + ":0\"") }
        assertEquals(WalletInfo.MAX_DROPPED, WalletInfo.parse(fileWith("[$full]")).dropped.size)
        assertFailsWith<Exception>("one too many") { WalletInfo.parse(fileWith("[" + full + "," + entry(id(999), 1, coin) + "]")) }
    }

    /** A waiting payment that spends a coin of one that did not go through stands against it; any other waiting payment does not. */
    @Test
    fun aWaitingPaymentThatSpendsItsCoinStandsAgainstIt() {
        val elsewhere = Pending(id(2), 1, 1, setOf(id(9) + ":0"))
        val standing = Pending(id(3), 1, 1, setOf(id(8) + ":1", id(9) + ":5"))
        assertEquals(listOf(gone), WalletInfo(Chain.MAIN, xpub, dropped = listOf(gone)).unanswered)
        assertEquals(listOf(gone), WalletInfo(Chain.MAIN, xpub, pending = listOf(elsewhere), dropped = listOf(gone)).unanswered)
        assertEquals(emptyList(), WalletInfo(Chain.MAIN, xpub, pending = listOf(elsewhere, standing), dropped = listOf(gone)).unanswered)
    }
}
