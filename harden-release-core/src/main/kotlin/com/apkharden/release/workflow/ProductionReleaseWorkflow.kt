package com.apkharden.release.workflow

import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.apk.ApkDigest
import com.apkharden.release.device.DeviceQualificationEvaluator
import com.apkharden.release.device.DeviceQualification
import com.apkharden.release.device.DeviceVerificationReport
import com.apkharden.release.device.DeviceVerificationReportCodec
import com.apkharden.release.gate.ReleaseAnalyzer
import com.apkharden.release.metadata.HardenMetadataReader
import com.apkharden.release.model.ReleaseAssessment
import com.apkharden.release.model.ReleaseRequest
import com.apkharden.release.model.ReleaseStatus
import com.apkharden.release.report.ReleaseArtifactWriter
import com.apkharden.release.report.ReleaseHtmlReportWriter
import com.apkharden.release.report.ReleaseReportWriter
import com.apkharden.release.report.ReleaseReportReader
import com.apkharden.release.signing.ReleaseSigner
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

data class ProductionReleaseBundle(
    val directory: File,
    val apk: File,
    val jsonReport: File,
    val htmlReport: File,
    val certificatePem: File,
    val checksums: File,
    val assessment: ReleaseAssessment,
    val deviceQualification: DeviceQualification? = null,
)

class ReleaseBlockedException(
    message: String,
    val assessment: ReleaseAssessment,
) : IllegalStateException(message)

object ProductionReleaseWorkflow {
    fun analyze(request: ReleaseRequest): ReleaseAssessment = ReleaseAnalyzer.analyze(request)

    fun export(
        request: ReleaseRequest,
        outputRoot: File,
        deviceReportFile: File? = null,
    ): ProductionReleaseBundle {
        val preSignAssessment = analyze(request)
        if (preSignAssessment.status != ReleaseStatus.STATIC_VERIFIED) {
            throw ReleaseBlockedException(
                "Release export requires STATIC_VERIFIED pre-sign status",
                preSignAssessment,
            )
        }

        val variant = request.metadataFile
            ?.let(HardenMetadataReader::read)
            ?.variantName
            ?.safeFileComponent("variant")
            ?: "apk"
        val versionCode = requireNotNull(preSignAssessment.candidate).versionCode
        val releaseName = "release-$variant-v$versionCode"
        val root = outputRoot.absoluteFile
        require(root.exists() || root.mkdirs()) { "Unable to create output directory: $root" }
        require(root.isDirectory) { "Release output root is not a directory: $root" }
        val destination = File(root, releaseName)
        require(!destination.exists()) { "Release bundle already exists: $destination" }

        val staging = Files.createTempDirectory(root.toPath(), ".$releaseName-").toFile()
        try {
            val apk = File(staging, "app-$variant-v$versionCode-hardened.apk")
            ReleaseSigner.sign(request.candidateApk, apk, request.keystore)

            val staticAssessment = analyze(request.copy(candidateApk = apk))
            if (staticAssessment.status != ReleaseStatus.STATIC_VERIFIED) {
                throw ReleaseBlockedException(
                    "Signed APK failed post-sign static verification",
                    staticAssessment,
                )
            }

            val deviceQualification = deviceReportFile?.let { reportFile ->
                val report = DeviceVerificationReportCodec.read(reportFile)
                DeviceQualificationEvaluator.evaluate(
                    candidate = requireNotNull(staticAssessment.candidate),
                    candidateSha256 = ApkDigest.sha256(apk),
                    report = report,
                )
            }
            if (deviceQualification != null && !deviceQualification.qualified) {
                throw ReleaseBlockedException(
                    "Signed APK failed device qualification",
                    staticAssessment.withDeviceFindings(deviceQualification),
                )
            }
            val postSignAssessment = staticAssessment.withDeviceQualification(deviceQualification)

            val json = File(staging, "release-report.json")
            val html = File(staging, "release-report.html")
            val pem = File(staging, "signer-certificate.pem")
            val checksums = File(staging, "checksums.sha256")
            val deviceResults = deviceReportFile?.let { reportFile ->
                File(staging, "device-test-results.json").also { output ->
                    reportFile.copyTo(output)
                }
            }
            ReleaseReportWriter.write(postSignAssessment, json)
            ReleaseHtmlReportWriter.write(postSignAssessment, html)
            val certificate = KeystoreReader.load(request.keystore).certificateChain.first()
            ReleaseArtifactWriter.writeCertificatePem(certificate, pem)
            ReleaseArtifactWriter.writeChecksums(
                listOfNotNull(apk, html, json, pem, deviceResults),
                checksums,
            )

            moveDirectory(staging, destination)
            return ProductionReleaseBundle(
                directory = destination,
                apk = File(destination, apk.name),
                jsonReport = File(destination, json.name),
                htmlReport = File(destination, html.name),
                certificatePem = File(destination, pem.name),
                checksums = File(destination, checksums.name),
                assessment = postSignAssessment,
                deviceQualification = deviceQualification,
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun qualify(
        bundleDirectory: File,
        deviceReportFile: File,
    ): ProductionReleaseBundle {
        require(bundleDirectory.isDirectory) { "Release bundle not found: $bundleDirectory" }
        val apk = bundleDirectory.listFiles().orEmpty().singleOrNull { it.name.endsWith("-hardened.apk") }
            ?: error("Release bundle must contain exactly one hardened APK")
        val json = File(bundleDirectory, "release-report.json")
        val html = File(bundleDirectory, "release-report.html")
        val pem = File(bundleDirectory, "signer-certificate.pem")
        val checksums = File(bundleDirectory, "checksums.sha256")
        val current = ReleaseReportReader.read(json)
        require(current.status == ReleaseStatus.STATIC_VERIFIED) {
            "Only STATIC_VERIFIED bundles can be device-qualified"
        }
        val report = DeviceVerificationReportCodec.read(deviceReportFile)
        val qualification = DeviceQualificationEvaluator.evaluate(
            candidate = requireNotNull(current.candidate),
            candidateSha256 = ApkDigest.sha256(apk),
            report = report,
        )
        if (!qualification.qualified) {
            throw ReleaseBlockedException(
                "Device qualification failed",
                current.withDeviceFindings(qualification),
            )
        }
        val qualified = current.withDeviceQualification(qualification)
        val staged = Files.createTempDirectory(bundleDirectory.parentFile.toPath(), ".qualify-").toFile()
        try {
            val stagedJson = File(staged, json.name)
            val stagedHtml = File(staged, html.name)
            val stagedDevice = File(staged, "device-test-results.json")
            val stagedChecksums = File(staged, checksums.name)
            ReleaseReportWriter.write(qualified, stagedJson)
            ReleaseHtmlReportWriter.write(qualified, stagedHtml)
            deviceReportFile.copyTo(stagedDevice)
            ReleaseArtifactWriter.writeChecksums(
                listOf(apk, html, json, pem, stagedDevice).map { file ->
                    if (file.name == json.name) stagedJson
                    else if (file.name == html.name) stagedHtml
                    else file
                },
                stagedChecksums,
            )
            stagedJson.copyTo(json, overwrite = true)
            stagedHtml.copyTo(html, overwrite = true)
            stagedDevice.copyTo(File(bundleDirectory, stagedDevice.name), overwrite = true)
            stagedChecksums.copyTo(checksums, overwrite = true)
            return ProductionReleaseBundle(
                directory = bundleDirectory,
                apk = apk,
                jsonReport = json,
                htmlReport = html,
                certificatePem = pem,
                checksums = checksums,
                assessment = qualified,
                deviceQualification = qualification,
            )
        } finally {
            staged.deleteRecursively()
        }
    }

    private fun ReleaseAssessment.withDeviceQualification(
        qualification: DeviceQualification?,
    ): ReleaseAssessment {
        if (qualification == null) return this
        return ReleaseAssessment(
            findings = findings,
            approvedFindingCodes = approvedFindingCodes,
            online = online,
            candidate = candidate,
            keystore = keystore,
            status = if (qualification.qualified) ReleaseStatus.RELEASE_QUALIFIED else ReleaseStatus.NOT_QUALIFIED,
        )
    }

    private fun ReleaseAssessment.withDeviceFindings(
        qualification: DeviceQualification,
    ): ReleaseAssessment = ReleaseAssessment(
        findings = findings + qualification.findings,
        approvedFindingCodes = approvedFindingCodes,
        online = online,
        candidate = candidate,
        keystore = keystore,
    )

    private fun moveDirectory(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun String.safeFileComponent(name: String): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', '-')
            .also { require(it.isNotBlank()) { "$name cannot form a safe file name" } }
}
