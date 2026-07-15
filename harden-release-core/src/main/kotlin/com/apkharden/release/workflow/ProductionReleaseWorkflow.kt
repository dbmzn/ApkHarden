package com.apkharden.release.workflow

import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.gate.ReleaseAnalyzer
import com.apkharden.release.metadata.HardenMetadataReader
import com.apkharden.release.model.ReleaseAssessment
import com.apkharden.release.model.ReleaseRequest
import com.apkharden.release.model.ReleaseStatus
import com.apkharden.release.report.ReleaseArtifactWriter
import com.apkharden.release.report.ReleaseHtmlReportWriter
import com.apkharden.release.report.ReleaseReportWriter
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
    ): ProductionReleaseBundle {
        val preSignAssessment = analyze(request)
        if (preSignAssessment.status != ReleaseStatus.STATIC_VERIFIED) {
            throw ReleaseBlockedException(
                "Release export requires STATIC_VERIFIED pre-sign status",
                preSignAssessment,
            )
        }

        val metadata = HardenMetadataReader.read(request.metadataFile)
        val variant = metadata.variantName.safeFileComponent("variant")
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

            val postSignAssessment = analyze(request.copy(candidateApk = apk))
            if (postSignAssessment.status != ReleaseStatus.STATIC_VERIFIED) {
                throw ReleaseBlockedException(
                    "Signed APK failed post-sign static verification",
                    postSignAssessment,
                )
            }

            val json = File(staging, "release-report.json")
            val html = File(staging, "release-report.html")
            val pem = File(staging, "signer-certificate.pem")
            val checksums = File(staging, "checksums.sha256")
            ReleaseReportWriter.write(postSignAssessment, json)
            ReleaseHtmlReportWriter.write(postSignAssessment, html)
            val certificate = KeystoreReader.load(request.keystore).certificateChain.first()
            ReleaseArtifactWriter.writeCertificatePem(certificate, pem)
            ReleaseArtifactWriter.writeChecksums(listOf(apk, html, json, pem), checksums)

            moveDirectory(staging, destination)
            return ProductionReleaseBundle(
                directory = destination,
                apk = File(destination, apk.name),
                jsonReport = File(destination, json.name),
                htmlReport = File(destination, html.name),
                certificatePem = File(destination, pem.name),
                checksums = File(destination, checksums.name),
                assessment = postSignAssessment,
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

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
