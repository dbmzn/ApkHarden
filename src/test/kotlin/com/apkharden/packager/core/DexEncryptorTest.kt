package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DexEncryptorTest {
    // Mirror of the shell-side decrypt, to prove round-trip compatibility.
    private fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, 16)
        val body = blob.copyOfRange(16, blob.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Constants.AES_KEY, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(body)
    }

    @Test
    fun `encrypt then decrypt returns original`() {
        val original = ByteArray(5000) { (it % 256).toByte() }
        val blob = DexEncryptor.encrypt(original)
        assertFalse(blob.copyOfRange(16, blob.size).contentEquals(original)) // body differs from plaintext
        assertArrayEquals(original, decrypt(blob))
    }

    @Test
    fun `two encryptions of same input differ (random IV)`() {
        val original = "hello dex".toByteArray()
        val a = DexEncryptor.encrypt(original)
        val b = DexEncryptor.encrypt(original)
        assertFalse(a.contentEquals(b))
    }
}
