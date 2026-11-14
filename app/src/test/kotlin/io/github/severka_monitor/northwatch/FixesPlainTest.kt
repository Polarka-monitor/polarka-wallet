package io.github.severka_monitor.northwatch

import north.core.Account
import north.core.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What 0.4 added that needs neither a phone nor a node: the setup of a public node, and waiting payments in the wallet file. */
class FixesPlainTest {
    private val address = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    @Test
    fun aPublicNodeNeedsOnlyItsAddress() {
        val typed = Setup.publicNode("  node.example.org ")
        assertEquals("https://node.example.org", typed.url)
        assertTrue(typed.isPublic && typed.addresses.isEmpty() && typed.chain == Chain.MAIN)
        assertEquals(typed, Setup.parse(typed.toJson()))
        assertTrue("password" !in typed.toJson() && "user" !in typed.toJson())
        for (bad in listOf("http://node.example.org", "https://user:pw@node.example.org", "https://node.example.org/x", "ftp://node.example.org", "", "https://", "node.example.org?a=b")) {
            assertFailsWith<SetupError>(bad) { Setup.publicNode(bad) }
        }

        // the second form of the setup file: no account, perhaps no addresses; or an account as before
        val file = """{"format":"polarka-setup/2","node":{"url":"https://node.example.org:8650"},"chain":"regtest","addresses":[]}"""
        assertTrue(Setup.parse(file).isPublic)
        assertEquals(Chain.REGTEST, Setup.parse(file).chain)
        val own = Setup.parse("""{"format":"polarka-setup/2","node":{"url":"https://node.example.org","user":"phone","password":"Pass-1"},"chain":"main"}""")
        assertFalse(own.isPublic)
        assertEquals(own, Setup.parse(own.toJson()))

        // half an account is refused, and the first form still needs both an account and an address
        fun reason(text: String) = assertFailsWith<SetupError> { Setup.parse(text) }.reason
        assertEquals(SetupError.Reason.BAD_PASSWORD, reason("""{"format":"polarka-setup/2","node":{"url":"https://n.example.org","user":"phone"},"chain":"main"}"""))
        assertEquals(SetupError.Reason.BAD_USER, reason("""{"format":"polarka-setup/2","node":{"url":"https://n.example.org","password":"Pass-1"},"chain":"main"}"""))
        assertEquals(SetupError.Reason.BAD_USER, reason("""{"format":"north-watch-setup/1","node":{"url":"https://n.example.org"},"chain":"main","addresses":["$address"]}"""))
        assertEquals(SetupError.Reason.NO_ADDRESS, reason("""{"format":"north-watch-setup/1","node":{"url":"https://n.example.org","user":"phone","password":"Pass-1"},"chain":"main","addresses":[]}"""))
        assertEquals(SetupError.Reason.WRONG_FORMAT, reason("""{"format":"polarka-setup/3","node":{"url":"https://n.example.org"},"chain":"main"}"""))
    }

    @Test
    fun waitingPaymentsAreKeptInTheWalletFile() {
        val xpub = Account.fromWords(words, Chain.REGTEST).watchKey().xpub
        val waiting = Pending("ab".repeat(32), 5_00000000L, 326, setOf("cd".repeat(32) + ":1", "ef".repeat(32) + ":0"), unsure = true, atMillis = 1234)
        val info = WalletInfo(Chain.REGTEST, xpub, 2, 3, pending = listOf(waiting))
        assertEquals(info, WalletInfo.parse(info.toJson()))

        // a file written by 0.3.1 has no such list, and is read as before
        val old = info.toJson().replace(Regex(",\"pending\":\\[.*]"), "")
        check("pending" !in old) { "the test is not valid" }
        assertEquals(info.copy(pending = emptyList()), WalletInfo.parse(old))

        // a damaged entry refuses the whole file: nothing half-read is used
        assertFails { WalletInfo.parse(info.toJson().replace("ab".repeat(32), "xyz")) }
        assertFails { WalletInfo.parse(info.toJson().replace(":1\"", ":-1\"")) }
        assertFails { WalletInfo.parse(info.toJson().replace("\"sent\":500000000", "\"sent\":-5")) }
    }

    @Test
    fun aPriceIsAPlainNumberOrNothing() {
        assertEquals("2.46 USD", Format.value(123_00000000L, "0,02", "USD"))
        assertEquals("2.46", Format.value(123_00000000L, " 0.02 ", ""))
        for (odd in listOf("", "abc", "1e3", "-1", "0", "1e999999999", "0x10", "1.", ".5", "1.123456789")) {
            assertEquals(null, Format.value(123_00000000L, odd, "USD"), odd)
        }
    }
}
