package com.apkharden.gradle.strings

import com.apkharden.crypto.StringCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StringTableCompilerTest {
    @Test
    fun `compiler assigns stable ids deduplicates and encrypts every string`() {
        val table = compiler().compile(
            strings = listOf("alpha", "订单完成", "alpha"),
            certificateSha256 = CERTIFICATE,
            applicationId = "com.example.app",
            buildId = "build-1",
        )

        assertEquals(mapOf("alpha" to 0, "订单完成" to 1), table.ids)
        assertEquals(2, table.entries.size)
        val key = StringCrypto.deriveKey(
            table.fragmentA,
            table.fragmentB,
            CERTIFICATE,
            "com.example.app",
            "build-1",
        )
        assertEquals(
            "alpha",
            StringCrypto.decrypt(
                table.entries[0].ciphertext,
                key,
                table.entries[0].iv,
                StringCrypto.entryAad(0),
            ),
        )
        assertEquals(
            "订单完成",
            StringCrypto.decrypt(
                table.entries[1].ciphertext,
                key,
                table.entries[1].iv,
                StringCrypto.entryAad(1),
            ),
        )
    }

    @Test
    fun `generated java table contains ciphertext but no protected plaintext`() {
        val table = compiler().compile(
            strings = listOf("top-secret-value"),
            certificateSha256 = CERTIFICATE,
            applicationId = "com.example.app",
            buildId = "build-1",
        )

        val source = table.javaSource()

        assertFalse(source.contains("top-secret-value"))
        assertFalse(source.contains(CERTIFICATE))
        assertFalse(source.contains("build-1"))
        assertEquals(1, "public static final HardenStringTable INSTANCE".toRegex().findAll(source).count())
        assertEquals(2, "new byte[][]".toRegex(RegexOption.LITERAL).findAll(source).count())
    }

    @Test
    fun `empty string set produces a valid empty generated table`() {
        val table = compiler().compile(
            strings = emptyList(),
            certificateSha256 = CERTIFICATE,
            applicationId = "com.example.app",
            buildId = "build-1",
        )

        assertEquals(emptyMap<String, Int>(), table.ids)
        assertEquals(0, table.entries.size)
        assertFalse(table.javaSource().contains("null"))
    }

    @Test
    fun `compiler rejects entropy sources returning the wrong size`() {
        val compiler = StringTableCompiler(EntropySource { size -> ByteArray(size - 1) })

        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(
                strings = listOf("value"),
                certificateSha256 = CERTIFICATE,
                applicationId = "com.example.app",
                buildId = "build-1",
            )
        }
    }

    private fun compiler(): StringTableCompiler {
        var next = 0
        return StringTableCompiler(
            EntropySource { size -> ByteArray(size) { (next++).toByte() } },
        )
    }

    private companion object {
        const val CERTIFICATE =
            "abababababababababababababababababababababababababababababababab"
    }
}
