package com.apkharden.packager.core

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
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
    private const val PAGE_ALIGNMENT = 4096
    private const val DEFAULT_STORED_ALIGNMENT = 4
    private const val ALIGN_EXTRA_HEADER_ID = 0xd935.toShort()
    private const val ALIGN_EXTRA_MIN_SIZE = 6 // 2 (id) + 2 (size) + 2 (alignment value)

    fun repackage(
        input: File,
        output: File,
        patchedManifest: ByteArray,
        shellDex: ByteArray,
        encryptedDexes: List<ByteArray>,
    ) {
        ZipFile(input).use { zin ->
            output.outputStream().buffered().use { raw ->
                val counting = CountingOutputStream(raw)
                ZipOutputStream(counting).use { zout ->
                    // 1. Copy originals except dex, manifest, and old signatures.
                    for (e in zin.entries()) {
                        val name = e.name
                        if (name.matches(dexRegex)) continue
                        if (name == "AndroidManifest.xml") continue
                        if (name.startsWith("META-INF/") &&
                            (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                             name.endsWith(".EC") || name.endsWith(".SF") ||
                             name == "META-INF/MANIFEST.MF")
                        ) continue

                        // Preserve the original compression method. STORED entries (resources.arsc,
                        // native libs on extractNativeLibs=false) must stay uncompressed and aligned.
                        val copy = ZipEntry(name)
                        if (e.method == ZipEntry.STORED) {
                            copy.method = ZipEntry.STORED
                            copy.size = e.size
                            copy.compressedSize = e.size
                            copy.crc = e.crc
                            alignStored(copy, name, counting.count)
                        } else {
                            copy.method = ZipEntry.DEFLATED
                        }
                        zout.putNextEntry(copy)
                        zin.getInputStream(e).use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                    // 2. Patched manifest (compressed; not mmap'd, no alignment needed).
                    write(zout, "AndroidManifest.xml", patchedManifest)
                    // 3. Shell becomes classes.dex (compressed).
                    write(zout, "classes.dex", shellDex)
                    // 4. Encrypted original dexes as assets. These are already deflate+AES'd
                    //    (high-entropy, incompressible), so store them uncompressed to avoid
                    //    wasting CPU and the small deflate overhead.
                    encryptedDexes.forEachIndexed { i, bytes ->
                        writeStored(zout, Constants.encryptedDexEntry(i), bytes, counting.count)
                    }
                }
            }
        }
    }

    private fun write(zout: ZipOutputStream, name: String, bytes: ByteArray) {
        zout.putNextEntry(ZipEntry(name))
        zout.write(bytes)
        zout.closeEntry()
    }

    private fun writeStored(zout: ZipOutputStream, name: String, bytes: ByteArray, lfhOffset: Long) {
        val e = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        alignStored(e, name, lfhOffset)
        zout.putNextEntry(e)
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
