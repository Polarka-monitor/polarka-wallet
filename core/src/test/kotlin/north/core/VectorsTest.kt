package north.core

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Everything here is compared byte for byte with vectors/p2qrh-test-vectors.json of the cold address
 * repository. That file was made by Python code that shares nothing with this code or with TrueNorth Core,
 * and every value in it was checked against the official v30.5.4 programs.
 */
class VectorsTest {
    private val raw = javaClass.getResourceAsStream("/p2qrh-test-vectors.json")!!.readBytes()
    private val v = Json.parseToJsonElement(raw.decodeToString()).jsonObject
    private fun JsonObject.s(name: String) = this[name]!!.jsonPrimitive.content
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private val words = v.s("mnemonic").split(" ")

    @Test
    fun theVectorFileIsThePublishedOne() {
        val sha = MessageDigest.getInstance("SHA-256").digest(raw).hex()
        assertEquals("b5c8e06c2ac4c0eaa81fbc42d4237cc1ff5a256beee0915e1e29815e1c751e13", sha)
    }

    @Test
    fun seedAndMasterKey() {
        assertEquals(v.s("seed"), fr.acinq.bitcoin.MnemonicCode.toSeed(words, "").hex())
        for ((name, chain) in listOf("main" to Chain.MAIN, "regtest" to Chain.REGTEST)) {
            val c = v["chains"]!!.jsonObject[name]!!.jsonObject
            assertEquals(c.s("hrp"), chain.hrp)
            assertEquals(c["coin_type"]!!.jsonPrimitive.long, chain.coinType)
            assertEquals(c.s("master_xprv"), Account.fromWords(words, chain).masterXprv())
        }
    }

    @Test
    fun keysCommitmentsScriptsAndAddresses() {
        var n = 0
        for ((name, chain) in listOf("main" to Chain.MAIN, "regtest" to Chain.REGTEST)) {
            val account = Account.fromWords(words, chain)
            for (k in v["chains"]!!.jsonObject[name]!!.jsonObject["keys"]!!.jsonArray.map { it.jsonObject }) {
                val (branch, index) = k.s("path").split("/").takeLast(2).map { it.toInt() }
                assertEquals(k.s("private_key"), account.privateKey(branch, index).value.toHex(), k.s("path"))
                assertEquals(k.s("xonly_pubkey"), account.xOnlyPublicKey(branch, index).hex())
                assertEquals(k.s("commitment"), account.commitment(branch, index).hex())
                assertEquals(k.s("scriptPubKey"), account.script(branch, index).hex())
                assertEquals(k.s("address"), account.address(branch, index))
                assertEquals(k.s("scriptPubKey"), Qrh.scriptForAddress(chain, k.s("address")).hex())
                n++
            }
        }
        assertEquals(10, n)
    }

    @Test
    fun addressesOfAnotherNetworkAndDamagedOnesAreRefused() {
        val main = Account.fromWords(words, Chain.MAIN).address(0, 0)
        val reg = Account.fromWords(words, Chain.REGTEST).address(0, 0)
        assertFailsWith<IllegalArgumentException> { Qrh.scriptForAddress(Chain.REGTEST, main) }
        assertFailsWith<IllegalArgumentException> { Qrh.scriptForAddress(Chain.MAIN, reg) }
        val damaged = main.dropLast(1) + (if (main.last() == 'q') 'p' else 'q')
        assertFailsWith<Exception> { Qrh.scriptForAddress(Chain.MAIN, damaged) }
        assertFailsWith<Exception> { Qrh.scriptForAddress(Chain.MAIN, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4") }
        // the reasons must be the right ones
        assertEquals("the address belongs to another network (prefix north, expected rnorth)",
            assertFailsWith<IllegalArgumentException> { Qrh.scriptForAddress(Chain.REGTEST, main) }.message)
        // a version 2 program under the old bech32 checksum, a 20-byte program, non-zero padding, version 3
        val c = Account.fromWords(words, Chain.MAIN).commitment(0, 0)
        val five = fr.acinq.bitcoin.Bech32.eight2five(c)
        fun addr(version: Int, groups: Array<Byte>, enc: fr.acinq.bitcoin.Bech32.Encoding) =
            fr.acinq.bitcoin.Bech32.encode("north", arrayOf(version.toByte()) + groups, enc)
        assertEquals(main, addr(2, five, fr.acinq.bitcoin.Bech32.Encoding.Bech32m))
        assertEquals("wrong checksum type for this address version",
            assertFailsWith<IllegalArgumentException> { Qrh.scriptForAddress(Chain.MAIN, addr(2, five, fr.acinq.bitcoin.Bech32.Encoding.Bech32)) }.message)
        assertEquals("unsupported address type (witness version 2, 20 bytes)",
            assertFailsWith<IllegalArgumentException> {
                Qrh.scriptForAddress(Chain.MAIN, addr(2, fr.acinq.bitcoin.Bech32.eight2five(c.copyOf(20)), fr.acinq.bitcoin.Bech32.Encoding.Bech32m))
            }.message)
        val padded = five.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() or 1).toByte() }
        assertEquals("the address has invalid padding",
            assertFailsWith<IllegalArgumentException> { Qrh.scriptForAddress(Chain.MAIN, addr(2, padded, fr.acinq.bitcoin.Bech32.Encoding.Bech32m)) }.message)
        assertEquals("unsupported address type (witness version 3, 32 bytes)",
            assertFailsWith<IllegalArgumentException> { Qrh.scriptForAddress(Chain.MAIN, addr(3, five, fr.acinq.bitcoin.Bech32.Encoding.Bech32m)) }.message)
        // the other witness types Core accepts: version 0 (20 and 32 bytes) and version 1
        assertEquals("0014" + c.copyOf(20).hex(),
            Qrh.scriptForAddress(Chain.MAIN, addr(0, fr.acinq.bitcoin.Bech32.eight2five(c.copyOf(20)), fr.acinq.bitcoin.Bech32.Encoding.Bech32)).hex())
        assertEquals("0020" + c.hex(), Qrh.scriptForAddress(Chain.MAIN, addr(0, five, fr.acinq.bitcoin.Bech32.Encoding.Bech32)).hex())
        assertEquals("5120" + c.hex(), Qrh.scriptForAddress(Chain.MAIN, addr(1, five, fr.acinq.bitcoin.Bech32.Encoding.Bech32m)).hex())
    }

    @Test
    fun theSignedTransactionIsTheOneTrueNorthCoreMined() {
        val s = v["spend"]!!.jsonObject
        val account = Account.fromWords(words, Chain.REGTEST)
        val coins = s["inputs"]!!.jsonArray.map { it.jsonObject }.map {
            val (branch, index) = it.s("key_path").split("/").takeLast(2).map { p -> p.toInt() }
            Coin(it.s("txid"), it["vout"]!!.jsonPrimitive.long, it["amount_sat"]!!.jsonPrimitive.long, branch, index,
                it["sequence"]!!.jsonPrimitive.long)
        }
        val payments = s["outputs"]!!.jsonArray.map { it.jsonObject }.map {
            Payment(it.s("scriptPubKey").chunked(2).map { b -> b.toInt(16).toByte() }.toByteArray(), it["amount_sat"]!!.jsonPrimitive.long)
        }
        val aux = ByteVector32(s.s("bip340_aux_rand"))
        val spend = Spend.sign(account, coins, payments, aux, s["version"]!!.jsonPrimitive.long, s["locktime"]!!.jsonPrimitive.long)

        assertEquals(s["signature_hashes"]!!.jsonArray.map { it.jsonPrimitive.content }, spend.sighashes.map { it.toHex() })
        assertEquals(s["signatures"]!!.jsonArray.map { it.jsonPrimitive.content },
            spend.transaction.txIn.map { it.witness.stack[0].toHex() })
        assertEquals(s.s("signed_tx"), spend.hex)
        assertEquals(s.s("txid"), spend.txid)
        assertEquals(s["vsize"]!!.jsonPrimitive.long.toInt(), spend.vsize)
        assertEquals(s["fee_sat"]!!.jsonPrimitive.long, spend.feeSat)
        val unsigned = spend.transaction.copy(txIn = spend.transaction.txIn.map { it.copy(witness = fr.acinq.bitcoin.ScriptWitness.empty) })
        assertEquals(s.s("unsigned_tx"), Transaction.write(unsigned).hex())
    }

    @Test
    fun theBuiltInSelfCheckHoldsThePublishedAnswers() {
        val s = v["spend"]!!.jsonObject
        assertEquals(words, SelfCheck.WORDS)
        assertEquals(v["chains"]!!.jsonObject["main"]!!.jsonObject["keys"]!!.jsonArray[0].jsonObject.s("address"), SelfCheck.MAIN_ADDRESS)
        assertEquals(s.s("txid"), SelfCheck.TXID)
        val signed = s.s("signed_tx").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(MessageDigest.getInstance("SHA-256").digest(signed).hex(), SelfCheck.SIGNED_SHA256)
        assertEquals(null, SelfCheck.run())
        // a valid signature that is not the published one must be noticed; the transaction id alone would not show it
        val other = SelfCheck.run(ByteVector32("0100000000000000000000000000000000000000000000000000000000000000"))
        assertEquals(true, other != null && other.startsWith("signature differs"), other)
        println("SELFCHECK OK: built-in answers equal the vector file; another signature is noticed")
    }
}
