package com.apkharden.packager.core

/** Injects an APK-unique payload key into the prebuilt shell libraries. */
object NativeLibraryPatcher {
    // Must exactly match g_key_slot in native/apkharden.c. It is replaced before packaging.
    private val keySlot = ByteArray(DexPayloadCodec.KEY_SIZE) { index -> (0xA1 + index).toByte() }

    fun injectPayloadKey(library: ByteArray, key: ByteArray): ByteArray {
        require(key.size == DexPayloadCodec.KEY_SIZE) {
            "Native payload key must be ${DexPayloadCodec.KEY_SIZE} bytes"
        }
        val offsets = findAll(library, keySlot)
        require(offsets.size == 1) {
            "Native shell key slot must occur exactly once, found ${offsets.size}"
        }
        return library.clone().also { output ->
            key.copyInto(output, destinationOffset = offsets.single())
        }
    }

    internal fun containsUnpatchedKeySlot(library: ByteArray): Boolean = findAll(library, keySlot).isNotEmpty()

    private fun findAll(haystack: ByteArray, needle: ByteArray): List<Int> {
        if (haystack.size < needle.size) return emptyList()
        val matches = ArrayList<Int>()
        for (start in 0..haystack.size - needle.size) {
            var matched = true
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) {
                    matched = false
                    break
                }
            }
            if (matched) matches += start
        }
        return matches
    }
}
