package com.apkharden.release.workflow

import com.apkharden.release.apk.ApkSignatureReader
import com.apkharden.release.fixture.TestApkFactory
import com.apkharden.release.model.ReleaseRequest
import com.apkharden.release.model.ReleaseStatus
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProductionReleaseWorkflowTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `static verified candidate exports a complete signed bundle`() {
        val request = validRequest()
        val output = File(temp, "output")

        val bundle = ProductionReleaseWorkflow.export(request, output)

        assertEquals(ReleaseStatus.STATIC_VERIFIED, bundle.assessment.status)
        assertEquals(
            setOf(
                "app-fixture-v11-hardened.apk",
                "release-report.json",
                "release-report.html",
                "signer-certificate.pem",
                "checksums.sha256",
            ),
            bundle.directory.listFiles().orEmpty().map(File::getName).toSet(),
        )
        val signature = ApkSignatureReader.read(bundle.apk)
        assertTrue(signature.verified && signature.v1 && signature.v2 && signature.v3)
        val report = Json.parseToJsonElement(bundle.jsonReport.readText()).jsonObject
        assertEquals("STATIC_VERIFIED", report.getValue("status").jsonPrimitive.content)
        assertTrue(bundle.htmlReport.readText().contains("Status: STATIC_VERIFIED"))
        assertEquals(4, bundle.checksums.readLines().size)
    }

    @Test
    fun `blocked candidate exports no release bundle`() {
        val online = signed("com.example.blocked", 10, "blocked-online.apk")
        val candidate = TestApkFactory.createUnsigned(temp, "com.example.blocked", 10)
        val metadata = TestApkFactory.metadata(
            File(temp, "blocked-metadata.json"),
            "com.example.blocked",
            10,
            emptySet(),
        )
        val output = File(temp, "blocked-output")

        val error = assertThrows(ReleaseBlockedException::class.java) {
            ProductionReleaseWorkflow.export(
                ReleaseRequest(online, candidate, metadata, TestApkFactory.keystoreRequest()),
                output,
            )
        }

        assertTrue(error.assessment.findings.any { it.code == "VERSION_NOT_INCREMENTED" })
        assertFalse(output.exists())
    }

    @Test
    fun `reports are reproducible from the same assessment`() {
        val request = validRequest()

        val first = ProductionReleaseWorkflow.export(request, File(temp, "first"))
        val second = ProductionReleaseWorkflow.export(request, File(temp, "second"))

        assertEquals(first.jsonReport.readText(), second.jsonReport.readText())
        assertEquals(first.htmlReport.readText(), second.htmlReport.readText())
        assertEquals(first.certificatePem.readText(), second.certificatePem.readText())
    }

    private fun validRequest(): ReleaseRequest {
        val online = signed("com.example.app", 10, "online.apk")
        val candidate = TestApkFactory.createUnsigned(temp, "com.example.app", 11)
        val metadata = TestApkFactory.metadata(
            File(temp, "metadata.json"),
            "com.example.app",
            11,
            emptySet(),
        )
        return ReleaseRequest(
            onlineApk = online,
            candidateApk = candidate,
            metadataFile = metadata,
            keystore = TestApkFactory.keystoreRequest(),
        )
    }

    private fun signed(packageName: String, versionCode: Int, name: String): File =
        TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, packageName, versionCode),
            File(temp, name),
        )
}
