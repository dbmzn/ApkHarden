package com.apkharden.release.crypto

import com.apkharden.release.fixture.TestApkFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeystoreReaderTest {
    @Test
    fun `loads leaf certificate identity`() {
        val loaded = KeystoreReader.load(TestApkFactory.keystoreRequest())

        assertEquals(64, loaded.identity.certificateSha256.length)
        assertEquals("CN=ApkHarden Test", loaded.identity.certificateSubject)
        assertEquals("test", loaded.identity.alias)
    }
}
