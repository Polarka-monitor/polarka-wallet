package north.core

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut

/** A P2QRH output of this wallet that is to be spent. */
data class Coin(val txid: String, val vout: Long, val amountSat: Long, val branch: Int, val index: Int, val sequence: Long = 0xfffffffdL)

/** Where money goes: an output script and an amount. */
class Payment(val script: ByteArray, val amountSat: Long)

/** A finished transaction with the figures a person must see before it is sent. */
class SignedSpend(val transaction: Transaction, val feeSat: Long, val sighashes: List<ByteVector32>) {
    val hex: String get() = Transaction.write(transaction).joinToString("") { "%02x".format(it) }
    val txid: String get() = transaction.txid.value.toHex()
    val vsize: Int get() = (transaction.weight() + 3) / 4
}

object Spend {
    /**
     * The virtual size in vbytes that a transaction with this many P2QRH inputs and these outputs will have.
     * It is exact, not an estimate: every P2QRH witness has the same size (64 + 32 + 1 bytes and their length
     * prefixes), so the fee can be fixed before anything is signed.
     */
    fun vsize(inputCount: Int, outputScripts: List<ByteArray>): Int {
        require(inputCount > 0 && outputScripts.isNotEmpty())
        val witness = ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(ByteArray(32)), ByteVector(ByteArray(1))))
        val tx = Transaction(
            2,
            List(inputCount) { TxIn(OutPoint(TxId(ByteArray(32)), it.toLong()), ByteVector.empty, 0xfffffffdL, witness) },
            outputScripts.map { TxOut(Satoshi(0), it) },
            0,
        )
        return (tx.weight() + 3) / 4
    }

    /**
     * Builds and signs a transaction that spends P2QRH outputs of this account.
     *
     * Each input is signed over the BIP-341 key path signature hash (SIGHASH_DEFAULT, no annex) with BIP-340
     * Schnorr. Its witness is: signature (64 bytes), x-only public key (32 bytes), scheme id (0x01).
     * auxRand is the BIP-340 auxiliary randomness; the test vectors use 32 zero bytes, a wallet passes fresh
     * random bytes.
     */
    fun sign(account: Account, coins: List<Coin>, payments: List<Payment>, auxRand: ByteVector32, version: Long = 2, lockTime: Long = 0): SignedSpend {
        require(coins.isNotEmpty()) { "nothing to spend" }
        require(payments.isNotEmpty()) { "nowhere to pay" }
        require(coins.map { it.txid to it.vout }.toSet().size == coins.size) { "the same output is listed twice" }
        // Every amount, and each of the two sums, is one that can exist; the sums cannot wrap around a Long, so
        // the fee below is the real difference (review 9, M-3).
        var inputs = 0L
        for (c in coins) {
            require(c.amountSat in 0..Planner.MAX_SAT) { "an input with an amount that cannot exist" }
            inputs += c.amountSat
            require(inputs <= Planner.MAX_SAT) { "inputs whose amounts cannot exist together" }
        }
        var outputs = 0L
        for (p in payments) {
            require(p.amountSat in 1..Planner.MAX_SAT) { "a payment must be positive and an amount that can exist" }
            outputs += p.amountSat
            require(outputs <= Planner.MAX_SAT) { "payments whose amounts cannot exist together" }
        }
        val fee = inputs - outputs
        require(fee >= 0) { "the payments exceed the inputs" }

        val spent = coins.map { TxOut(Satoshi(it.amountSat), account.script(it.branch, it.index)) }
        val unsigned = Transaction(
            version,
            coins.map { TxIn(OutPoint(TxId(it.txid), it.vout), ByteVector.empty, it.sequence, ScriptWitness.empty) },
            payments.map { TxOut(Satoshi(it.amountSat), it.script) },
            lockTime,
        )
        val hashes = coins.indices.map { Transaction.hashForSigningTaprootKeyPath(unsigned, it, spent, SigHash.SIGHASH_DEFAULT, null) }
        val signedInputs = coins.mapIndexed { i, coin ->
            val key = account.privateKey(coin.branch, coin.index)
            val signature = Crypto.signSchnorr(hashes[i], key, null, auxRand)
            val xOnly = key.xOnlyPublicKey()
            check(Crypto.verifySignatureSchnorr(hashes[i], signature, xOnly)) { "the signature just made does not verify" }
            val witness = ScriptWitness(listOf(signature, xOnly.value, ByteVector(byteArrayOf(Qrh.SCHEME_SCHNORR))))
            unsigned.txIn[i].copy(witness = witness)
        }
        return SignedSpend(unsigned.copy(txIn = signedInputs), fee, hashes)
    }
}
