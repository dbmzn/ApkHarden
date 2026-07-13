package com.apkharden.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardenConfigTest {
    private val cert = "A".repeat(64)

    @Test fun `config normalizes certificate and preserves variant identity`() {
        val config = HardenConfig("com.example.app", "product_64", "build-1", cert)
        assertEquals(cert.lowercase(), config.certificateSha256)
        assertEquals("product_64", config.variantName)
    }

    @Test fun `config rejects blank identity and malformed certificate`() {
        assertThrows(IllegalArgumentException::class.java) {
            HardenConfig("", "release", "build", cert)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardenConfig("com.example", "release", "build", "aa")
        }
    }

    @Test fun `certificate match requires exactly one signer`() {
        val signer = byteArrayOf(1, 2, 3)
        val expected = CertificateVerifier.sha256(signer)
        assertTrue(CertificateVerifier.matchesSigners(listOf(signer), expected))
        assertFalse(CertificateVerifier.matchesSigners(emptyList(), expected))
        assertFalse(CertificateVerifier.matchesSigners(listOf(signer, signer), expected))
    }
}
