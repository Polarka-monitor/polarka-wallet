package org.polarka.wallet

import north.core.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SetupTest {
    // The address of the public BIP39 test phrase (vectors/p2qrh-test-vectors.json). Nobody should send coins to it.
    private val main = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"
    private val regtest = "rnorth1zfgvaxg6parqfv2drvsk9zadefh40zg97ese44hjkjzkjamkftpzsnpx4h6"

    private fun text(
        format: String = Setup.FORMAT, url: String = "https://node.example.ts.net:9443/", user: String = "phone",
        password: String = "s3cret-Value_1", chain: String = "main", addresses: String = "[\"$main\"]",
    ) = """{"format":"$format","node":{"url":"$url","user":"$user","password":"$password"},"chain":"$chain","addresses":$addresses}"""

    private fun refused(t: String) = assertFailsWith<SetupError> { Setup.parse(t) }.reason

    @Test
    fun aGoodSetupIsRead() {
        val s = Setup.parse("  \n" + text() + "\n")
        assertEquals("https://node.example.ts.net:9443/", s.url)
        assertEquals("node.example.ts.net", s.host)
        assertEquals("phone", s.user)
        assertEquals("s3cret-Value_1", s.password)
        assertEquals(Chain.MAIN, s.chain)
        assertEquals(listOf(main), s.addresses)
        assertEquals(s, Setup.parse(s.toJson()))
    }

    @Test
    fun thePasswordIsNotInTheTextForm() {
        assertFalse(Setup.parse(text()).toString().contains("s3cret"))
    }

    @Test
    fun onlyHttpsIsAccepted() {
        assertEquals(SetupError.Reason.NOT_HTTPS, refused(text(url = "http://100.101.102.103:9554/")))
        assertEquals(SetupError.Reason.NOT_HTTPS, refused(text(url = "ftp://node.example/")))
        assertEquals(SetupError.Reason.BAD_URL, refused(text(url = "https://user:pw@node.example/")))
        assertEquals(SetupError.Reason.BAD_URL, refused(text(url = "https://node.example/?a=1")))
        assertEquals(SetupError.Reason.BAD_URL, refused(text(url = "https://node.example/wallet/x")))
        assertEquals(SetupError.Reason.BAD_URL, refused(text(url = "https:///nohost")))
    }

    @Test
    fun brokenTextsAreRefused() {
        assertEquals(SetupError.Reason.NOT_JSON, refused("hello"))
        assertEquals(SetupError.Reason.NOT_JSON, refused("[1,2]"))
        assertEquals(SetupError.Reason.WRONG_FORMAT, refused(text(format = "north-watch-setup/2")))
        assertEquals(SetupError.Reason.BAD_USER, refused(text(user = "")))
        assertEquals(SetupError.Reason.BAD_USER, refused(text(user = "a:b")))
        assertEquals(SetupError.Reason.BAD_PASSWORD, refused(text(password = "")))
        assertEquals(SetupError.Reason.BAD_PASSWORD, refused(text(password = "a\\nb")))
        assertEquals(SetupError.Reason.BAD_CHAIN, refused(text(chain = "testnet")))
        assertEquals(SetupError.Reason.NO_ADDRESS, refused(text(addresses = "[]")))
        assertEquals(SetupError.Reason.TOO_LONG, refused(text(password = "x".repeat(Setup.MAX_CHARS))))
    }

    @Test
    fun anAddressMustBelongToTheChain() {
        assertEquals(SetupError.Reason.BAD_ADDRESS, refused(text(addresses = "[\"$regtest\"]")))
        assertEquals(Chain.REGTEST, Setup.parse(text(chain = "regtest", addresses = "[\"$regtest\"]")).chain)
        // one changed character: the checksum catches it
        assertEquals(SetupError.Reason.BAD_ADDRESS, refused(text(addresses = "[\"${main.dropLast(1)}y\"]")))
        assertEquals(SetupError.Reason.BAD_ADDRESS, refused(text(addresses = "[\"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4\"]")))
        assertEquals(SetupError.Reason.BAD_ADDRESS, refused(text(addresses = "[17]")))
        assertEquals(SetupError.Reason.TOO_MANY_ADDRESSES, refused(text(addresses = "[" + List(21) { "\"$main\"" }.joinToString(",") + "]")))
        assertEquals(1, Setup.parse(text(addresses = "[\"$main\",\"$main\"]")).addresses.size)
    }
}
