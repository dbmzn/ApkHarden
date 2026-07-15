package com.apkharden.release.device

import com.apkharden.release.model.ApkIdentity
import com.apkharden.release.model.ApkSignatureInfo
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeviceQualificationTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `matrix requires every scenario and report integrity is checked`() {
        val candidate = identity()
        val digest = fingerprint(candidate)
        val output = File(temp, "device-results.json")
        DeviceVerificationReportCodec.write(
            candidate.packageName,
            candidate.versionCode,
            digest,
            DeviceScenario.entries.map { scenario ->
                DeviceTestResult(scenario, true, 36, if (scenario == DeviceScenario.API_36_16K_SURVIVES) 16384 else 4096, listOf("arm64-v8a"), candidate.packageName, candidate.versionCode, digest)
            },
            output,
        )

        val result = DeviceQualificationEvaluator.evaluate(candidate, digest, DeviceVerificationReportCodec.read(output))

        assertTrue(result.qualified)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `failed scenario is a blocker`() {
        val candidate = identity()
        val digest = fingerprint(candidate)
        val output = File(temp, "failed.json")
        DeviceVerificationReportCodec.write(
            candidate.packageName,
            candidate.versionCode,
            digest,
            DeviceScenario.entries.map { scenario ->
                DeviceTestResult(scenario, scenario != DeviceScenario.API_36_16K_SURVIVES, 36, 16384, listOf("arm64-v8a"), candidate.packageName, candidate.versionCode, digest)
            },
            output,
        )

        val result = DeviceQualificationEvaluator.evaluate(candidate, digest, DeviceVerificationReportCodec.read(output))

        assertFalse(result.qualified)
        assertTrue(result.findings.any { it.code == "DEVICE_SCENARIO_FAILED" })
    }

    private fun identity() = ApkIdentity(
        fileName = "candidate.apk",
        packageName = "com.example.app",
        versionCode = 11,
        versionName = "1.0",
        minSdk = 23,
        targetSdk = 36,
        debuggable = false,
        testOnly = false,
        splitName = null,
        extractNativeLibs = null,
        requiredFeatures = emptySet(),
        abis = setOf("arm64-v8a"),
        signature = ApkSignatureInfo(true, setOf("ab".repeat(32)), 1, false, true, true, true, false),
    )

    private fun fingerprint(candidate: ApkIdentity): String =
        listOf(candidate.packageName, candidate.versionCode, candidate.fileName).joinToString(":")
}
