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
    fun `encrypted shell preserves original entry points and installs shell factory`() {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"
            applicationClassName = "com.example.demo.MyApp"
            getOrCreateApplicationElement()
                .getOrCreateAndroidAttribute("appComponentFactory", 0x0101057a)
                .valueAsString = "androidx.core.app.CoreComponentFactory"
            refreshFull()
        }.bytes

        val result = ManifestPatcher.patchEncryptedShell(manifest, "cd".repeat(32), dexCount = 3)

        assertEquals(Constants.SHELL_APPLICATION, ManifestPatcher.readApplicationClass(result.bytes))
        assertEquals(Constants.SHELL_COMPONENT_FACTORY, ManifestPatcher.readApplicationComponentFactory(result.bytes))
        assertEquals("com.example.demo.MyApp", result.originalApplication)
        assertEquals("androidx.core.app.CoreComponentFactory", result.originalComponentFactory)
        val metadata = ManifestPatcher.readMetaData(result.bytes)
        assertEquals("com.example.demo.MyApp", metadata[Constants.META_ORIGINAL_APPLICATION])
        assertEquals("androidx.core.app.CoreComponentFactory", metadata[Constants.META_ORIGINAL_COMPONENT_FACTORY])
        assertEquals("3", metadata[Constants.META_DEX_COUNT])
        assertEquals(setOf(""), ManifestPatcher.guardProcesses(result.bytes))
    }

    @Test
    fun `encrypted shell rejects re-hardening`() {
        val once = ManifestPatcher.patchEncryptedShell(baseManifest(null), "ef".repeat(32), 1).bytes

        assertThrows(IllegalArgumentException::class.java) {
            ManifestPatcher.patchEncryptedShell(once, "ef".repeat(32), 1)
        }
    }
}
