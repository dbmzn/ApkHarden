package com.apkharden.packager.core

import java.security.SecureRandom
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DexEncryptor {
    private val rng = SecureRandom()

    /**
     * Returns [16-byte IV] + AES/CBC/PKCS5( deflate(plain) ).
     *
     * The dex is deflated *before* encryption: AES ciphertext is high-entropy and cannot be
     * compressed by the APK's zip, so encrypting the raw dex would store it at its full
     * uncompressed size (inflating the output APK). Compressing first keeps the encrypted
     * asset close to the original APK's compressed dex size. The shell inflates after decrypt.
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val compressed = deflate(plain)
        val iv = ByteArray(16).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Constants.AES_KEY, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(compressed)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(data); finish() }
        val out = java.io.ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }
}
