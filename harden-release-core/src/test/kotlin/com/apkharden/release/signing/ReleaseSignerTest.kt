package com.apkharden.release.signing

import com.apkharden.release.apk.ApkSignatureReader
import com.apkharden.release.crypto.CertificateDigests
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.fixture.TestApkFactory
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseSignerTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `signing preserves non-signature entries and creates expected V1 V2 V3 signer`() {
        val input = TestApkFactory.createUnsigned(temp, "com.example.app", 11)
        val before = content(input)
        val output = File(temp, "release.apk")

        ReleaseSigner.sign(input, output, TestApkFactory.keystoreRequest())

        assertEquals(before, content(output))
        val signature = ApkSignatureReader.read(output)
        assertTrue(signature.verified && signature.v1 && signature.v2 && signature.v3)
        val expectedCertificate = KeystoreReader.load(TestApkFactory.keystoreRequest())
            .certificateChain
            .first()
        assertEquals(
            setOf(CertificateDigests.sha256(expectedCertificate)),
            signature.signerSha256,
        )
    }


    @Test
    fun `refuses to overwrite the input APK in place`() {
        val input = TestApkFactory.createUnsigned(temp, "com.example.same", 11)
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseSigner.sign(input, input, TestApkFactory.keystoreRequest())
        }
    }    private fun content(apk: File): Map<String, EntryFingerprint> = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filterNot { it.name.startsWith("META-INF/") }
            .associate { entry ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(zip.getInputStream(entry).use { it.readBytes() })
                    .joinToString("") { "%02x".format(it) }
                entry.name to EntryFingerprint(
                    method = entry.method,
                    size = entry.size,
                    crc = entry.crc,
                    sha256 = digest,
                )
            }
    }

    private data class EntryFingerprint(
        val method: Int,
        val size: Long,
        val crc: Long,
        val sha256: String,
    )
}
