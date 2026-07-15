package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManifestPatcherTest {

    private fun baseManifest(appName: String?): ByteArray {
        val m = AndroidManifestBlock()
        m.packageName = "com.example.demo"
        m.getOrCreateApplicationElement()
        if (appName != null) m.applicationClassName = appName
        m.refreshFull()
        return m.bytes
    }

    @Test
    fun `reads original application class name`() {
        val bytes = baseManifest("com.example.demo.MyApp")
        assertEquals("com.example.demo.MyApp", ManifestPatcher.readApplicationClass(bytes))
    }

    @Test
    fun `null when no custom application`() {
        val bytes = baseManifest(null)
        assertNull(ManifestPatcher.readApplicationClass(bytes))
    }

    @Test
    fun `static guard preserves application and covers main and explicit processes`() {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"
            applicationClassName = "com.example.demo.MyApp"
            getOrCreateApplicationElement().newElement("service").apply {
                getOrCreateAndroidAttribute("process", 0x01010011).valueAsString = ":remote"
            }
            refreshFull()
        }.bytes

        val patched = ManifestPatcher.patchGuard(manifest, "ab".repeat(32))

        assertEquals("com.example.demo.MyApp", ManifestPatcher.readApplicationClass(patched))
        assertEquals("ab".repeat(32), ManifestPatcher.readMetaData(patched)[Constants.META_SIG_HASH])
        assertEquals(setOf("", ":remote"), ManifestPatcher.guardProcesses(patched))
    }

    @Test
    fun `static guard rejects an already protected APK`() {
        val once = ManifestPatcher.patchGuard(baseManifest(null), "ab".repeat(32))

        assertThrows(IllegalArgumentException::class.java) {
            ManifestPatcher.patchGuard(once, "ab".repeat(32))
        }
    }
}
