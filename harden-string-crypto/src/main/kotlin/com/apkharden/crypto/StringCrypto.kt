package com.apkharden.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object StringCrypto {
    @JvmStatic
    fun deriveKey(
        fragmentA: ByteArray,
        fragmentB: ByteArray,
        certificateSha256: String,
        applicationId: String,
        buildId: String,
    ): ByteArray {
        require(fragmentA.size == KEY_FRAGMENT_SIZE) {
            "fragmentA must contain $KEY_FRAGMENT_SIZE bytes"
        }
        require(fragmentB.size == KEY_FRAGMENT_SIZE) {
            "fragmentB must contain $KEY_FRAGMENT_SIZE bytes"
        }
        require(applicationId.isNotBlank()) { "applicationId is blank" }
        require(buildId.isNotBlank()) { "buildId is blank" }
        val certificate = certificateSha256.lowercase()
        require(certificate.matches(CERTIFICATE_PATTERN)) {
            "certificateSha256 must contain 64 hexadecimal characters"
        }

        return MessageDigest.getInstance("SHA-256").apply {
            update(KEY_DERIVATION_DOMAIN)
            updatePart(fragmentA)
            updatePart(fragmentB)
            updatePart(certificate.hexToBytes())
            updatePart(applicationId.toByteArray(StandardCharsets.UTF_8))
            updatePart(buildId.toByteArray(StandardCharsets.UTF_8))
        }.digest()
    }

    @JvmStatic
    fun entryAad(entryId: Int): ByteArray {
        require(entryId >= 0) { "entryId is negative" }
        return ByteBuffer.allocate(ENTRY_AAD_DOMAIN.size + Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ENTRY_AAD_DOMAIN)
            .putInt(entryId)
            .array()
    }

    @JvmStatic
    fun encrypt(
        plaintext: String,
        key: ByteArray,
        iv: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, iv, associatedData)
        .doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

    @JvmStatic
    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        associatedData: ByteArray,
    ): String {
        require(ciphertext.size >= GCM_TAG_BYTES) {
            "ciphertext must include a $GCM_TAG_BITS-bit authentication tag"
        }
        return cipher(Cipher.DECRYPT_MODE, key, iv, associatedData)
            .doFinal(ciphertext)
            .toString(StandardCharsets.UTF_8)
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        iv: ByteArray,
        associatedData: ByteArray,
    ): Cipher {
        require(key.size == AES_256_KEY_BYTES) {
            "key must contain $AES_256_KEY_BYTES bytes"
        }
        require(iv.size == GCM_IV_BYTES) { "iv must contain $GCM_IV_BYTES bytes" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                mode,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            updateAAD(associatedData)
        }
    }

    private fun MessageDigest.updatePart(value: ByteArray) {
        update(
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(value.size)
                .array(),
        )
        update(value)
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private val CERTIFICATE_PATTERN = Regex("[0-9a-f]{64}")
    private val KEY_DERIVATION_DOMAIN =
        "ApkHarden.StringKey.v1".toByteArray(StandardCharsets.US_ASCII)
    private val ENTRY_AAD_DOMAIN =
        "ApkHarden.StringEntry.v1".toByteArray(StandardCharsets.US_ASCII)

    private const val KEY_FRAGMENT_SIZE = 16
    private const val AES_256_KEY_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
}
