package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApkExplorerTest {
    @TempDir lateinit var temp: File

    @Test
    fun `manifest explorer reads components processes and deep links`() {
        val apk = fixture("manifest.apk", 100)

        val report = ManifestExplorer.analyze(apk)

        assertEquals("com.example.explorer", report.packageName)
        assertTrue("android.permission.CAMERA" in report.permissions)
        assertTrue(":remote" in report.processes)
        assertTrue(report.deepLinks.any { it == "https://example.com/product" })
        assertTrue(report.components.any { it.type == "provider" && it.risk == ManifestRisk.HIGH })
    }

    @Test
    fun `archive explorer groups files and ranks size changes`() {
        val oldApk = fixture("old.apk", 100)
        val newApk = fixture("new.apk", 400)

        val report = ApkArchiveExplorer.analyze(oldApk)
        val comparison = ApkArchiveExplorer.compare(oldApk, newApk)

        assertEquals(128, report.categorySizes[ApkEntryCategory.DEX])
        assertEquals(256, report.categorySizes[ApkEntryCategory.NATIVE])
        assertTrue(comparison.changes.first { it.name == "assets/payload.bin" }.delta == 300L)
    }

    private fun fixture(name: String, assetSize: Int): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.explorer"
            versionCode = 1
            minSdkVersion = 23
            targetSdkVersion = 30
            addUsesPermission("android.permission.CAMERA")
            val app = getOrCreateApplicationElement()
            val activity = getOrCreateMainActivity("com.example.explorer.MainActivity")
            activity.getOrCreateAndroidAttribute("exported", 0x01010010).valueAsBoolean = true
            activity.getOrCreateAndroidAttribute("process", 0x01010011).valueAsString = ":remote"
            val filter = activity.newElement("intent-filter")
            val data = filter.newElement("data")
            data.getOrCreateAndroidAttribute("scheme", 0x01010027).valueAsString = "https"
            data.getOrCreateAndroidAttribute("host", 0x01010028).valueAsString = "example.com"
            data.getOrCreateAndroidAttribute("path", 0x0101002a).valueAsString = "/product"
            val provider = app.newElement("provider")
            provider.getOrCreateAndroidAttribute("name", 0x01010003).valueAsString = "com.example.OpenProvider"
            provider.getOrCreateAndroidAttribute("exported", 0x01010010).valueAsBoolean = true
            provider.getOrCreateAndroidAttribute("authorities", 0x01010018).valueAsString = "com.example.open"
            refreshFull()
        }.bytes
        return File(temp, name).also { apk ->
            ZipOutputStream(apk.outputStream()).use { zip ->
                fun add(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                }
                add("AndroidManifest.xml", manifest)
                add("classes.dex", ByteArray(128))
                add("lib/arm64-v8a/libsample.so", ByteArray(256))
                add("assets/payload.bin", ByteArray(assetSize))
                add("res/drawable/icon.png", ByteArray(64))
            }
        }
    }
}
