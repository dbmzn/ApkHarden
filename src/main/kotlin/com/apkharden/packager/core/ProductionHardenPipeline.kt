package com.apkharden.packager.core

import com.apkharden.release.apk.ApkDigest
import com.apkharden.release.apk.ApkIdentityReader
import com.apkharden.release.apk.ApkSignatureReader
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Upload-only hardening pipeline safe for modern Android runtime policy.
 *
 * Unlike the legacy whole-dex shell, this pipeline leaves all business dex files inside the APK
 * and appends a normal guard dex. It never extracts writable dex at runtime or uses hidden APIs.
 */
object ProductionHardenPipeline {
    private const val LEGACY_PROXY_APPLICATION = "com.apkharden.shell.ProxyApplication"

    private fun loadGuardDex(): ByteArray =
        (javaClass.getResourceAsStream("/guard.dex")
            ?: error("guard.dex missing from resources — run scripts/build-guard.ps1"))
            .use { it.readBytes() }

    fun harden(
        input: File,
        output: File,
        keystore: File,
        storePass: String,
        alias: String,
        keyPass: String,
        log: (String) -> Unit = {},
    ): ProductionHardenResult {
        require(input.isFile) { "Input APK not found: $input" }
        require(keystore.isFile) { "Keystore not found: $keystore" }
        require(input.canonicalFile != output.canonicalFile) { "Output APK must differ from input APK" }
        val outputFile = output.absoluteFile
        val outputDirectory = outputFile.parentFile
        require(outputDirectory.exists() || outputDirectory.mkdirs()) {
            "Unable to create output directory: $outputDirectory"
        }

        log("读取正式签名证书…")
        val credentials = KeystoreUtil.load(keystore, storePass, alias, keyPass)
        val certificateSha256 = KeystoreUtil.expectedSigHash(credentials)

        log("读取 APK 并执行兼容性预检…")
        val identity = ApkIdentityReader.read(input)
        require(identity.minSdk >= 23) { "APK minSdk ${identity.minSdk} is below supported API 23" }
        require(identity.splitName == null) { "Split APK is not supported: ${identity.splitName}" }
        require(!identity.testOnly) { "testOnly APK cannot be hardened for release" }
        require(!identity.debuggable) { "Debuggable APK cannot use the production static guard" }
        if (identity.signature.verified) {
            require(identity.signature.signerCount == 1) {
                "Input APK must have exactly one signer"
            }
            require(!identity.signature.hasSigningLineage) {
                "Signing lineage is not supported by the upload-only hardening flow"
            }
            require(identity.signature.signerSha256.singleOrNull() == certificateSha256) {
                "Input APK signer differs from the selected keystore; use the original release keystore"
            }
        }
        val patchedManifest = ApkReader(input).use { reader ->
            val dexes = reader.dexNames()
            require(dexes.isNotEmpty()) { "APK contains no classes.dex" }
            val manifest = reader.manifestBytes()
            HardenLinter.lint(
                reader.entryNames(),
                manifest,
                dexes.map(reader::read),
                log,
            )
            require(ManifestPatcher.readApplicationClass(manifest) != LEGACY_PROXY_APPLICATION) {
                "Legacy whole-DEX hardened APK cannot be hardened again; use the original APK"
            }
            ManifestPatcher.patchGuard(manifest, certificateSha256)
        }

        log("注入静态运行时保护（不释放或动态加载 DEX）…")
        val unsigned = File.createTempFile(".apkharden-unsigned-", ".apk", outputDirectory)
        val signed = File.createTempFile(".apkharden-signed-", ".apk", outputDirectory)
        signed.delete()
        var guardDexEntry = ""
        try {
            guardDexEntry = ApkRepackager.injectGuard(
                input = input,
                output = unsigned,
                patchedManifest = patchedManifest,
                guardDex = loadGuardDex(),
            )
            log("执行 16KB 对齐并使用 V1+V2+V3 正式签名…")
            ApkSignerWrapper.sign(unsigned, signed, credentials)
            check(ApkSignerWrapper.verify(signed)) { "Output APK failed signature verification" }
            val signer = ApkSignatureReader.read(signed)
            check(signer.signerSha256.singleOrNull() == certificateSha256) {
                "Output APK signer does not match the selected keystore"
            }
            replaceFile(signed, outputFile)
        } finally {
            unsigned.delete()
            signed.delete()
        }

        val guardedProcesses = ZipFile(outputFile).use { apk ->
            val manifestEntry = requireNotNull(apk.getEntry("AndroidManifest.xml"))
            val manifest = apk.getInputStream(manifestEntry).use { it.readBytes() }
            ManifestPatcher.guardProcesses(manifest).also { processes ->
                check(processes.isNotEmpty()) {
                    "Output APK is missing the static guard provider"
                }
            }
        }
        ZipFile(outputFile).use { apk ->
            check(apk.getEntry(guardDexEntry) != null) {
                "Output APK is missing the injected guard dex"
            }
        }

        val reportFile = File(
            outputDirectory,
            "${outputFile.nameWithoutExtension}-report.json",
        )
        val report = ProductionHardenReport(
            inputFile = input.absolutePath,
            outputFile = outputFile.absolutePath,
            inputSha256 = ApkDigest.sha256(input),
            outputSha256 = ApkDigest.sha256(outputFile),
            packageName = identity.packageName,
            versionCode = identity.versionCode,
            minSdk = identity.minSdk,
            targetSdk = identity.targetSdk,
            abis = identity.abis,
            certificateSha256 = certificateSha256,
            guardDexEntry = guardDexEntry,
            guardedProcesses = guardedProcesses,
        )
        writeReport(report, reportFile)
        log("报告 → ${reportFile.absolutePath}")
        log("完成 → ${outputFile.absolutePath}")
        return ProductionHardenResult(outputFile, reportFile, report)
    }

    private fun writeReport(report: ProductionHardenReport, output: File) {
        val staging = File.createTempFile(".apkharden-report-", ".json", output.parentFile)
        try {
            staging.writeText(REPORT_JSON.encodeToString(report) + "\n")
            replaceFile(staging, output)
        } finally {
            staging.delete()
        }
    }

    private fun replaceFile(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), REPLACE_EXISTING)
        }
    }

    private val REPORT_JSON = Json {
        prettyPrint = true
        encodeDefaults = true
    }
}

data class ProductionHardenResult(
    val apk: File,
    val reportFile: File,
    val report: ProductionHardenReport,
)

@Serializable
data class ProductionHardenReport(
    val schemaVersion: Int = 1,
    val mode: String = "STATIC_GUARD",
    val inputFile: String,
    val outputFile: String,
    val inputSha256: String,
    val outputSha256: String,
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: Set<String>,
    val certificateSha256: String,
    val guardDexEntry: String,
    val guardedProcesses: Set<String>,
    val protections: Set<String> = setOf(
        "SIGNATURE_VERIFICATION",
        "ANTI_DEBUG",
        "STATIC_DEX_INJECTION",
    ),
)
