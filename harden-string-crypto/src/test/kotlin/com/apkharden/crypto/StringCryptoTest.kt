package com.apkharden.crypto

import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StringCryptoTest {
    @Test
    fun `key derivation is deterministic and bound to every input`() {
        val baseline = key()

        assertArrayEquals(baseline, key())
        assertFalse(baseline.contentEquals(key(fragmentA = ByteArray(16) { 3 })))
        assertFalse(baseline.contentEquals(key(fragmentB = ByteArray(16) { 4 })))
        assertFalse(baseline.contentEquals(key(certificate = "cd".repeat(32))))
        assertFalse(baseline.contentEquals(key(applicationId = "com.example.other")))
        assertFalse(baseline.contentEquals(key(buildId = "build-2")))
    }

    @Test
    fun `aes gcm round trips unicode with entry aad`() {
        val key = key()
        val iv = ByteArray(12) { it.toByte() }
        val aad = StringCrypto.entryAad(37)
        val plaintext = "订单已完成 / completed"

        val ciphertext = StringCrypto.encrypt(plaintext, key, iv, aad)

        assertEquals(plaintext, StringCrypto.decrypt(ciphertext, key, iv, aad))
        assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(plaintext))
    }

    @Test
    fun `aes gcm rejects changed ciphertext iv and aad`() {
        val key = key()
        val iv = ByteArray(12) { (it + 1).toByte() }
        val aad = StringCrypto.entryAad(5)
        val ciphertext = StringCrypto.encrypt("secret", key, iv, aad)

        assertThrows(AEADBadTagException::class.java) {
            StringCrypto.decrypt(ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, key, iv, aad)
        }
        assertThrows(AEADBadTagException::class.java) {
            StringCrypto.decrypt(ciphertext, key, iv.copyOf().also { it[0] = 99 }, aad)
        }
        assertThrows(AEADBadTagException::class.java) {
            StringCrypto.decrypt(ciphertext, key, iv, StringCrypto.entryAad(6))
        }
    }

    @Test
    fun `invalid key material is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StringCrypto.deriveKey(ByteArray(0), ByteArray(16), "ab".repeat(32), "app", "build")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StringCrypto.deriveKey(ByteArray(16), ByteArray(16), "not-a-certificate", "app", "build")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StringCrypto.encrypt("value", ByteArray(31), ByteArray(12), ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StringCrypto.encrypt("value", ByteArray(32), ByteArray(11), ByteArray(0))
        }
    }

    private fun key(
        fragmentA: ByteArray = ByteArray(16) { 1 },
        fragmentB: ByteArray = ByteArray(16) { 2 },
        certificate: String = "ab".repeat(32),
        applicationId: String = "com.example.app",
        buildId: String = "build-1",
    ): ByteArray = StringCrypto.deriveKey(
        fragmentA,
        fragmentB,
        certificate,
        applicationId,
        buildId,
    )
}
