package com.apkharden.release.workflow

import com.apkharden.release.apk.ApkDigest
import com.apkharden.release.device.DeviceScenario
import com.apkharden.release.device.DeviceTestResult
import com.apkharden.release.device.DeviceVerificationReportCodec
import com.apkharden.release.fixture.TestApkFactory
import com.apkharden.release.model.ReleaseRequest
import com.apkharden.release.model.ReleaseStatus
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProductionReleaseQualificationTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `device results qualify an existing static bundle`() {
        val request = request()
        val bundle = ProductionReleaseWorkflow.export(request, File(temp, "output"))
        val digest = ApkDigest.sha256(bundle.apk)
        val report = File(temp, "device-results.json")
        DeviceVerificationReportCodec.write(
            "com.example.app",
            11,
            digest,
            DeviceScenario.entries.map { scenario ->
                DeviceTestResult(scenario, true, 36, if (scenario == DeviceScenario.API_36_16K_SURVIVES) 16384 else 4096, listOf("arm64-v8a"), "com.example.app", 11, digest)
            },
            report,
        )

        val qualified = ProductionReleaseWorkflow.qualify(bundle.directory, report)

        assertEquals(ReleaseStatus.RELEASE_QUALIFIED, qualified.assessment.status)
        assertTrue(qualified.deviceQualification?.qualified == true)
        assertTrue(File(bundle.directory, "device-test-results.json").isFile)
        assertTrue(bundle.jsonReport.readText().contains("RELEASE_QUALIFIED"))
    }

    private fun request(): ReleaseRequest {
        val online = TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, "com.example.app", 10),
            File(temp, "online.apk"),
        )
        val candidate = TestApkFactory.createUnsigned(temp, "com.example.app", 11)
        val metadata = TestApkFactory.metadata(File(temp, "metadata.json"), "com.example.app", 11, emptySet())
        return ReleaseRequest(online, candidate, metadata, TestApkFactory.keystoreRequest())
    }
}
