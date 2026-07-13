package com.apkharden.release.apk

import com.apkharden.release.fixture.TestApkFactory
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApkSignatureReaderTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `unsigned APK returns unverified identity`() {
        val apk = TestApkFactory.createUnsigned(temp, "com.example.unsigned", 1)
        assertFalse(ApkSignatureReader.read(apk).verified)
    }

    @Test
    fun `signed APK reports one V1 V2 V3 signer`() {
        val unsigned = TestApkFactory.createUnsigned(temp, "com.example.signed", 1)
        val signed = TestApkFactory.sign(unsigned, File(temp, "signed.apk"))

        val info = ApkSignatureReader.read(signed)

        assertTrue(info.verified)
        assertTrue(info.v1 && info.v2 && info.v3)
        assertTrue(info.signerCount == 1)
        assertFalse(info.hasSigningLineage)
        assertTrue(info.signerSha256.single().length == 64)
    }
}
