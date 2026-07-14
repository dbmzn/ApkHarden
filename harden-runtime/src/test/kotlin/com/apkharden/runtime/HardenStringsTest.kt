package com.apkharden.runtime

import com.apkharden.crypto.StringCrypto
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HardenStringsTest {
    @AfterEach
    fun reset() {
        HardenStrings.clear()
    }

    @Test
    fun `decode requires an installed table`() {
        assertThrows(IllegalStateException::class.java) {
            HardenStrings.decode(0)
        }
    }

    @Test
    fun `decode is lazy and caches the same plaintext instance`() {
        val valid = encrypted(0, "first")
        val tampered = encrypted(1, "second").copy(
            ciphertext = encrypted(1, "second").ciphertext.also {
                it[0] = (it[0].toInt() xor 1).toByte()
            },
        )
        val table = table(valid, tampered)

        HardenStrings.install(CONFIG, table)
        val first = HardenStrings.decode(0)
        val cached = HardenStrings.decode(0)

        assertEquals("first", first)
        assertSame(first, cached)
        assertThrows(AEADBadTagException::class.java) {
            HardenStrings.decode(1)
        }
    }

    @Test
    fun `install is idempotent for one table and rejects another table`() {
        val table = table(encrypted(0, "value"))

        HardenStrings.install(CONFIG, table)
        HardenStrings.install(CONFIG, table)

        assertEquals("value", HardenStrings.decode(0))
        assertThrows(IllegalStateException::class.java) {
            HardenStrings.install(CONFIG, table(encrypted(0, "other")))
        }
    }

    @Test
    fun `decode validates entry id and clear removes installed key state`() {
        HardenStrings.install(CONFIG, table(encrypted(0, "value")))

        assertThrows(IllegalArgumentException::class.java) { HardenStrings.decode(-1) }
        assertThrows(IllegalArgumentException::class.java) { HardenStrings.decode(1) }

        HardenStrings.clear()
        assertThrows(IllegalStateException::class.java) { HardenStrings.decode(0) }
    }

    @Test
    fun `table defensively copies fragments and encrypted entries`() {
        val fragmentA = FRAGMENT_A.copyOf()
        val fragmentB = FRAGMENT_B.copyOf()
        val encrypted = encrypted(0, "stable")
        val ivs = arrayOf(encrypted.iv)
        val ciphertexts = arrayOf(encrypted.ciphertext)
        val table = HardenStringTable(fragmentA, fragmentB, ivs, ciphertexts)

        fragmentA.fill(0)
        fragmentB.fill(0)
        ivs[0].fill(0)
        ciphertexts[0].fill(0)
        HardenStrings.install(CONFIG, table)

        assertEquals("stable", HardenStrings.decode(0))
    }

    @Test
    fun `generated table loader distinguishes an absent table from an invalid table`() {
        val classLoader = requireNotNull(javaClass.classLoader)

        assertNull(
            GeneratedStringTableLoader.loadOrNull(
                "com.apkharden.runtime.fixture.MissingStringTable",
                classLoader,
            ),
        )
        assertSame(
            com.apkharden.runtime.fixture.TestGeneratedStringTable.INSTANCE,
            GeneratedStringTableLoader.loadOrNull(
                "com.apkharden.runtime.fixture.TestGeneratedStringTable",
                classLoader,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            GeneratedStringTableLoader.loadOrNull(
                "com.apkharden.runtime.fixture.TestGeneratedConfig",
                classLoader,
            )
        }
    }

    private fun table(vararg entries: Entry): HardenStringTable = HardenStringTable(
        FRAGMENT_A,
        FRAGMENT_B,
        entries.map(Entry::iv).toTypedArray(),
        entries.map(Entry::ciphertext).toTypedArray(),
    )

    private fun encrypted(id: Int, plaintext: String): Entry {
        val iv = ByteArray(12) { index -> (id * 17 + index).toByte() }
        return Entry(
            iv,
            StringCrypto.encrypt(
                plaintext,
                KEY,
                iv,
                StringCrypto.entryAad(id),
            ),
        )
    }

    private data class Entry(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private companion object {
        val FRAGMENT_A = ByteArray(16) { 1 }
        val FRAGMENT_B = ByteArray(16) { 2 }
        val CONFIG = HardenConfig(
            applicationId = "com.example.app",
            variantName = "release",
            buildId = "build-1",
            certificateSha256 = "ab".repeat(32),
        )
        val KEY = StringCrypto.deriveKey(
            FRAGMENT_A,
            FRAGMENT_B,
            CONFIG.certificateSha256,
            CONFIG.applicationId,
            CONFIG.buildId,
        )
    }
}
