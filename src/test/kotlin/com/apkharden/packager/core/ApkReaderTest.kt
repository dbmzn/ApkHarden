package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkReaderTest {
    @TempDir lateinit var tmp: File

    private fun fakeApk(): File {
        val f = File(tmp, "in.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(byteArrayOf(3, 0, 8, 0)); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write("dex0".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes2.dex")); z.write("dex1".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("res/layout/a.xml")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/CERT.RSA")); z.write("old".toByteArray()); z.closeEntry()
        }
        return f
    }

    @Test
    fun `finds dex entries in order`() {
        ApkReader(fakeApk()).use { r ->
            assertEquals(listOf("classes.dex", "classes2.dex"), r.dexNames())
            assertArrayEquals("dex0".toByteArray(), r.read("classes.dex"))
        }
    }

    @Test
    fun `reads manifest bytes`() {
        ApkReader(fakeApk()).use { r ->
            assertArrayEquals(byteArrayOf(3, 0, 8, 0), r.manifestBytes())
        }
    }

    @Test
    fun `lists all entry names`() {
        ApkReader(fakeApk()).use { r ->
            assertTrue(r.entryNames().contains("res/layout/a.xml"))
            assertTrue(r.entryNames().contains("META-INF/CERT.RSA"))
        }
    }
}
