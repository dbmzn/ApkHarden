package com.apkharden.release.apk

import java.io.File
import java.io.FilterOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ZipAlignmentInspectorTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `stored native entry aligned to 4KB but not 16KB fails`() {
        val apk = nativeZip(File(temp, "4k.apk"), 4096)
        val entry = ZipAlignmentInspector.inspect(apk).single()

        assertTrue(entry.dataOffset % 4096L == 0L)
        assertFalse(entry.aligned16k)
    }

    @Test
    fun `stored native entry aligned to 16KB passes`() {
        val apk = nativeZip(File(temp, "16k.apk"), 16384)
        assertTrue(ZipAlignmentInspector.inspect(apk).single().aligned16k)
    }


    @Test
    fun `compressed native entry does not require direct-map alignment`() {
        val apk = File(temp, "compressed.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libcompressed.so"))
            zip.write(ByteArray(256) { it.toByte() })
            zip.closeEntry()
        }
        val entry = ZipAlignmentInspector.inspect(apk).single()
        assertTrue(entry.compressionMethod != ZipEntry.STORED)
        assertTrue(entry.aligned16k)
    }    private fun nativeZip(file: File, alignment: Int): File {
        val body = ByteArray(256) { it.toByte() }
        val counting = object : FilterOutputStream(file.outputStream()) {
            var count = 0L
            override fun write(value: Int) {
                out.write(value)
                count++
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                out.write(bytes, offset, length)
                count += length
            }
        }
        ZipOutputStream(counting).use { zip ->
            val name = "lib/arm64-v8a/libfixture.so"
            val minimumExtra = 6
            val start = counting.count + 30 + name.toByteArray().size + minimumExtra
            val padding = ((alignment - (start % alignment)) % alignment).toInt()
            val extra = ByteBuffer.allocate(minimumExtra + padding)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0xd935.toShort())
                .putShort((2 + padding).toShort())
                .putShort(alignment.toShort())
                .array()
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = body.size.toLong()
                compressedSize = body.size.toLong()
                crc = CRC32().apply { update(body) }.value
                this.extra = extra
            }
            zip.putNextEntry(entry)
            zip.write(body)
            zip.closeEntry()
        }
        return file
    }
}
