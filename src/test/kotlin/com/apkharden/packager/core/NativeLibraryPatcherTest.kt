package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeLibraryPatcherTest {
    private val slot = ByteArray(DexPayloadCodec.KEY_SIZE) { index -> (0xA1 + index).toByte() }

    @Test
    fun `replaces exactly one native key slot`() {
        val key = DexPayloadCodec.newKey()
        val library = byteArrayOf(1, 2, 3) + slot + byteArrayOf(4, 5, 6)

        val patched = NativeLibraryPatcher.injectPayloadKey(library, key)

        assertArrayEquals(key, patched.copyOfRange(3, 3 + key.size))
        assertFalse(NativeLibraryPatcher.containsUnpatchedKeySlot(patched))
    }

    @Test
    fun `rejects missing or duplicate key slots`() {
        val key = DexPayloadCodec.newKey()
        assertThrows(IllegalArgumentException::class.java) {
            NativeLibraryPatcher.injectPayloadKey(byteArrayOf(1, 2, 3), key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeLibraryPatcher.injectPayloadKey(slot + slot, key)
        }
    }

    @Test
    fun `all packaged shell libraries expose exactly one patchable key slot`() {
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abi ->
            val library = requireNotNull(
                javaClass.getResourceAsStream("/shell-libs/$abi/libapkharden.so"),
            ) { "missing shell library for $abi" }.use { it.readBytes() }

            val patched = NativeLibraryPatcher.injectPayloadKey(library, DexPayloadCodec.newKey())

            assertFalse(NativeLibraryPatcher.containsUnpatchedKeySlot(patched), abi)
        }
    }
}
