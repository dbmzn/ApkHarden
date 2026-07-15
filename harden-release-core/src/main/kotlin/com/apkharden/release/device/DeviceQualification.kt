package com.apkharden.release.device

import com.apkharden.release.model.ApkIdentity
import com.apkharden.release.model.FindingLevel
import com.apkharden.release.model.ReleaseFinding
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class DeviceScenario {
    API_26_SURVIVES,
    API_36_4K_SURVIVES,
    API_36_16K_SURVIVES,
    API_36_WRONG_SIGNER_TERMINATES,
    API_36_DEBUGGER_TERMINATES,
}

@Serializable
data class DeviceTestResult(
    val scenario: DeviceScenario,
    val passed: Boolean,
    val apiLevel: Int,
    val pageSize: Int,
    val abis: List<String>,
    val packageName: String,
    val versionCode: Long,
    val candidateSha256: String,
    val anrDetected: Boolean = false,
    val crashDetected: Boolean = false,
    val forbiddenLogs: List<String> = emptyList(),
    val diagnostics: String = "",
)

@Serializable
data class DeviceVerificationReport(
    val schemaVersion: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val candidateSha256: String,
    val results: List<DeviceTestResult>,
    val reportSha256: String,
)

data class DeviceQualification(
    val qualified: Boolean,
    val findings: List<ReleaseFinding>,
    val report: DeviceVerificationReport? = null,
)

object DeviceQualificationMatrix {
    val required: Set<DeviceScenario> = DeviceScenario.entries.toSet()
}

object DeviceVerificationReportCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun write(
        packageName: String,
        versionCode: Long,
        candidateSha256: String,
        results: List<DeviceTestResult>,
        output: File,
    ): DeviceVerificationReport {
        val unsigned = DeviceVerificationReport(
            packageName = packageName,
            versionCode = versionCode,
            candidateSha256 = candidateSha256,
            results = results.sortedBy { it.scenario.name },
            reportSha256 = "",
        )
        val signed = unsigned.copy(reportSha256 = digest(unsigned))
        output.parentFile.mkdirs()
        output.writeText(json.encodeToString(signed) + "\n", Charsets.UTF_8)
        return signed
    }

    fun read(file: File): DeviceVerificationReport {
        val report = json.decodeFromString<DeviceVerificationReport>(file.readText())
        require(report.reportSha256 == digest(report.copy(reportSha256 = ""))) {
            "Device verification report integrity check failed"
        }
        return report
    }

    private fun digest(report: DeviceVerificationReport): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(report).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

object DeviceQualificationEvaluator {
    fun evaluate(
        candidate: ApkIdentity,
        candidateSha256: String,
        report: DeviceVerificationReport,
    ): DeviceQualification {
        val findings = buildList {
            blocker(report.packageName != candidate.packageName, "DEVICE_PACKAGE_MISMATCH", "Device report package differs from candidate")
            blocker(report.versionCode != candidate.versionCode, "DEVICE_VERSION_MISMATCH", "Device report version differs from candidate")
            blocker(report.candidateSha256 != candidateSha256, "DEVICE_APK_MISMATCH", "Device report candidate digest differs from candidate")
            val byScenario = report.results.groupBy(DeviceTestResult::scenario)
            DeviceQualificationMatrix.required.forEach { scenario ->
                val matches = byScenario[scenario].orEmpty()
                blocker(matches.size != 1, "DEVICE_SCENARIO_MISSING_OR_DUPLICATE", "Required device scenario is missing or duplicated: $scenario")
                matches.singleOrNull()?.let { result ->
                    blocker(!result.passed, "DEVICE_SCENARIO_FAILED", "Device scenario failed: $scenario")
                    blocker(result.anrDetected, "DEVICE_ANR", "Device scenario reported ANR: $scenario")
                    blocker(result.crashDetected, "DEVICE_CRASH", "Device scenario reported crash: $scenario")
                    blocker(result.forbiddenLogs.isNotEmpty(), "DEVICE_FORBIDDEN_LOG", "Device scenario reported forbidden runtime logs: $scenario")
                }
            }
        }
        return DeviceQualification(findings.isEmpty(), findings, report)
    }

    private fun MutableList<ReleaseFinding>.blocker(condition: Boolean, code: String, message: String) {
        if (condition) add(ReleaseFinding(code, FindingLevel.BLOCKER, message))
    }

}
