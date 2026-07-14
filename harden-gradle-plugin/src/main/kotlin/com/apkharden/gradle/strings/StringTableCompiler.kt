package com.apkharden.gradle.strings

import com.apkharden.crypto.StringCrypto
import java.security.SecureRandom

fun interface EntropySource {
    fun nextBytes(size: Int): ByteArray
}

class SecureEntropySource(
    private val random: SecureRandom = SecureRandom(),
) : EntropySource {
    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

class StringTableCompiler(
    private val entropy: EntropySource = SecureEntropySource(),
) {
    fun compile(
        strings: List<String>,
        certificateSha256: String,
        applicationId: String,
        buildId: String,
    ): CompiledStringTable {
        val ids = linkedMapOf<String, Int>()
        strings.forEach { value -> ids.getOrPut(value) { ids.size } }

        val fragmentA = entropy.bytes(KEY_FRAGMENT_SIZE, "fragmentA")
        val fragmentB = entropy.bytes(KEY_FRAGMENT_SIZE, "fragmentB")
        val key = StringCrypto.deriveKey(
            fragmentA,
            fragmentB,
            certificateSha256,
            applicationId,
            buildId,
        )
        return try {
            val entries = ids.entries.map { (plaintext, entryId) ->
                val iv = entropy.bytes(GCM_IV_SIZE, "iv[$entryId]")
                EncryptedStringEntry(
                    iv = iv,
                    ciphertext = StringCrypto.encrypt(
                        plaintext,
                        key,
                        iv,
                        StringCrypto.entryAad(entryId),
                    ),
                )
            }
            CompiledStringTable(
                ids = ids.toMap(),
                fragmentA = fragmentA,
                fragmentB = fragmentB,
                entries = entries,
            )
        } finally {
            key.fill(0)
        }
    }

    private fun EntropySource.bytes(size: Int, name: String): ByteArray =
        nextBytes(size).also { value ->
            require(value.size == size) { "$name entropy must contain $size bytes" }
        }

    private companion object {
        const val KEY_FRAGMENT_SIZE = 16
        const val GCM_IV_SIZE = 12
    }
}

data class EncryptedStringEntry(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

class CompiledStringTable(
    val ids: Map<String, Int>,
    fragmentA: ByteArray,
    fragmentB: ByteArray,
    entries: List<EncryptedStringEntry>,
) {
    val fragmentA = fragmentA.copyOf()
    val fragmentB = fragmentB.copyOf()
    val entries = entries.map { entry ->
        EncryptedStringEntry(entry.iv.copyOf(), entry.ciphertext.copyOf())
    }

    fun javaSource(): String {
        val ivValues = entries.joinToString(",\n                    ") { entry ->
            "new byte[] {${entry.iv.javaBytes()}}"
        }
        val ciphertextValues = entries.joinToString(",\n                    ") { entry ->
            "new byte[] {${entry.ciphertext.javaBytes()}}"
        }
        return """
            package com.apkharden.generated;

            import com.apkharden.runtime.HardenStringTable;

            public final class HardenStringTableConfig {
                public static final HardenStringTable INSTANCE = new HardenStringTable(
                    new byte[] {${fragmentA.javaBytes()}},
                    new byte[] {${fragmentB.javaBytes()}},
                    new byte[][] {
                        $ivValues
                    },
                    new byte[][] {
                        $ciphertextValues
                    }
                );

                private HardenStringTableConfig() {}
            }
        """.trimIndent() + "\n"
    }

    private fun ByteArray.javaBytes(): String = joinToString(", ") { byte ->
        "(byte) 0x%02x".format(byte.toInt() and 0xff)
    }
}
