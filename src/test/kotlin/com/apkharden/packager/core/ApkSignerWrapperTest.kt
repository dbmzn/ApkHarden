package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkSignerWrapperTest {
    @TempDir lateinit var tmp: File
    private val ks = File("src/test/resources/test.jks")

    private fun minimalApk(): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"; refreshFull()
        }.bytes
        val f = File(tmp, "unsigned.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(64)); z.closeEntry()
        }
        return f
    }

    @Test
    fun `signs and verifies`() {
        val out = File(tmp, "signed.apk")
        val creds = KeystoreUtil.load(ks, "123456", "test", "123456")
        ApkSignerWrapper.sign(minimalApk(), out, creds)
        assertTrue(out.exists() && out.length() > 0)
        assertTrue(ApkSignerWrapper.verify(out))
    }
}
