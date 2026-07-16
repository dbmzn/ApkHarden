package com.apkharden.packager.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Upload-only encrypted-DEX shell pipeline.
 *
 * Business DEX entries are compressed, authenticated-encrypted and replaced by a minimal shell.
 * API 29+ loads plaintext DEX from memory through the public AppComponentFactory class-loader hook;
 * older Android versions use an app-private, read-only compatibility cache.
 */
object ProductionHardenPipeline {
    private const val LEGACY_PROXY_APPLICATION = "com.apkharden.shell.ProxyApplication"

    private fun loadResource(path: String): ByteArray =
        (javaClass.getResourceAsStream(path)
            ?: error("Shell resource $path is missing — run scripts/build-shell.ps1"))
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
        val identity = ApkInspector.inspect(input)
        require(identity.minSdk >= 23) { "APK minSdk ${identity.minSdk} is below supported API 23" }
        require(identity.splitName == null) { "Split APK is not supported: ${identity.splitName}" }
        require(!identity.testOnly) { "testOnly APK cannot be hardened for release" }
        require(!identity.debuggable) { "Debuggable APK cannot use the production encrypted shell" }
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
        val source = ApkReader(input).use { reader ->
            val dexes = reader.dexNames()
            require(dexes.isNotEmpty()) { "APK contains no classes.dex" }
            val manifest = reader.manifestBytes()
            require(ManifestPatcher.readApplicationClass(manifest) != LEGACY_PROXY_APPLICATION) {
                "Legacy whole-DEX hardened APK cannot be hardened again; use the original APK"
            }
            SourceApk(
                manifest = manifest,
                dexes = dexes.map(reader::read),
            )
        }

        log("压缩并使用 AES-256-GCM 加密 ${source.dexes.size} 个业务 DEX…")
        val payloadKey = DexPayloadCodec.newKey()
        val encryptedDexes = try {
            source.dexes.map { dex -> DexPayloadCodec.encrypt(dex, payloadKey) }
        } catch (error: Throwable) {
            payloadKey.fill(0)
            throw error
        }
        val patched = ManifestPatcher.patchEncryptedShell(
            source.manifest,
            certificateSha256,
            encryptedDexes.size,
        )
        val shellAbis = selectShellAbis(identity.abis)
        val nativeLibraries = try {
            shellAbis.associateWith { abi ->
                NativeLibraryPatcher.injectPayloadKey(
                    loadResource("/shell-libs/$abi/${Constants.SHELL_LIBRARY_NAME}"),
                    payloadKey,
                )
            }
        } finally {
            payloadKey.fill(0)
        }
        val payloadMetadata = ShellPayloadMetadata(
            payloadId = UUID.randomUUID().toString().replace("-", ""),
            dexCount = encryptedDexes.size,
            originalApplication = patched.originalApplication,
            originalComponentFactory = patched.originalComponentFactory,
            dexSizes = source.dexes.map(ByteArray::size),
            dexSha256 = source.dexes.map(::sha256),
        ).encode()

        log("注入壳 DEX、Native 解密库和多进程运行时守卫…")
        val unsigned = File.createTempFile(".apkharden-unsigned-", ".apk", outputDirectory)
        val signed = File.createTempFile(".apkharden-signed-", ".apk", outputDirectory)
        signed.delete()
        try {
            ApkRepackager.wrapEncryptedDex(
                input = input,
                output = unsigned,
                patchedManifest = patched.bytes,
                shellDex = loadResource("/shell.dex"),
                encryptedDexes = encryptedDexes,
                metadata = payloadMetadata,
                nativeLibraries = nativeLibraries,
            )
            log("执行 16KB 对齐并使用 V1+V2+V3 正式签名…")
            ApkSignerWrapper.sign(unsigned, signed, credentials)
            check(ApkSignerWrapper.verify(signed)) { "Output APK failed signature verification" }
            val signer = ApkInspector.signature(signed)
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
        ApkReader(outputFile).use { reader ->
            check(reader.dexNames() == listOf("classes.dex")) {
                "Output APK still exposes business DEX entries"
            }
        }
        ZipFile(outputFile).use { apk ->
            encryptedDexes.indices.forEach { index ->
                val entry = Constants.encryptedDexEntry(index)
                check(apk.getEntry(entry) != null) { "Output APK is missing $entry" }
            }
            shellAbis.forEach { abi ->
                val entry = "lib/$abi/${Constants.SHELL_LIBRARY_NAME}"
                check(apk.getEntry(entry) != null) { "Output APK is missing $entry" }
            }
        }

        val reportFile = File(
            outputDirectory,
            "${outputFile.nameWithoutExtension}-report.json",
        )
        val report = ProductionHardenReport(
            inputFile = input.absolutePath,
            outputFile = outputFile.absolutePath,
            inputSha256 = ApkInspector.sha256(input),
            outputSha256 = ApkInspector.sha256(outputFile),
            packageName = identity.packageName,
            versionCode = identity.versionCode,
            minSdk = identity.minSdk,
            targetSdk = identity.targetSdk,
            abis = identity.abis,
            certificateSha256 = certificateSha256,
            originalApplication = patched.originalApplication,
            businessDexCount = source.dexes.size,
            businessDexBytes = source.dexes.sumOf { it.size.toLong() },
            encryptedPayloadBytes = encryptedDexes.sumOf { it.size.toLong() },
            encryptedDexEntries = encryptedDexes.indices.map(Constants::encryptedDexEntry),
            shellDexEntry = "classes.dex",
            shellAbis = shellAbis,
            packageSizeDelta = outputFile.length() - input.length(),
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

    private fun selectShellAbis(inputAbis: Set<String>): Set<String> {
        if (inputAbis.isEmpty()) return SUPPORTED_SHELL_ABIS
        val unsupported = inputAbis - SUPPORTED_SHELL_ABIS
        require(unsupported.isEmpty()) {
            "Unsupported native ABI for encrypted shell: ${unsupported.sorted().joinToString()}"
        }
        return inputAbis
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private val REPORT_JSON = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val SUPPORTED_SHELL_ABIS = linkedSetOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86",
    )

    private data class SourceApk(
        val manifest: ByteArray,
        val dexes: List<ByteArray>,
    )
}

data class ProductionHardenResult(
    val apk: File,
    val reportFile: File,
    val report: ProductionHardenReport,
)

@Serializable
data class ProductionHardenReport(
    val schemaVersion: Int = 1,
    val mode: String = "ENCRYPTED_DEX_SHELL",
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
    val originalApplication: String,
    val businessDexCount: Int,
    val businessDexBytes: Long,
    val encryptedPayloadBytes: Long,
    val encryptedDexEntries: List<String>,
    val shellDexEntry: String,
    val shellAbis: Set<String>,
    val packageSizeDelta: Long,
    val guardedProcesses: Set<String>,
    val protections: Set<String> = setOf(
        "AES_256_GCM_DEX_ENCRYPTION",
        "NATIVE_PAYLOAD_DECRYPTION",
        "IN_MEMORY_DEX_LOADING_API_29_PLUS",
        "SIGNATURE_VERIFICATION",
        "ANTI_DEBUG",
        "STATIC_SHELL_INJECTION",
    ),
)
