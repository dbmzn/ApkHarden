package com.apkharden.packager.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DexEncryptor {
    private val rng = SecureRandom()

    /** Returns [16-byte IV] + AES/CBC/PKCS5 ciphertext. */
    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(16).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Constants.AES_KEY, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(plain)
    }
}
