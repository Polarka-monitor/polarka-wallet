package io.github.severka_monitor.northwatch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import north.core.Chain
import north.core.WatchKey
import north.node.NodeClient
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the key that locks a sealed file lives. */
interface KeyHolder {
    /** The key, or null if there is none and [create] is false. */
    fun key(create: Boolean): SecretKey?
    fun delete()
}

/**
 * One file, encrypted with AES-256-GCM. The file is: version (1 byte), nonce length (1 byte), nonce,
 * ciphertext with its tag. A changed file, a file sealed for another purpose or with another key is refused
 * as a whole; nothing half-read is ever used.
 *
 * It works in two steps, so that a key which needs the owner's fingerprint can be used: make the cipher,
 * let the owner unlock it, then seal or unseal. For a key that needs no unlocking, [write] and [read] do both.
 */
class SealedBytes(private val file: File, private val keys: KeyHolder, purpose: String) {
    private val aad = purpose.encodeToByteArray()

    fun exists(): Boolean = file.exists()

    fun encryptor(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, checkNotNull(keys.key(create = true)))       // the provider picks a fresh random nonce
    }

    fun seal(cipher: Cipher, plain: ByteArray) {
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected nonce length" }
        // written to the side, forced onto the disk, then moved into place: a power loss leaves the old file or the new one
        synchronized(WRITING) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(byteArrayOf(VERSION, IV_BYTES.toByte()) + iv + sealed)
                out.flush()
                runCatching { out.fd.sync() }
            }
            check(tmp.renameTo(file)) { "the file could not be written" }
        }
    }

    fun decryptor(): Cipher {
        val raw = header()
        val key = checkNotNull(keys.key(create = false)) { "the key for the stored file is gone" }
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 2, IV_BYTES)) }
    }

    fun unseal(cipher: Cipher): ByteArray {
        val raw = header()
        cipher.updateAAD(aad)
        return cipher.doFinal(raw, 2 + IV_BYTES, raw.size - 2 - IV_BYTES)
    }

    fun write(plain: ByteArray) = seal(encryptor(), plain)

    fun read(): ByteArray? = if (file.exists()) unseal(decryptor()) else null

    fun delete() {
        file.delete()
        keys.delete()
    }

    private fun header(): ByteArray {
        val raw = file.readBytes()
        require(raw.size > 2 + IV_BYTES && raw[0] == VERSION && raw[1].toInt() == IV_BYTES) { "the stored file has an unknown form" }
        return raw
    }

    private companion object {
        val WRITING = Any()
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Where the node setup is kept between runs. */
interface SetupStore {
    fun load(): Setup?
    fun save(setup: Setup)
    fun clear()
}

class SealedFileStore(file: File, keys: KeyHolder) : SetupStore {
    private val sealed = SealedBytes(file, keys, "north-watch-setup/1")
    override fun load(): Setup? = sealed.read()?.let { Setup.parse(it.decodeToString()) }
    override fun save(setup: Setup) = sealed.write(setup.toJson().encodeToByteArray())
    override fun clear() = sealed.delete()
}

/**
 * A payment that left this phone and is not in a block yet. It is written down before the transaction is
 * handed to the node, so that the app knows about it even if it is closed, or if the node's answer is lost.
 * [unsure] is true until the node has been seen to hold the transaction. [inputs] are the coins it spends
 * ("txid:n"); they are not offered for another payment.
 */
data class Pending(val txid: String, val sentSat: Long, val feeSat: Long, val inputs: Set<String>, val unsure: Boolean = false, val atMillis: Long = 0)

/**
 * What the app keeps about its own wallet without any secret: enough to show it, not enough to spend.
 * The account key shows every address of the wallet, so it is stored sealed like the node password.
 */
data class WalletInfo(
    val chain: Chain, val xpub: String, val receiveIndex: Int = 0, val changeIndex: Int = 0,
    val hashrate: String = "", val price: String = "", val currency: String = "", val lockOnOpen: Boolean = true,
    val pending: List<Pending> = emptyList(),
) {
    fun watchKey(): WatchKey = WatchKey.parse(xpub, chain)

    /** How many keys of each branch the node is asked about: everything used so far and twenty beyond. */
    val window: Int get() = maxOf(receiveIndex, changeIndex) + GAP

    fun toJson(): String = buildJsonObject {
        put("format", JsonPrimitive(FORMAT))
        put("chain", JsonPrimitive(Setup.nameOf(chain)))
        put("xpub", JsonPrimitive(xpub))
        put("receiveIndex", JsonPrimitive(receiveIndex))
        put("changeIndex", JsonPrimitive(changeIndex))
        put("hashrate", JsonPrimitive(hashrate))
        put("price", JsonPrimitive(price))
        put("currency", JsonPrimitive(currency))
        put("lockOnOpen", JsonPrimitive(lockOnOpen))
        put("pending", buildJsonArray {
            pending.forEach { p ->
                add(buildJsonObject {
                    put("txid", JsonPrimitive(p.txid))
                    put("sent", JsonPrimitive(p.sentSat))
                    put("fee", JsonPrimitive(p.feeSat))
                    put("inputs", buildJsonArray { p.inputs.forEach { add(JsonPrimitive(it)) } })
                    put("unsure", JsonPrimitive(p.unsure))
                    put("at", JsonPrimitive(p.atMillis))
                })
            }
        })
    }.toString()

    companion object {
        const val FORMAT = "north-wallet-info/1"
        const val GAP = 20
        const val MAX_PENDING = 200

        fun parse(text: String): WalletInfo {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun s(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(s("format") == FORMAT) { "unknown form of the wallet file" }
            val chain = Chain.entries.single { Setup.nameOf(it) == s("chain") }
            val info = WalletInfo(
                chain, s("xpub")!!, (o["receiveIndex"] as JsonPrimitive).intOrNull ?: 0, (o["changeIndex"] as JsonPrimitive).intOrNull ?: 0,
                s("hashrate").orEmpty(), s("price").orEmpty(), s("currency").orEmpty(), (o["lockOnOpen"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
            require(info.receiveIndex in 0..100_000 && info.changeIndex in 0..100_000) { "a key number is out of bounds" }
            info.watchKey()                                 // refuses a key of another network or a damaged one
            val waiting = (o["pending"] as? JsonArray).orEmpty().map { it as JsonObject }.map { w ->
                fun text(name: String) = (w[name] as JsonPrimitive).content
                val inputs = (w["inputs"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
                val p = Pending(
                    text("txid"), (w["sent"] as JsonPrimitive).longOrNull ?: -1, (w["fee"] as JsonPrimitive).longOrNull ?: -1, inputs,
                    (w["unsure"] as? JsonPrimitive)?.booleanOrNull ?: false, (w["at"] as? JsonPrimitive)?.longOrNull ?: 0,
                )
                require(NodeClient.isId(p.txid) && p.sentSat in 0..NodeClient.MAX_SAT && p.feeSat in 0..NodeClient.MAX_SAT) { "a waiting payment is damaged" }
                require(inputs.isNotEmpty() && inputs.all { NodeClient.isId(it.substringBefore(':')) && it.substringAfter(':', "").toLongOrNull() in 0..NodeClient.MAX_VOUT }) { "a waiting payment is damaged" }
                p
            }
            require(waiting.size <= MAX_PENDING) { "too many waiting payments" }
            return info.copy(pending = waiting)
        }
    }
}

interface WalletStore {
    fun load(): WalletInfo?
    fun save(info: WalletInfo)
    fun clear()
}

class SealedWalletStore(file: File, keys: KeyHolder) : WalletStore {
    private val sealed = SealedBytes(file, keys, WalletInfo.FORMAT)
    override fun load(): WalletInfo? = sealed.read()?.let { WalletInfo.parse(it.decodeToString()) }
    override fun save(info: WalletInfo) = sealed.write(info.toJson().encodeToByteArray())
    override fun clear() = sealed.delete()
}

/**
 * The recovery words. They are sealed with a key that the phone releases only after its owner unlocks it
 * (fingerprint or screen lock), each time anew: the app cannot read the words on its own.
 */
class WordVault(private val sealed: SealedBytes) {
    fun exists(): Boolean = sealed.exists()
    fun encryptor(): Cipher = sealed.encryptor()
    fun store(unlocked: Cipher, words: List<String>) = sealed.seal(unlocked, words.joinToString(" ").encodeToByteArray())
    fun decryptor(): Cipher = sealed.decryptor()
    fun open(unlocked: Cipher): List<String> = sealed.unseal(unlocked).decodeToString().split(" ")
    fun destroy() = sealed.delete()
}

/**
 * A key made inside the Android Keystore. It never leaves it: the app can ask the system to encrypt and
 * decrypt, but it cannot read the key, and neither can a copy of the file taken off the phone. The app is
 * also excluded from backups (see the manifest), so the files do not travel to a cloud or to another phone.
 *
 * With [ownerOnly] the system uses the key only right after the owner has unlocked it with a fingerprint or
 * the screen lock, once per use. The key is asked for in the phone's separate security chip (StrongBox)
 * first; a phone without one keeps it in the processor's secure area.
 */
class AndroidKeystoreKey(private val alias: String, private val ownerOnly: Boolean = false) : KeyHolder {
    override fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            if (ownerOnly) {
                spec.setUserAuthenticationRequired(true)
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                spec.setInvalidatedByBiometricEnrollment(false)     // the screen lock opens it anyway; a new fingerprint must not lock the owner out
            }
            if (strongBox) spec.setIsStrongBoxBacked(true)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec.build()) }.generateKey()
        }
        // Phones differ in how they say that they have no separate security chip, or that it cannot make this key.
        // Whatever the reason, the processor's secure area is tried next; if that fails too, its error is the one reported.
        return try {
            generate(strongBox = true)
        } catch (e: Exception) {
            generate(strongBox = false)
        }
    }

    override fun delete() {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

/** The one place that decides where things are kept; a test puts its own stores here. */
object Stores {
    val default: (Context) -> SetupStore = { SealedFileStore(File(it.applicationContext.filesDir, "setup.bin"), AndroidKeystoreKey("north-watch.setup.v1")) }
    val defaultWallet: (Context) -> WalletStore = { SealedWalletStore(File(it.applicationContext.filesDir, "wallet.bin"), AndroidKeystoreKey("north-watch.wallet.v1")) }
    val defaultWords: (Context) -> WordVault = {
        WordVault(SealedBytes(File(it.applicationContext.filesDir, "words.bin"), AndroidKeystoreKey("north-watch.words.v1", ownerOnly = true), "north-wallet-words/1"))
    }

    @Volatile var open: (Context) -> SetupStore = default
    @Volatile var wallet: (Context) -> WalletStore = defaultWallet
    @Volatile var words: (Context) -> WordVault = defaultWords
}
