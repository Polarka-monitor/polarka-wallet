package north.core

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
import java.security.MessageDigest

/**
 * A check with known answers that can run on any device, a phone included: it derives an address and signs a
 * transaction with the keys of the public BIP39 test phrase and compares both with the published answers.
 * It shows that the key code and the native library behind it work where the wallet runs. It uses no key of
 * the user.
 *
 * The answers come from vectors/p2qrh-test-vectors.json of the cold address repository; the transaction is the
 * one TrueNorth Core accepted and mined on regtest when the vectors were made. VectorsTest keeps the constants
 * below equal to that file.
 */
object SelfCheck {
    val WORDS: List<String> =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    const val MAIN_ADDRESS = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"
    const val TXID = "6c3bb7c85cafd957f135f8168ca9365b0de76e918cec602b15cb6aad6e701c11"

    /** SHA-256 of the whole signed transaction, signatures included (the transaction id does not cover them). */
    const val SIGNED_SHA256 = "fc7ce760561bfaa0024ddc5a1427a8816fceca0b320a5498039e84a390b674f7"

    /**
     * Null when every answer agrees, otherwise a short note on the first one that differs.
     * The published signatures were made with 32 zero bytes of BIP-340 auxiliary randomness, which is the
     * default here; any other value gives valid but different signatures and is only of use to a test.
     */
    fun run(auxRand: ByteVector32 = ByteVector32(ByteArray(32))): String? {
        val address = Account.fromWords(WORDS, Chain.MAIN).address(0, 0)
        if (address != MAIN_ADDRESS) return "address differs: $address"

        val coins = listOf(
            Coin("70c25483749d1e260f5ca7006250cfea53c47113239f6d1bdda6cbca864add92", 0, 51_200_000_000L, 0, 0),
            Coin("fd45cd0a193cf11f9d65309ab79970c268d09692a08c46054606bbceb83f190c", 0, 51_200_000_000L, 0, 1),
        )
        val payments = listOf(
            Payment(bytes("522018638a201b7882383ad1a88e1f50bcbab97fa0fee8f5bb42b57bdac56541c030"), 100_000_000_000L),
            Payment(bytes("5220977b680678dadc6c2f6b5f831af475900cd9e607d0b549e66f870bc89cf9d110"), 2_399_980_000L),
        )
        val spend = Spend.sign(Account.fromWords(WORDS, Chain.REGTEST), coins, payments, auxRand)
        if (spend.txid != TXID) return "transaction differs: ${spend.txid}"
        val digest = MessageDigest.getInstance("SHA-256").digest(Transaction.write(spend.transaction))
            .joinToString("") { "%02x".format(it) }
        if (digest != SIGNED_SHA256) return "signature differs: $digest"
        return null
    }

    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
