package north.core

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.Crypto

/**
 * P2QRH, the address type TrueNorth Core uses for its own wallets (doc/p2qrh.md, v30.5.4).
 *
 * An output is `OP_2 <commitment>`, a witness version 2 program of 32 bytes, where
 * commitment = SHA256("TrueNorth/QRH/v1" || scheme id || x-only public key || script root).
 * Only scheme 0x01 (BIP-340 Schnorr) with an all-zero script root can be spent today, and the scheme id is
 * part of the commitment: an output made with any other scheme id is frozen. This code never makes one.
 */
object Qrh {
    const val SCHEME_SCHNORR: Byte = 0x01
    const val WITNESS_VERSION: Byte = 2
    private val TAG = "TrueNorth/QRH/v1".encodeToByteArray()

    fun commitment(xOnlyPublicKey: ByteArray): ByteArray {
        require(xOnlyPublicKey.size == 32) { "an x-only public key has 32 bytes" }
        return Crypto.sha256(TAG + byteArrayOf(SCHEME_SCHNORR) + xOnlyPublicKey + ByteArray(32))
    }

    /** The output script: OP_2, a push of 32 bytes, the commitment. */
    fun script(commitment: ByteArray): ByteArray {
        require(commitment.size == 32) { "a commitment has 32 bytes" }
        return byteArrayOf(0x52, 0x20) + commitment
    }

    fun address(chain: Chain, commitment: ByteArray): String =
        Bech32.encodeWitnessAddress(chain.hrp, WITNESS_VERSION, commitment)

    /**
     * The output script for an address of this chain. Accepts the witness address types TrueNorth Core accepts
     * (version 0 with 20 or 32 bytes, version 1 and version 2 with 32 bytes); anything else is refused, so that
     * a typo or an address of another network can never become a payment. The rules are those of BIP-173 and
     * BIP-350: version 0 uses the bech32 checksum, later versions bech32m, and the padding must be zero bits.
     */
    fun scriptForAddress(chain: Chain, address: String): ByteArray {
        val (hrp, data, encoding) = Bech32.decode(address, false)
        require(hrp == chain.hrp) { "the address belongs to another network (prefix $hrp, expected ${chain.hrp})" }
        require(data.isNotEmpty()) { "the address is empty" }
        val v = data[0].toInt()
        val program = fiveToEight(data.drop(1))
        require(encoding == (if (v == 0) Bech32.Encoding.Bech32 else Bech32.Encoding.Bech32m)) { "wrong checksum type for this address version" }
        require((v == 0 && (program.size == 20 || program.size == 32)) || ((v == 1 || v == 2) && program.size == 32)) {
            "unsupported address type (witness version $v, ${program.size} bytes)"
        }
        val op = if (v == 0) 0x00 else 0x50 + v
        return byteArrayOf(op.toByte(), program.size.toByte()) + program
    }

    /** Groups of 5 bits to bytes, strictly: at most 4 bits of padding, and they must be zero. */
    private fun fiveToEight(groups: List<Byte>): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (g in groups) {
            require(g in 0..31) { "not a 5-bit value" }
            acc = (acc shl 5) or g.toInt()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xff).toByte())
            }
        }
        require(bits < 5 && (acc and ((1 shl bits) - 1)) == 0) { "the address has invalid padding" }
        return out.toByteArray()
    }
}
