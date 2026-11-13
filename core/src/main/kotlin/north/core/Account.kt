package north.core

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey

/**
 * The keys of one wallet: account m/88h/COINh/0h, as TrueNorth Core derives them for its qrh() descriptors.
 * Branch 0 is for receiving, branch 1 for change.
 */
class Account private constructor(
    private val master: DeterministicWallet.ExtendedPrivateKey,
    val chain: Chain,
) {
    private val account = master.derivePrivateKey(
        listOf(DeterministicWallet.hardened(88), DeterministicWallet.hardened(chain.coinType), DeterministicWallet.hardened(0))
    )

    fun privateKey(branch: Int, index: Int): PrivateKey {
        require(branch == 0 || branch == 1) { "branch is 0 (receive) or 1 (change)" }
        require(index >= 0) { "index is not negative" }
        return account.derivePrivateKey(listOf(branch.toLong(), index.toLong())).privateKey
    }

    fun xOnlyPublicKey(branch: Int, index: Int): ByteArray = privateKey(branch, index).xOnlyPublicKey().value.toByteArray()

    fun commitment(branch: Int, index: Int): ByteArray = Qrh.commitment(xOnlyPublicKey(branch, index))

    fun script(branch: Int, index: Int): ByteArray = Qrh.script(commitment(branch, index))

    fun address(branch: Int, index: Int): String = Qrh.address(chain, commitment(branch, index))

    /** The public half of the account: shows every address and balance, cannot spend. */
    fun watchKey(): WatchKey = WatchKey.of(account.extendedPublicKey, chain)

    /** The master extended private key in the form TrueNorth Core reads (Tprv... on mainnet). As secret as the words. */
    fun masterXprv(): String = master.encode(chain.xprvPrefix)

    companion object {
        fun fromSeed(seed: ByteArray, chain: Chain): Account {
            require(seed.size == 64) { "a BIP39 seed has 64 bytes" }
            return Account(DeterministicWallet.generate(seed), chain)
        }

        /** From BIP39 words (English list, no passphrase). Throws if the words are not a valid phrase. */
        fun fromWords(words: List<String>, chain: Chain): Account {
            MnemonicCode.validate(words)
            return fromSeed(MnemonicCode.toSeed(words, ""), chain)
        }
    }
}
