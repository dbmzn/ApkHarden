package com.apkharden.release.gate

import com.apkharden.release.metadata.HardenMetadata
import com.apkharden.release.model.ApkIdentity
import com.apkharden.release.model.FindingLevel
import com.apkharden.release.model.KeystoreIdentity
import com.apkharden.release.model.ReleaseFinding

object ReleaseGateEvaluator {
    fun evaluate(
        online: ApkIdentity,
        candidate: ApkIdentity,
        metadata: HardenMetadata?,
        keystore: KeystoreIdentity,
    ): List<ReleaseFinding> = buildList {
        blocker(
            !online.signature.verified,
            "ONLINE_APK_UNSIGNED",
            "Online APK signature is not verified",
        )
        blocker(
            online.signature.signerCount != 1,
            "ONLINE_SIGNER_COUNT_UNSUPPORTED",
            "Online APK must have exactly one signer",
        )
        blocker(
            online.signature.hasSigningLineage,
            "SIGNING_LINEAGE_UNSUPPORTED",
            "Online APK signing lineage is unsupported",
        )
        blocker(
            online.packageName != candidate.packageName,
            "PACKAGE_MISMATCH",
            "Online and candidate package names differ",
        )
        blocker(
            candidate.versionCode <= online.versionCode,
            "VERSION_NOT_INCREMENTED",
            "Candidate versionCode must be greater than online versionCode",
        )
        blocker(
            candidate.debuggable,
            "DEBUGGABLE_CANDIDATE",
            "Formal candidate is debuggable",
        )
        blocker(
            candidate.testOnly,
            "TEST_ONLY_CANDIDATE",
            "Formal candidate is testOnly",
        )
        blocker(
            candidate.minSdk < 23,
            "MIN_SDK_UNSUPPORTED",
            "Candidate minSdk is below 23",
        )
        blocker(
            candidate.splitName != null,
            "SPLIT_APK_UNSUPPORTED",
            "Candidate is a split APK",
        )
        blocker(
            online.splitName != null,
            "ONLINE_SPLIT_APK_UNSUPPORTED",
            "Online baseline is a split APK",
        )

        val onlineCertificate = online.signature.signerSha256.singleOrNull()
        blocker(
            onlineCertificate != keystore.certificateSha256,
            "ONLINE_CERT_KEYSTORE_MISMATCH",
            "Online APK and keystore certificates differ",
        )

        if (candidate.signature.verified) {
            blocker(
                candidate.signature.signerCount != 1,
                "CANDIDATE_SIGNER_COUNT_UNSUPPORTED",
                "Signed candidate must have exactly one signer",
            )
            blocker(
                candidate.signature.hasSigningLineage,
                "CANDIDATE_SIGNING_LINEAGE_UNSUPPORTED",
                "Candidate signing lineage is unsupported",
            )
            blocker(
                candidate.signature.signerSha256.singleOrNull() !=
                    keystore.certificateSha256,
                "CANDIDATE_CERT_KEYSTORE_MISMATCH",
                "Candidate and keystore certificates differ",
            )
        }

        if (metadata != null) {
            blocker(
                metadata.applicationId != candidate.packageName,
                "METADATA_PACKAGE_MISMATCH",
                "Metadata applicationId differs from candidate",
            )
            blocker(
                metadata.versionCode != candidate.versionCode,
                "METADATA_VERSION_MISMATCH",
                "Metadata versionCode differs from candidate",
            )
            blocker(
                metadata.minSdk != candidate.minSdk,
                "METADATA_MIN_SDK_MISMATCH",
                "Metadata minSdk differs from candidate",
            )
            blocker(
                metadata.targetSdk != candidate.targetSdk,
                "METADATA_TARGET_SDK_MISMATCH",
                "Metadata targetSdk differs from candidate",
            )
            blocker(
                metadata.debuggable != candidate.debuggable,
                "METADATA_DEBUGGABLE_MISMATCH",
                "Metadata debuggable differs from candidate",
            )
            blocker(
                metadata.abis != candidate.abis,
                "METADATA_ABI_MISMATCH",
                "Metadata ABI set differs from candidate",
            )
            blocker(
                metadata.expectedCertificateSha256 != keystore.certificateSha256,
                "METADATA_CERT_KEYSTORE_MISMATCH",
                "Metadata certificate differs from keystore",
            )
        }

        approval(
            candidate.minSdk > online.minSdk,
            "MIN_SDK_INCREASED",
            "Candidate increases minSdk",
        )
        approval(
            !candidate.abis.containsAll(online.abis),
            "ABI_SUPPORT_REDUCED",
            "Candidate removes an ABI supported by the online APK",
        )
        approval(
            (candidate.requiredFeatures - online.requiredFeatures).isNotEmpty(),
            "REQUIRED_FEATURE_ADDED",
            "Candidate adds required hardware features",
        )
    }

    private fun MutableList<ReleaseFinding>.blocker(
        condition: Boolean,
        code: String,
        message: String,
    ) {
        if (condition) {
            add(ReleaseFinding(code, FindingLevel.BLOCKER, message))
        }
    }

    private fun MutableList<ReleaseFinding>.approval(
        condition: Boolean,
        code: String,
        message: String,
    ) {
        if (condition) {
            add(ReleaseFinding(code, FindingLevel.REQUIRES_APPROVAL, message))
        }
    }
}
