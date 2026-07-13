package com.apkharden.release.apk

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlinx.serialization.Serializable

@Serializable
data class NativeZipEntry(
    val name: String,
    val compressionMethod: Int,
    val dataOffset: Long,
    val aligned16k: Boolean,
)

object ZipAlignmentInspector {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val STORED = 0
    private const val UTF8_FLAG = 1 shl 11
    private val cp437 = Charset.forName("CP437")

    fun inspect(apk: File): List<NativeZipEntry> {
        require(apk.isFile) { "APK not found: $apk" }
        return RandomAccessFile(apk, "r").use { file ->
            val eocdOffset = findEocd(file)
            val diskNumber = readU16(file, eocdOffset + 4)
            val centralDisk = readU16(file, eocdOffset + 6)
            val entriesOnDisk = readU16(file, eocdOffset + 8)
            val entryCount = readU16(file, eocdOffset + 10)
            val centralOffset = readU32(file, eocdOffset + 16)
            require(diskNumber == 0 && centralDisk == 0 && entriesOnDisk == entryCount) {
                "Multi-disk APK ZIP is unsupported"
            }
            require(entryCount != 0xffff && centralOffset != 0xffffffffL) {
                "ZIP64 APK is unsupported"
            }

            buildList {
                var cursor = centralOffset
                repeat(entryCount) {
                    require(readU32(file, cursor) == CENTRAL_SIGNATURE) {
                        "Invalid ZIP central directory at offset $cursor"
                    }
                    val flags = readU16(file, cursor + 8)
                    val method = readU16(file, cursor + 10)
                    val nameLength = readU16(file, cursor + 28)
                    val extraLength = readU16(file, cursor + 30)
                    val commentLength = readU16(file, cursor + 32)
                    val localOffset = readU32(file, cursor + 42)
                    val charset = if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else cp437
                    val name = readText(file, cursor + 46, nameLength, charset)

                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        require(readU32(file, localOffset) == LOCAL_SIGNATURE) {
                            "Invalid ZIP local header for $name"
                        }
                        val localNameLength = readU16(file, localOffset + 26)
                        val localExtraLength = readU16(file, localOffset + 28)
                        val localName = readText(
                            file,
                            localOffset + 30,
                            localNameLength,
                            charset,
                        )
                        require(localName == name) {
                            "ZIP central/local entry name mismatch for $name"
                        }
                        val dataOffset = localOffset + 30 + localNameLength + localExtraLength
                        add(
                            NativeZipEntry(
                                name = name,
                                compressionMethod = method,
                                dataOffset = dataOffset,
                                aligned16k = method != STORED || dataOffset % 16384L == 0L,
                            )
                        )
                    }
                    cursor += 46L + nameLength + extraLength + commentLength
                }
            }
        }
    }

    private fun findEocd(file: RandomAccessFile): Long {
        val lastPossible = (file.length() - 22).coerceAtLeast(0)
        val firstPossible = (file.length() - 65557).coerceAtLeast(0)
        for (offset in lastPossible downTo firstPossible) {
            if (readU32(file, offset) != EOCD_SIGNATURE) continue
            val commentLength = readU16(file, offset + 20)
            if (offset + 22 + commentLength == file.length()) {
                return offset
            }
        }
        throw IllegalArgumentException("ZIP end-of-central-directory not found")
    }

    private fun readU16(file: RandomAccessFile, offset: Long): Int {
        file.seek(offset)
        return file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
    }

    private fun readU32(file: RandomAccessFile, offset: Long): Long {
        file.seek(offset)
        return (
            file.readUnsignedByte().toLong() or
                (file.readUnsignedByte().toLong() shl 8) or
                (file.readUnsignedByte().toLong() shl 16) or
                (file.readUnsignedByte().toLong() shl 24)
            ) and 0xffffffffL
    }

    private fun readText(
        file: RandomAccessFile,
        offset: Long,
        length: Int,
        charset: Charset,
    ): String {
        val bytes = ByteArray(length)
        file.seek(offset)
        file.readFully(bytes)
        return bytes.toString(charset)
    }
}
