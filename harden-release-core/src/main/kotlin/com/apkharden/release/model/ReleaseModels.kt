package com.apkharden.release.model

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
enum class FindingLevel {
    BLOCKER,
    REQUIRES_APPROVAL,
    WARNING,
    INFO,
}

@Serializable
enum class ReleaseStatus {
    NOT_QUALIFIED,
    STATIC_VERIFIED,
    DEVICE_VERIFIED,
    RELEASE_QUALIFIED,
}

@Serializable
data class ReleaseFinding(
    val code: String,
    val level: FindingLevel,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ApkSignatureInfo(
    val verified: Boolean,
    val signerSha256: Set<String>,
    val signerCount: Int,
    val hasSigningLineage: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v31: Boolean,
    val errors: List<String> = emptyList(),
)

@Serializable
data class ApkIdentity(
    val fileName: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val testOnly: Boolean,
    val splitName: String?,
    val extractNativeLibs: Boolean?,
    val requiredFeatures: Set<String>,
    val abis: Set<String>,
    val signature: ApkSignatureInfo,
)

@Serializable
data class KeystoreIdentity(
    val alias: String,
    val certificateSha256: String,
    val certificateSubject: String,
)

data class KeystoreRequest(
    val file: File,
    val storePassword: CharArray,
    val alias: String,
    val keyPassword: CharArray,
)

data class ReleaseRequest(
    val onlineApk: File,
    val candidateApk: File,
    /** Optional build-time metadata supplied by the Gradle plugin for stronger cross-checks. */
    val metadataFile: File? = null,
    val keystore: KeystoreRequest,
    val approvedFindingCodes: Set<String> = emptySet(),
)

@Serializable
class ReleaseAssessment(
    val findings: List<ReleaseFinding>,
    val approvedFindingCodes: Set<String> = emptySet(),
    val online: ApkIdentity? = null,
    val candidate: ApkIdentity? = null,
    val keystore: KeystoreIdentity? = null,
    val status: ReleaseStatus = calculateStatus(findings, approvedFindingCodes),
) {
    companion object {
        private fun calculateStatus(
            findings: List<ReleaseFinding>,
            approved: Set<String>,
        ): ReleaseStatus = when {
            findings.any { it.level == FindingLevel.BLOCKER } ->
                ReleaseStatus.NOT_QUALIFIED

            findings.any {
                it.level == FindingLevel.REQUIRES_APPROVAL && it.code !in approved
            } -> ReleaseStatus.NOT_QUALIFIED

            else -> ReleaseStatus.STATIC_VERIFIED
        }
    }
}
