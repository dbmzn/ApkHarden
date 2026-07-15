package com.apkharden.packager.core

import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class ApkSignatureInfo(
    val verified: Boolean,
    val signerSha256: Set<String>,
    val signerCount: Int,
    val hasSigningLineage: Boolean,
)

internal data class ApkInfo(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val testOnly: Boolean,
    val splitName: String?,
    val abis: Set<String>,
    val signature: ApkSignatureInfo,
)

internal object ApkInspector {
    private const val ATTR_TEST_ONLY = 0x01010272
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576
    private val nativeEntry = Regex("lib/([^/]+)/[^/]+\\.so")

    fun inspect(apk: File): ApkInfo {
        require(apk.isFile) { "APK not found: $apk" }
        return ZipFile(apk).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalArgumentException("APK has no AndroidManifest.xml")
            val manifest = zip.getInputStream(manifestEntry).use(AndroidManifestBlock::load)
            val application = manifest.applicationElement
                ?: throw IllegalArgumentException("Manifest has no application")
            ApkInfo(
                packageName = requireNotNull(manifest.packageName) { "Manifest package is missing" },
                versionCode = longVersionCode(manifest),
                minSdk = manifest.minSdkVersion ?: 1,
                targetSdk = manifest.targetSdkVersion ?: 1,
                debuggable = manifest.isDebuggable,
                testOnly = application.searchAttributeByResourceId(ATTR_TEST_ONLY)?.valueAsBoolean == true,
                splitName = manifest.split,
                abis = zip.entries().asSequence()
                    .mapNotNull { nativeEntry.matchEntire(it.name)?.groupValues?.get(1) }
                    .toSortedSet(),
                signature = signature(apk),
            )
        }
    }

    fun signature(apk: File): ApkSignatureInfo {
        require(apk.isFile) { "APK not found: $apk" }
        val result = ApkVerifier.Builder(apk).build().verify()
        val certificates = result.signerCertificates
        return ApkSignatureInfo(
            verified = result.isVerified,
            signerSha256 = certificates.map { certificateSha256(it.encoded) }.toSet(),
            signerCount = certificates.size,
            hasSigningLineage = result.signingCertificateLineage != null,
        )
    }

    fun sha256(file: File): String {
        require(file.isFile) { "File not found: $file" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun longVersionCode(manifest: AndroidManifestBlock): Long {
        val low = requireNotNull(manifest.versionCode) { "Manifest versionCode is missing" }
            .toLong() and 0xffffffffL
        val major = manifest.manifestElement
            .searchAttributeByResourceId(ATTR_VERSION_CODE_MAJOR)
            ?.data
            ?.toLong()
            ?.and(0xffffffffL)
            ?: 0L
        return (major shl 32) or low
    }

    private fun certificateSha256(encoded: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
