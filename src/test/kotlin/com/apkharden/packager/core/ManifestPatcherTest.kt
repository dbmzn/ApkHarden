package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManifestPatcherTest {

    private fun baseManifest(appName: String?): ByteArray {
        val m = AndroidManifestBlock()
        m.packageName = "com.example.demo"
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
    fun `patch sets proxy and adds meta-data`() {
        val patched = ManifestPatcher.patch(
            manifestBytes = baseManifest("com.example.demo.MyApp"),
            originalAppClass = "com.example.demo.MyApp",
            sigHash = "abc123",
            dexCount = 2,
        )
        assertEquals(Constants.PROXY_APPLICATION, ManifestPatcher.readApplicationClass(patched))
        val md = ManifestPatcher.readMetaData(patched)
        assertEquals("com.example.demo.MyApp", md[Constants.META_APP_NAME])
        assertEquals("abc123", md[Constants.META_SIG_HASH])
        assertEquals("2", md[Constants.META_DEX_COUNT])
    }
}
