package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApkToolboxTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `analyze summarizes contents and release blockers`() {
        val apk = fixture("old.apk", version = 10, debuggable = true, extraBytes = 64)

        val report = ApkToolbox.analyze(apk)

        assertEquals("com.example.toolbox", report.info.packageName)
        assertEquals(1, report.dexCount)
        assertEquals(1, report.nativeCount)
        assertTrue(report.blockerCount >= 2) // unsigned fixture + debuggable
        assertTrue(report.largestEntries.isNotEmpty())
    }

    @Test
    fun `compare reports version and file changes`() {
        val oldApk = fixture("old.apk", version = 10, debuggable = false, extraBytes = 32)
        val newApk = fixture("new.apk", version = 11, debuggable = false, extraBytes = 96)

        val comparison = ApkToolbox.compare(oldApk, newApk)

        assertEquals(10, comparison.oldReport.info.versionCode)
        assertEquals(11, comparison.newReport.info.versionCode)
        assertTrue(comparison.changes.any { it.name == "assets/payload.bin" && it.delta == 64L })
        assertFalse(comparison.signaturesMatch)
    }

    private fun fixture(name: String, version: Int, debuggable: Boolean, extraBytes: Int): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.toolbox"
            versionCode = version
            minSdkVersion = 23
            targetSdkVersion = 30
            getOrCreateApplicationElement()
            isDebuggable = debuggable
            refreshFull()
        }.bytes
        return File(temp, name).also { apk ->
            ZipOutputStream(apk.outputStream()).use { zip ->
                fun entry(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("AndroidManifest.xml", manifest)
                entry("classes.dex", ByteArray(128))
                entry("lib/arm64-v8a/libsample.so", ByteArray(256))
                entry("assets/payload.bin", ByteArray(extraBytes))
            }
        }
    }
}
