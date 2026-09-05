package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApkInspectorTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `reads hardening compatibility fields and long version code`() {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.inspect"
            versionCode = 120
            manifestElement.getOrCreateAndroidAttribute("versionCodeMajor", 0x01010576).apply {
                valueType = ValueType.DEC
                data = 1
            }
            minSdkVersion = 23
            targetSdkVersion = 36
            isDebuggable = false
            setSplit("config.arm64_v8a", true)
            getOrCreateApplicationElement()
                .getOrCreateAndroidAttribute("testOnly", 0x01010272)
                .valueAsBoolean = true
            refreshFull()
        }.bytes
        val apk = File(temp, "inspect.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(16))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libfixture.so"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }

        val value = ApkInspector.inspect(apk)

        assertEquals("com.example.inspect", value.packageName)
        assertEquals((1L shl 32) or 120L, value.versionCode)
        assertEquals(23, value.minSdk)
        assertEquals(36, value.targetSdk)
        assertEquals("config.arm64_v8a", value.splitName)
        assertEquals(setOf("arm64-v8a"), value.abis)
        assertTrue(value.permissions.isEmpty())
        assertTrue(value.exportedComponents.isEmpty())
        assertTrue(value.testOnly)
        assertFalse(value.debuggable)
        assertFalse(value.signature.verified)
    }
}
