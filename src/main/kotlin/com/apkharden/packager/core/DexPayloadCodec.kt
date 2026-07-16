package com.apkharden.packager.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned authenticated payload used for encrypted business DEX files. */
object DexPayloadCodec {
    const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val HEADER_SIZE = 24
    private val magic = byteArrayOf('A'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte())
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_SIZE).also(random::nextBytes)

    /** Layout: APH1 | version/flags | plain length | nonce | AES-GCM(deflate(dex)). */
    fun encrypt(plainDex: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "DEX payload key must be $KEY_SIZE bytes" }
        require(isDex(plainDex)) { "Payload is not a DEX file" }
        val compressed = deflate(plainDex)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(1)
            .put(0)
            .putShort(0)
            .putInt(plainDex.size)
            .put(nonce)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(compressed)
    }

    /** JVM-side decoder used by tests and structural verification. Runtime decoding is native. */
    fun decrypt(payload: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "DEX payload key must be $KEY_SIZE bytes" }
        val header = parseHeader(payload)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, header.nonce),
        )
        cipher.updateAAD(payload, 0, HEADER_SIZE)
        val compressed = cipher.doFinal(payload, HEADER_SIZE, payload.size - HEADER_SIZE)
        return inflate(compressed, header.plainLength).also {
            require(isDex(it)) { "Decrypted payload is not a DEX file" }
        }
    }

    fun plainLength(payload: ByteArray): Int = parseHeader(payload).plainLength

    private fun parseHeader(payload: ByteArray): Header {
        require(payload.size >= HEADER_SIZE + 16) { "Encrypted DEX payload is truncated" }
        require(payload.copyOfRange(0, 4).contentEquals(magic)) { "Encrypted DEX magic mismatch" }
        require(payload[4].toInt() == 1) { "Unsupported encrypted DEX version: ${payload[4]}" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.position(8)
        val plainLength = buffer.int
        require(plainLength in 40..MAX_DEX_SIZE) { "Invalid DEX payload size: $plainLength" }
        val nonce = ByteArray(NONCE_SIZE).also(buffer::get)
        return Header(plainLength, nonce)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION).apply {
            setInput(data)
            finish()
        }
        return ByteArrayOutputStream(data.size / 2).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            output.toByteArray()
        }
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater().apply { setInput(data) }
        return ByteArrayOutputStream(expectedSize).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                require(count > 0 || !inflater.needsInput()) { "Compressed DEX payload is truncated" }
                if (count == 0 && inflater.needsDictionary()) error("Compressed DEX requires a dictionary")
                output.write(buffer, 0, count)
                require(output.size() <= expectedSize) { "Decompressed DEX exceeds declared size" }
            }
            inflater.end()
            output.toByteArray().also {
                require(it.size == expectedSize) {
                    "Decompressed DEX size ${it.size} differs from declared size $expectedSize"
                }
            }
        }
    }

    private fun isDex(bytes: ByteArray): Boolean =
        bytes.size >= 40 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    private data class Header(val plainLength: Int, val nonce: ByteArray)

    private const val MAX_DEX_SIZE = 512 * 1024 * 1024
}
