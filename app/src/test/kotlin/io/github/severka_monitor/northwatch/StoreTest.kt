package io.github.severka_monitor.northwatch

import north.core.Chain
import java.io.File
import java.nio.file.Files
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sealed file, with an ordinary AES key in place of the phone's Keystore key.
 * The Keystore itself (making the key and keeping it) can only be tried on a phone.
 */
class StoreTest {
    private class PlainKey(seed: Int) : KeyHolder {
        private val fixed = SecretKeySpec(ByteArray(32) { (it * 7 + seed).toByte() }, "AES")
        var present = true
        override fun key(create: Boolean): SecretKey? = if (present || create) fixed.also { present = true } else null
        override fun delete() { present = false }
    }

    private val setup = Setup(
        "https://node.example.ts.net:9443/", "phone", "Hidden-Pass-7731", Chain.MAIN,
        listOf("north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"),
    )

    private fun inTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("north-store-test-").toFile()
        try { block(File(dir, "setup.bin")) } finally { dir.deleteRecursively() }
    }

    @Test
    fun whatIsSavedComesBack() = inTempDir { file ->
        val store = SealedFileStore(file, PlainKey(1))
        assertNull(store.load())
        store.save(setup)
        assertEquals(setup, store.load())
        assertEquals(setup, SealedFileStore(file, PlainKey(1)).load())
    }

    @Test
    fun theFileDoesNotShowThePasswordOrTheAddress() = inTempDir { file ->
        SealedFileStore(file, PlainKey(1)).save(setup)
        val raw = file.readBytes()
        val text = raw.decodeToString()
        assertFalse(text.contains("Hidden") || text.contains("north1z") || text.contains("example"))
        assertEquals(2 + 12 + setup.toJson().encodeToByteArray().size + 16, raw.size)
        // saving again uses a new nonce: the two files differ everywhere after the header
        SealedFileStore(file, PlainKey(1)).save(setup)
        val again = file.readBytes()
        assertNotEquals(raw.copyOfRange(2, 14).toList(), again.copyOfRange(2, 14).toList())
    }

    @Test
    fun aChangedFileOrAnotherKeyIsRefused() = inTempDir { file ->
        val store = SealedFileStore(file, PlainKey(1))
        store.save(setup)
        val good = file.readBytes()
        for (position in listOf(0, 1, 2, 13, 14, good.size / 2, good.size - 1)) {
            file.writeBytes(good.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() })
            assertFailsWith<Exception>("byte $position") { store.load() }
        }
        file.writeBytes(good.copyOf(good.size - 1))
        assertFailsWith<Exception> { store.load() }
        file.writeBytes(good)
        assertEquals(setup, store.load())
        assertFailsWith<Exception> { SealedFileStore(file, PlainKey(2)).load() }
    }

    @Test
    fun clearingRemovesTheFileAndTheKey() = inTempDir { file ->
        val keys = PlainKey(1)
        val store = SealedFileStore(file, keys)
        store.save(setup)
        store.clear()
        assertFalse(file.exists())
        assertFalse(keys.present)
        assertNull(store.load())
        // a file without its key (copied from elsewhere) is refused, not silently replaced
        store.save(setup)
        keys.present = false
        assertFailsWith<IllegalStateException> { store.load() }
        assertTrue(file.exists())
    }
}
