package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProductionHardenPipelineTest {
    @TempDir
    lateinit var temp: File

    private val keystore = File("src/test/resources/test.jks")
    private val sampleDex = "BUSINESS-DEX-CONTENT".toByteArray()

    @Test
    fun `upload-only hardening preserves business dex and writes signed output and report`() {
        val input = apk(debuggable = false)
        val output = File(temp, "app-hardened.apk")

        val result = ProductionHardenPipeline.harden(
            input = input,
            output = output,
            keystore = keystore,
            storePass = "123456",
            alias = "test",
            keyPass = "123456",
        )

        assertTrue(ApkSignerWrapper.verify(result.apk))
        assertTrue(result.reportFile.isFile)
        assertEquals("com.example.upload", result.report.packageName)
        assertEquals("classes2.dex", result.report.guardDexEntry)
        assertEquals(setOf(""), result.report.guardedProcesses)
        ZipFile(output).use { zip ->
            assertTrue(zip.getInputStream(zip.getEntry("classes.dex")).readBytes().contentEquals(sampleDex))
            assertNotNull(zip.getEntry("classes2.dex"))
            val guardDex = zip.getInputStream(zip.getEntry("classes2.dex")).readBytes()
            val guardText = String(guardDex, Charsets.ISO_8859_1)
            assertTrue(guardText.contains("Lcom/apkharden/guard/AntiDebug;"))
            assertTrue(guardText.contains("Lcom/apkharden/guard/AntiTamper;"))
            assertTrue(guardText.contains("Lcom/apkharden/guard/GuardProvider;"))
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals("com.example.upload.App", ManifestPatcher.readApplicationClass(manifest))
            assertEquals(64, ManifestPatcher.readMetaData(manifest)[Constants.META_SIG_HASH]?.length)
        }
        assertTrue(result.reportFile.readText().contains("STATIC_GUARD"))
    }

    @Test
    fun `debuggable APK is rejected before producing an output`() {
        val output = File(temp, "debug-hardened.apk")

        assertThrows(IllegalArgumentException::class.java) {
            ProductionHardenPipeline.harden(
                input = apk(debuggable = true),
                output = output,
                keystore = keystore,
                storePass = "123456",
                alias = "test",
                keyPass = "123456",
            )
        }
        assertTrue(!output.exists())
    }

    private fun apk(debuggable: Boolean): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.upload"
            applicationClassName = "com.example.upload.App"
            versionCode = 7
            minSdkVersion = 23
            targetSdkVersion = 36
            isDebuggable = debuggable
            refreshFull()
        }.bytes
        return File(temp, if (debuggable) "debug.apk" else "release.apk").also { output ->
            ZipOutputStream(output.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(manifest)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(sampleDex)
                zip.closeEntry()
            }
        }
    }
}
