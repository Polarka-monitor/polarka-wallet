package north.core

import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey

/** The checksum that TrueNorth Core (like Bitcoin Core) puts after a descriptor: eight characters after '#'. */
object Descriptor {
    private const val INPUT = "0123456789()[],'/*abcdefgh@:\$%{}" +
        "IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~" +
        "ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
    private const val OUTPUT = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun polymod(c: Long, value: Int): Long {
        val top = (c shr 35).toInt()
        var r = ((c and 0x7ffffffffL) shl 5) xor value.toLong()
        if (top and 1 != 0) r = r xor 0xf5dee51989L
        if (top and 2 != 0) r = r xor 0xa9fdca3312L
        if (top and 4 != 0) r = r xor 0x1bab10e32dL
        if (top and 8 != 0) r = r xor 0x3706b1677aL
        if (top and 16 != 0) r = r xor 0x644d626ffdL
        return r
    }

    fun checksum(body: String): String {
        var c = 1L
        var group = 0
        var count = 0
        for (ch in body) {
            val pos = INPUT.indexOf(ch)
            require(pos >= 0) { "a descriptor cannot contain the character '$ch'" }
            c = polymod(c, pos and 31)
            group = group * 3 + (pos shr 5)
            if (++count == 3) {
                c = polymod(c, group)
                group = 0
                count = 0
            }
        }
        if (count > 0) c = polymod(c, group)
        repeat(8) { c = polymod(c, 0) }
        c = c xor 1
        return (0 until 8).map { OUTPUT[((c shr (5 * (7 - it))) and 31).toInt()] }.joinToString("")
    }

    fun withChecksum(body: String): String = body + "#" + checksum(body)
}

/**
 * The public half of an account. Every address of the wallet can be derived from it; nothing can be spent
 * with it. It is what the phone gives its node (inside descriptors) and what a watch-only copy needs.
 *
 * It is written the way TrueNorth Core and the cold address tool write it: the account key as a key of
 * depth 0, so that the descriptors are qrh(KEY/0/STAR) for receiving and qrh(KEY/1/STAR) for change. A
 * descriptor that still has the hardened steps after the key cannot be used by a node without private keys.
 */
class WatchKey private constructor(private val key: DeterministicWallet.ExtendedPublicKey, val chain: Chain) {
    /** The extended public key of the account (Tpub... on mainnet). Not a secret that can spend, but it shows the whole wallet. */
    val xpub: String get() = key.encode(chain.xpubPrefix)

    fun xOnlyPublicKey(branch: Int, index: Int): ByteArray {
        require(branch == 0 || branch == 1) { "branch is 0 (receive) or 1 (change)" }
        require(index >= 0) { "index is not negative" }
        return key.derivePublicKey(listOf(branch.toLong(), index.toLong())).publicKey.xOnly().value.toByteArray()
    }

    fun commitment(branch: Int, index: Int): ByteArray = Qrh.commitment(xOnlyPublicKey(branch, index))

    fun script(branch: Int, index: Int): ByteArray = Qrh.script(commitment(branch, index))

    fun address(branch: Int, index: Int): String = Qrh.address(chain, commitment(branch, index))

    /** One branch as a descriptor with its checksum, as scantxoutset, scanblocks and importdescriptors read it. */
    fun descriptor(branch: Int): String {
        require(branch == 0 || branch == 1) { "branch is 0 (receive) or 1 (change)" }
        return Descriptor.withChecksum("qrh($xpub/$branch/*)")
    }

    companion object {
        /** From the key of account m/88h/COINh/0h; the place in the tree is dropped, as Core's wallet does. */
        fun of(accountKey: DeterministicWallet.ExtendedPublicKey, chain: Chain): WatchKey =
            WatchKey(DeterministicWallet.ExtendedPublicKey(accountKey.publickeybytes, accountKey.chaincode, 0, KeyPath(emptyList()), 0L), chain)

        /**
         * From the text form. A key of another network, or one that is not written at depth 0, is refused.
         * It is read here and not by the library, which knows only Bitcoin's own version bytes.
         */
        fun parse(xpub: String, chain: Chain): WatchKey {
            val (prefix, data) = Base58Check.decodeWithIntPrefix(xpub.trim())
            require(prefix == chain.xpubPrefix) { "the key belongs to another network" }
            require(data.size == 74) { "this is not an extended public key" }
            require(data.take(9).all { it == 0.toByte() }) { "the key is not an account key in the form this wallet uses" }
            val public = PublicKey(data.copyOfRange(41, 74))
            require(public.isValid()) { "this is not a valid public key" }
            return WatchKey(DeterministicWallet.ExtendedPublicKey(public.value, ByteVector32(data.copyOfRange(9, 41)), 0, KeyPath(emptyList()), 0L), chain)
        }
    }
}
