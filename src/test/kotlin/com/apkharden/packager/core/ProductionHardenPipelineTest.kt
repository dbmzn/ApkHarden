package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.security.MessageDigest
import java.util.Properties
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
    private val sampleDex = requireNotNull(javaClass.getResourceAsStream("/shell.dex")).use { it.readBytes() }

    @Test
    fun `upload-only hardening encrypts business dex and writes signed shell plus report`() {
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
        assertEquals(1, result.report.businessDexCount)
        assertEquals(listOf(Constants.encryptedDexEntry(0)), result.report.encryptedDexEntries)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"), result.report.shellAbis)
        assertEquals(setOf(""), result.report.guardedProcesses)
        ZipFile(output).use { zip ->
            val shellDex = zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
            assertTrue(shellDex.contentEquals(sampleDex))
            val shellText = String(shellDex, Charsets.ISO_8859_1)
            assertTrue(shellText.contains("Lcom/apkharden/shell/ProxyApplication;"))
            assertTrue(shellText.contains("Lcom/apkharden/shell/ShellComponentFactory;"))
            assertTrue(shellText.contains("!/lib/"))
            val encrypted = zip.getInputStream(zip.getEntry(Constants.encryptedDexEntry(0))).readBytes()
            assertTrue(encrypted.copyOfRange(0, 4).contentEquals("APH1".encodeToByteArray()))
            assertTrue(!String(encrypted, Charsets.ISO_8859_1).contains("Lcom/apkharden/guard/GuardProvider;"))
            val metadataEntry = zip.getEntry(Constants.PAYLOAD_METADATA)
            assertNotNull(metadataEntry)
            val metadata = Properties().apply {
                zip.getInputStream(requireNotNull(metadataEntry)).use { load(it) }
            }
            assertEquals("2", metadata.getProperty("formatVersion"))
            assertEquals(sampleDex.size.toString(), metadata.getProperty("dex.0.size"))
            assertEquals(sha256(sampleDex), metadata.getProperty("dex.0.sha256"))
            assertNotNull(zip.getEntry("lib/arm64-v8a/${Constants.SHELL_LIBRARY_NAME}"))
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals(Constants.SHELL_APPLICATION, ManifestPatcher.readApplicationClass(manifest))
            assertEquals(Constants.SHELL_COMPONENT_FACTORY, ManifestPatcher.readApplicationComponentFactory(manifest))
            assertEquals("com.example.upload.App", ManifestPatcher.readMetaData(manifest)[Constants.META_ORIGINAL_APPLICATION])
            assertEquals(64, ManifestPatcher.readMetaData(manifest)[Constants.META_SIG_HASH]?.length)
        }
        assertTrue(result.reportFile.readText().contains("ENCRYPTED_DEX_SHELL"))
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
