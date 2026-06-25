package com.apkharden.packager.scanner

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManifestReaderTest {
    private fun manifest(): ByteArray = AndroidManifestBlock().apply {
        packageName = "com.example.demo"
        versionName = "2.3.1"
        addUsesPermission("android.permission.READ_PHONE_STATE")
        addUsesPermission("android.permission.INTERNET")
        refreshFull()
    }.bytes

    @Test
    fun `reads package, version and permissions`() {
        val info = ManifestReader.parse(manifest())
        assertEquals("com.example.demo", info.packageName)
        assertEquals("2.3.1", info.versionName)
        assertTrue(info.permissions.contains("android.permission.READ_PHONE_STATE"))
        assertTrue(info.permissions.contains("android.permission.INTERNET"))
    }
}
