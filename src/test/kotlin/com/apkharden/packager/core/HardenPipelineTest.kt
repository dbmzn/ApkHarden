package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class HardenPipelineTest {
    @TempDir lateinit var tmp: File
    private val ks = File("src/test/resources/test.jks")

    private fun appApk(appClass: String?): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"
            if (appClass != null) applicationClassName = appClass
            refreshFull()
        }.bytes
        val f = File(tmp, "app.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write("REALDEX".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(ByteArray(8)); z.closeEntry()
        }
        return f
    }

    @Test
    fun `hardens app end to end`() {
        val out = File(tmp, "hardened.apk")
        val logs = mutableListOf<String>()
        HardenPipeline.harden(
            input = appApk("com.example.demo.MyApp"),
            output = out,
            keystore = ks, storePass = "123456", alias = "test", keyPass = "123456",
            log = { logs.add(it) },
        )

        assertTrue(ApkSignerWrapper.verify(out))
        ZipFile(out).use { z ->
            // shell is now classes.dex
            assertNotNull(z.getEntry("classes.dex"))
            // original real dex is encrypted in assets/d/0 (not plaintext)
            assertNotNull(z.getEntry(Constants.encryptedDexEntry(0)))
            val enc = z.getInputStream(z.getEntry(Constants.encryptedDexEntry(0))).readBytes()
            assertFalse(enc.contentEquals("REALDEX".toByteArray()))
            // manifest now points at proxy
            val mBytes = z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals(Constants.PROXY_APPLICATION, ManifestPatcher.readApplicationClass(mBytes))
            val md = ManifestPatcher.readMetaData(mBytes)
            assertEquals("com.example.demo.MyApp", md[Constants.META_APP_NAME])
            assertEquals("1", md[Constants.META_DEX_COUNT])
            assertEquals(64, md[Constants.META_SIG_HASH]!!.length)
        }
        assertTrue(logs.isNotEmpty())
    }
}
