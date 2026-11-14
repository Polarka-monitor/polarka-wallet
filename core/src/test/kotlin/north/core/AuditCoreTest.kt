package north.core

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent audit of 0.3.1. Adversarial checks of the parts that need no node.
 * Every test in this class describes behaviour that is expected to hold; a failure is a finding.
 */
class AuditCoreTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val main = Account.fromWords(words, Chain.MAIN)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** True if the text is taken as an address. Only an Exception may refuse it: the app catches nothing else. */
    private fun accepted(text: String): Boolean = try {
        Qrh.scriptForAddress(Chain.MAIN, text)
        true
    } catch (e: Exception) {
        false
    }

    @Test
    fun everySmallDamageToAnAddressIsRefused() {
        val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        var tried = 0
        for (index in 0..2) {
            val address = main.address(0, index)
            val start = address.indexOf('1') + 1
            for (i in start until address.length) for (c in alphabet) {
                if (c == address[i]) continue
                assertFalse(accepted(address.substring(0, i) + c + address.substring(i + 1)), "one changed character was accepted at $i")
                tried++
            }
            for (i in start until address.length - 1) {
                if (address[i] != address[i + 1]) {
                    assertFalse(accepted(address.substring(0, i) + address[i + 1] + address[i] + address.substring(i + 2)), "two swapped characters at $i")
                }
                assertFalse(accepted(address.removeRange(i, i + 1)), "a dropped character at $i")
                assertFalse(accepted(address.substring(0, i) + address[i] + address.substring(i)), "a doubled character at $i")
                tried += 3
            }
        }
        println("AUDIT core: $tried damaged forms of three addresses, all refused")
    }

    @Test
    fun caseSpacesAndForeignFormsOfAnAddress() {
        val a = main.address(0, 0)
        // a QR code often carries the address in capitals: the same script must come out
        assertEquals(hex(main.script(0, 0)), hex(Qrh.scriptForAddress(Chain.MAIN, a.uppercase())))
        val refused = listOf(
            a.substring(0, 20) + a.substring(20).uppercase(),      // mixed case
            "NORTH1" + a.substring(6),
            "", " ", "1", "north1", a + a, "north:$a", "$a?amount=1", a.replace("north1", "rnorth1"), a.replace("north1", "bc1"),
            a + " ", " $a", a + "\n", a + "\u0000", a.replace('q', 'ԛ'),
        )
        val wrong = refused.filter { accepted(it) }
        assertTrue(wrong.isEmpty(), "accepted as an address: $wrong")
    }

    @Test
    fun randomTextIsNeverAnAddress() {
        val random = Random(31)
        val chars = "qpzry9x8gf2tvdw0s3jn54khce6mua7l1northNORTH :?=-_/\\\"'\u0000éřbcio"
        var accepted = 0
        repeat(150_000) {
            val text = buildString { repeat(1 + random.nextInt(90)) { append(chars[random.nextInt(chars.length)]) } }
            if (accepted(text)) accepted++
            if (accepted("north1z$text")) accepted++
        }
        assertEquals(0, accepted)
        println("AUDIT core: 300000 random texts, none taken for an address, none ended in anything but an Exception")
    }

    @Test
    fun onlyTwelveOrTwentyFourGoodWordsAreTaken() {
        assertTrue(Words.valid(words))
        // valid BIP39 phrases of 15, 18 and 21 words exist; the wallet says it takes 12 or 24
        for (bytes in listOf(20, 24, 28)) {
            val phrase = fr.acinq.bitcoin.MnemonicCode.toMnemonics(ByteArray(bytes) { (it * 11 + 1).toByte() })
            assertFalse(Words.valid(phrase), "${phrase.size} words taken")
        }
        // separators that are not plain white space do not split, so the phrase is refused rather than misread
        assertFalse(Words.valid(Words.split(words.joinToString(" "))))
        assertFalse(Words.valid(Words.split(words.joinToString(","))))
        assertEquals(words, Words.split(words.joinToString("\n\t ").uppercase()))
    }

    @Test
    fun aPlanSignsOnlyForTheWalletsOwnScripts() {
        // whatever place a coin is said to be at, the script that is signed for is derived from the account, never taken from outside
        val account = Account.fromWords(words, Chain.REGTEST)
        val coins = listOf(Unspent("%064x".format(1), 0, 5_000_000, 0, 3, 10, false), Unspent("%064x".format(2), 1, 7_000_000, 1, 9, 10, false))
        val plan = Planner.pay(coins, account.script(0, 40), 6_000_000, 2, account.script(1, 2))
        val signed = plan.sign(account)
        val byOutpoint = coins.associateBy { it.txid + ":" + it.vout }
        signed.transaction.txIn.forEach { input ->
            val coin = byOutpoint.getValue(input.outPoint.txid.value.toHex() + ":" + input.outPoint.index)
            assertEquals(hex(account.xOnlyPublicKey(coin.branch, coin.index)), input.witness.stack[1].toHex())
            assertEquals(64, input.witness.stack[0].size())
            assertEquals("01", input.witness.stack[2].toHex())
        }
        assertEquals(plan.inputSat, signed.transaction.txOut.sumOf { it.amount.toLong() } + plan.feeSat)
    }
}
