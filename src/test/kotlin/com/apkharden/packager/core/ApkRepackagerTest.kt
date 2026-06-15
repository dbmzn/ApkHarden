package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkRepackagerTest {
    @TempDir lateinit var tmp: File

    private fun fakeApk(): File {
        val f = File(tmp, "in.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("OLDMANIFEST".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write("dex0".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("res/a")); z.write("RES".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/CERT.RSA")); z.write("OLDSIG".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("MF".toByteArray()); z.closeEntry()
        }
        return f
    }

    private fun names(f: File) = ZipFile(f).use { it.entries().toList().map { e -> e.name } }
    private fun read(f: File, n: String) = ZipFile(f).use { z -> z.getInputStream(z.getEntry(n)).readBytes() }

    @Test
    fun `repackages with shell dex, patched manifest, encrypted assets, no old sig`() {
        val out = File(tmp, "out.apk")
        ApkRepackager.repackage(
            input = fakeApk(),
            output = out,
            patchedManifest = "NEWMANIFEST".toByteArray(),
            shellDex = "SHELL".toByteArray(),
            encryptedDexes = listOf("ENC0".toByteArray(), "ENC1".toByteArray()),
        )
        val n = names(out)
        assertTrue(n.contains("res/a"))
        assertEquals("RES", String(read(out, "res/a")))
        assertEquals("NEWMANIFEST", String(read(out, "AndroidManifest.xml")))
        assertEquals("SHELL", String(read(out, "classes.dex")))
        assertEquals("ENC0", String(read(out, Constants.encryptedDexEntry(0))))
        assertEquals("ENC1", String(read(out, Constants.encryptedDexEntry(1))))
        assertFalse(n.any { it.startsWith("META-INF/") }) // old signatures dropped
        assertFalse(n.contains("classes2.dex"))
    }

    @Test
    fun `stored native libs are page-aligned to 4096`() {
        // Uncompressed .so must start on a 4096 boundary or install fails with
        // INSTALL_FAILED_INVALID_APK "Failed to extract native libraries".
        val so = ByteArray(10_000) { (it % 7).toByte() }
        val input = File(tmp, "in.apk")
        ZipOutputStream(input.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("M".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write("dex0".toByteArray()); z.closeEntry()
            // a STORED native lib, like an extractNativeLibs=false build
            val e = ZipEntry("lib/arm64-v8a/libfoo.so").apply {
                method = ZipEntry.STORED; size = so.size.toLong(); compressedSize = so.size.toLong()
                crc = CRC32().apply { update(so) }.value
            }
            z.putNextEntry(e); z.write(so); z.closeEntry()
        }

        val out = File(tmp, "out.apk")
        ApkRepackager.repackage(
            input = input, output = out,
            patchedManifest = "NEWMANIFEST".toByteArray(),
            shellDex = "SHELL".toByteArray(),
            encryptedDexes = listOf("ENC0".toByteArray()),
        )

        assertEquals(0L, dataOffset(out, "lib/arm64-v8a/libfoo.so") % 4096,
            "native lib data must be 4096-aligned")
    }

    // Absolute offset where an entry's data begins (reads the local file header's name+extra lengths).
    private fun dataOffset(apk: File, name: String): Long {
        RandomAccessFile(apk, "r").use { raf ->
            // Walk local file headers from the start until we hit `name`.
            var pos = 0L
            while (true) {
                raf.seek(pos)
                val sig = readLE32(raf)
                if (sig != 0x04034b50L) break // not a local file header (reached central dir)
                raf.seek(pos + 26)
                val nameLen = readLE16(raf)
                val extraLen = readLE16(raf)
                val nameBytes = ByteArray(nameLen); raf.seek(pos + 30); raf.readFully(nameBytes)
                val entryName = String(nameBytes, Charsets.UTF_8)
                val compSize = run { raf.seek(pos + 18); readLE32(raf) }
                val dataStart = pos + 30 + nameLen + extraLen
                if (entryName == name) return dataStart
                pos = dataStart + compSize
            }
            error("entry $name not found while scanning local headers")
        }
    }

    private fun readLE16(raf: RandomAccessFile): Int {
        val a = raf.read(); val b = raf.read(); return a or (b shl 8)
    }

    private fun readLE32(raf: RandomAccessFile): Long {
        val a = raf.read().toLong(); val b = raf.read().toLong()
        val c = raf.read().toLong(); val d = raf.read().toLong()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }
}
