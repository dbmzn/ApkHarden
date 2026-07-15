package com.apkharden.packager.core

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRepackager {

    private val dexRegex = Regex("classes\\d*\\.dex")

    // Uncompressed entries are mmap'd at install/runtime and must be aligned. Native libs
    // (extractNativeLibs=false) need page alignment (4096); resources.arsc and other STORED
    // entries need 4. We write a zipalign-compatible 0xd935 "alignment" extra field, which
    // both the Android loader (it just needs the data at the boundary) and apksig (it reads
    // this record and preserves our alignment) honour. Without this, ZipOutputStream places
    // STORED entries at arbitrary offsets → INSTALL_FAILED_INVALID_APK "Failed to extract
    // native libraries".
    private const val PAGE_ALIGNMENT = 16384
    private const val DEFAULT_STORED_ALIGNMENT = 4
    private const val ALIGN_EXTRA_HEADER_ID = 0xd935.toShort()
    private const val ALIGN_EXTRA_MIN_SIZE = 6 // 2 (id) + 2 (size) + 2 (alignment value)

    /** Appends a statically loaded guard dex while preserving every business dex in place. */
    fun injectGuard(
        input: File,
        output: File,
        patchedManifest: ByteArray,
        guardDex: ByteArray,
    ): String {
        var injectedDexName = ""
        ZipFile(input).use { zin ->
            val dexNames = zin.entries().asSequence()
                .map { it.name }
                .filter { it.matches(dexRegex) }
                .toList()
            require(dexNames.isNotEmpty()) { "APK contains no classes.dex" }
            val nextDexIndex = dexNames.maxOf(::dexIndex) + 1
            val guardDexName = if (nextDexIndex == 1) "classes.dex" else "classes$nextDexIndex.dex"
            injectedDexName = guardDexName

            output.outputStream().buffered().use { raw ->
                val counting = CountingOutputStream(raw)
                ZipOutputStream(counting).use { zout ->
                    for (entry in zin.entries()) {
                        val name = entry.name
                        if (name == "AndroidManifest.xml") continue
                        if (isSignatureEntry(name)) continue
                        copyEntry(zin, entry, zout, counting.count)
                    }
                    write(zout, "AndroidManifest.xml", patchedManifest)
                    write(zout, guardDexName, guardDex)
                }
            }
        }
        return injectedDexName
    }

    private fun dexIndex(name: String): Int =
        if (name == "classes.dex") 1
        else name.removePrefix("classes").removeSuffix(".dex").toInt()

    private fun isSignatureEntry(name: String): Boolean =
        name.startsWith("META-INF/") &&
            (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") ||
                name.endsWith(".SF") || name == "META-INF/MANIFEST.MF")

    private fun copyEntry(
        input: ZipFile,
        source: ZipEntry,
        output: ZipOutputStream,
        offset: Long,
    ) {
        val copy = ZipEntry(source.name)
        if (source.method == ZipEntry.STORED) {
            copy.method = ZipEntry.STORED
            copy.size = source.size
            copy.compressedSize = source.size
            copy.crc = source.crc
            alignStored(copy, source.name, offset)
        } else {
            copy.method = ZipEntry.DEFLATED
        }
        output.putNextEntry(copy)
        input.getInputStream(source).use { it.copyTo(output) }
        output.closeEntry()
    }

    private fun write(zout: ZipOutputStream, name: String, bytes: ByteArray) {
        zout.putNextEntry(ZipEntry(name))
        zout.write(bytes)
        zout.closeEntry()
    }

    /**
     * Sets a zipalign-style extra field so the entry's data starts on its alignment boundary.
     * [lfhOffset] is the absolute offset where this entry's local file header will be written.
     */
    private fun alignStored(entry: ZipEntry, name: String, lfhOffset: Long) {
        val alignment = if (name.endsWith(".so")) PAGE_ALIGNMENT else DEFAULT_STORED_ALIGNMENT
        val nameLen = name.toByteArray(Charsets.UTF_8).size
        // Local header = 30 bytes + name + extra. With the minimal 6-byte alignment record the
        // data would start here; pad the record so that start lands on the boundary.
        val dataStartWithMinExtra = lfhOffset + 30 + nameLen + ALIGN_EXTRA_MIN_SIZE
        val pad = ((alignment - (dataStartWithMinExtra % alignment)) % alignment).toInt()

        val extra = ByteBuffer.allocate(ALIGN_EXTRA_MIN_SIZE + pad).order(ByteOrder.LITTLE_ENDIAN)
        extra.putShort(ALIGN_EXTRA_HEADER_ID)        // 0xd935
        extra.putShort((2 + pad).toShort())          // data size: alignment value (2) + padding
        extra.putShort(alignment.toShort())          // alignment value
        // remaining `pad` bytes stay zero
        entry.extra = extra.array()
    }

    /** Tracks the absolute byte offset written so far, so STORED entries can be aligned. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
