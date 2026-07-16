package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException

class DexPayloadCodecTest {
    private val dex = requireNotNull(javaClass.getResourceAsStream("/shell.dex")).use { it.readBytes() }

    @Test
    fun `round trips compressed authenticated dex`() {
        val key = DexPayloadCodec.newKey()

        val payload = DexPayloadCodec.encrypt(dex, key)

        assertArrayEquals(dex, DexPayloadCodec.decrypt(payload, key))
        assertTrue(payload.copyOfRange(0, 4).contentEquals("APH1".encodeToByteArray()))
        assertFalse(payload.copyOfRange(0, 4).contentEquals("dex\n".encodeToByteArray()))
        assertTrue(payload.size < dex.size, "shell fixture should demonstrate pre-encryption compression")
    }

    @Test
    fun `rejects tampered ciphertext`() {
        val key = DexPayloadCodec.newKey()
        val payload = DexPayloadCodec.encrypt(dex, key).also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }

        assertThrows(AEADBadTagException::class.java) {
            DexPayloadCodec.decrypt(payload, key)
        }
    }

    @Test
    fun `rejects wrong key`() {
        val payload = DexPayloadCodec.encrypt(dex, DexPayloadCodec.newKey())

        assertThrows(AEADBadTagException::class.java) {
            DexPayloadCodec.decrypt(payload, DexPayloadCodec.newKey())
        }
    }
}
