package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
}
