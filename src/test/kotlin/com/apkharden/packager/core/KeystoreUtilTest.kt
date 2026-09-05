package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class KeystoreUtilTest {
    private val ks = File("src/test/resources/test.jks")

    @Test
    fun `loads private key and cert chain`() {
        val creds = KeystoreUtil.load(ks, "123456", "test", "123456")
        assertTrue(creds.certificates.isNotEmpty())
        assertEquals("RSA", creds.privateKey.algorithm)
    }

    @Test
    fun `expected signature hash is 64 lowercase hex chars and stable`() {
        val creds = KeystoreUtil.load(ks, "123456", "test", "123456")
        val hash = KeystoreUtil.expectedSigHash(creds)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        // stable across calls
        assertEquals(hash, KeystoreUtil.expectedSigHash(KeystoreUtil.load(ks, "123456", "test", "123456")))
    }

    @Test
    fun `wrong store password throws`() {
        try {
            KeystoreUtil.load(ks, "wrong", "test", "123456")
            throw AssertionError("expected failure")
        } catch (e: Exception) { /* expected */ }
    }
}
