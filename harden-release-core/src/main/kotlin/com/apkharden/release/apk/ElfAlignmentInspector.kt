package com.apkharden.release.apk

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable

@Serializable
data class ElfLoadSegment(
    val fileOffset: Long,
    val virtualAddress: Long,
    val alignment: Long,
)

@Serializable
data class ElfAlignmentResult(
    val elfClass: Int,
    val loadSegments: List<ElfLoadSegment>,
    val compatible16k: Boolean,
) {
    val loadAlignments: List<Long>
        get() = loadSegments.map { it.alignment }
}

object ElfAlignmentInspector {
    private const val PT_LOAD = 1
    private const val ELF32 = 1
    private const val ELF64 = 2
    private const val LITTLE_ENDIAN = 1
    private const val PAGE_SIZE_16K = 16384L

    fun inspect(bytes: ByteArray): ElfAlignmentResult {
        require(bytes.size >= 52) { "ELF file is too small" }
        require(
            bytes[0] == 0x7f.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
        ) { "Native library has invalid ELF magic" }

        val elfClass = bytes[4].toInt() and 0xff
        require(bytes[5].toInt() and 0xff == LITTLE_ENDIAN) {
            "Only little-endian ELF is supported"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val layout = when (elfClass) {
            ELF32 -> {
                require(bytes.size >= 52) { "ELF32 header is truncated" }
                HeaderLayout(
                    programOffset = readU32(buffer, 28),
                    entrySize = readU16(buffer, 42),
                    entryCount = readU16(buffer, 44),
                    minimumEntrySize = 32,
                    fileOffsetField = 4,
                    virtualAddressField = 8,
                    alignmentField = 28,
                    wideFields = false,
                )
            }
            ELF64 -> {
                require(bytes.size >= 64) { "ELF64 header is truncated" }
                HeaderLayout(
                    programOffset = buffer.getLong(32),
                    entrySize = readU16(buffer, 54),
                    entryCount = readU16(buffer, 56),
                    minimumEntrySize = 56,
                    fileOffsetField = 8,
                    virtualAddressField = 16,
                    alignmentField = 48,
                    wideFields = true,
                )
            }
            else -> throw IllegalArgumentException("Unsupported ELF class: $elfClass")
        }

        require(layout.programOffset >= 0) { "ELF program header offset is negative" }
        require(layout.entryCount > 0) { "ELF has no program headers" }
        require(layout.entrySize >= layout.minimumEntrySize) {
            "ELF program header entry is too small"
        }

        val segments = buildList {
            repeat(layout.entryCount) { index ->
                val baseLong = layout.programOffset + index.toLong() * layout.entrySize
                val endLong = baseLong + layout.entrySize
                require(baseLong <= Int.MAX_VALUE && endLong <= bytes.size.toLong()) {
                    "ELF program header is truncated"
                }
                val base = baseLong.toInt()
                if (buffer.getInt(base) == PT_LOAD) {
                    val fileOffset = layout.readField(buffer, base + layout.fileOffsetField)
                    val virtualAddress = layout.readField(
                        buffer,
                        base + layout.virtualAddressField,
                    )
                    val alignment = layout.readField(buffer, base + layout.alignmentField)
                    add(ElfLoadSegment(fileOffset, virtualAddress, alignment))
                }
            }
        }
        require(segments.isNotEmpty()) { "ELF has no PT_LOAD segment" }
        val compatible = segments.all {
            it.alignment >= PAGE_SIZE_16K &&
                Math.floorMod(
                    it.virtualAddress - it.fileOffset,
                    PAGE_SIZE_16K,
                ) == 0L
        }
        return ElfAlignmentResult(elfClass, segments, compatible)
    }

    fun inspectApk(apk: File): Map<String, ElfAlignmentResult> =
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .associate { entry ->
                    entry.name to inspect(
                        zip.getInputStream(entry).use { it.readBytes() }
                    )
                }
        }

    private fun readU16(buffer: ByteBuffer, offset: Int): Int =
        buffer.getShort(offset).toInt() and 0xffff

    private fun readU32(buffer: ByteBuffer, offset: Int): Long =
        buffer.getInt(offset).toLong() and 0xffffffffL

    private data class HeaderLayout(
        val programOffset: Long,
        val entrySize: Int,
        val entryCount: Int,
        val minimumEntrySize: Int,
        val fileOffsetField: Int,
        val virtualAddressField: Int,
        val alignmentField: Int,
        val wideFields: Boolean,
    ) {
        fun readField(buffer: ByteBuffer, offset: Int): Long =
            if (wideFields) {
                buffer.getLong(offset)
            } else {
                buffer.getInt(offset).toLong() and 0xffffffffL
            }
    }
}
