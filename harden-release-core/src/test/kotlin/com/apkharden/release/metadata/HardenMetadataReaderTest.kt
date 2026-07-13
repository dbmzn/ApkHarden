package com.apkharden.release.metadata

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HardenMetadataReaderTest {
    @TempDir
    lateinit var temp: File

    private val certificate = "A".repeat(64)

    @Test
    fun `reads R8-disabled product variant metadata and normalizes certificate`() {
        val file = File(temp, "metadata.json")
        file.writeText(metadataJson(schemaVersion = 1))

        val metadata = HardenMetadataReader.read(file)

        assertEquals("product_32", metadata.variantName)
        assertEquals(false, metadata.r8Enabled)
        assertEquals(certificate.lowercase(), metadata.expectedCertificateSha256)
    }

    @Test
    fun `rejects unsupported schema`() {
        val file = File(temp, "metadata.json")
        file.writeText(metadataJson(schemaVersion = 2))

        assertThrows(IllegalArgumentException::class.java) {
            HardenMetadataReader.read(file)
        }
    }

    @Test
    fun `rejects non SHA-256 certificate`() {
        val file = File(temp, "metadata.json")
        file.writeText(metadataJson(schemaVersion = 1).replace(certificate, "aa"))

        assertThrows(IllegalArgumentException::class.java) {
            HardenMetadataReader.read(file)
        }
    }

    private fun metadataJson(schemaVersion: Int): String =
        """
        {
          "schemaVersion": $schemaVersion,
          "pluginVersion": "1.0.0",
          "runtimeVersion": "1.0.0",
          "variantName": "product_32",
          "applicationId": "com.example.app",
          "versionCode": 120,
          "minSdk": 23,
          "targetSdk": 36,
          "debuggable": false,
          "r8Enabled": false,
          "abis": ["armeabi-v7a"],
          "expectedCertificateSha256": "$certificate",
          "buildId": "build-1"
        }
        """.trimIndent()
}
