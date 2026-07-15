package com.apkharden.release.report

import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.fixture.TestApkFactory
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseArtifactWriterTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `writes certificate pem and sorted checksums`() {
        val certificate = KeystoreReader.load(TestApkFactory.keystoreRequest())
            .certificateChain.first()
        val pem = File(temp, "signer-certificate.pem")
        val alpha = File(temp, "alpha.txt").apply { writeText("alpha") }
        val zeta = File(temp, "zeta.txt").apply { writeText("zeta") }
        val checksums = File(temp, "checksums.sha256")

        ReleaseArtifactWriter.writeCertificatePem(certificate, pem)
        ReleaseArtifactWriter.writeChecksums(listOf(zeta, pem, alpha), checksums)

        assertTrue(pem.readText().startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.readText().endsWith("-----END CERTIFICATE-----\n"))
        val lines = checksums.readLines()
        assertEquals(listOf("alpha.txt", "signer-certificate.pem", "zeta.txt"), lines.map { it.substringAfter("  ") })
        assertTrue(lines.all { it.substringBefore("  ").matches(Regex("[0-9a-f]{64}")) })
    }
}
