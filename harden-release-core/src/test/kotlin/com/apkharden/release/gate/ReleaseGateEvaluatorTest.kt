package com.apkharden.release.gate

import com.apkharden.release.metadata.HardenMetadata
import com.apkharden.release.model.ApkIdentity
import com.apkharden.release.model.ApkSignatureInfo
import com.apkharden.release.model.FindingLevel
import com.apkharden.release.model.KeystoreIdentity
import com.apkharden.release.model.ReleaseFinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseGateEvaluatorTest {
    private val certA = "a".repeat(64)
    private val certB = "b".repeat(64)
    private val certC = "c".repeat(64)

    @Test
    fun `valid direct-signature update has no findings`() {
        val findings = ReleaseGateEvaluator.evaluate(
            online = apk("com.example.app", 10),
            candidate = apk("com.example.app", 11),
            metadata = metadata("com.example.app", 11),
            keystore = KeystoreIdentity("release", certA, "CN=Release"),
        )

        assertEquals(emptyList<ReleaseFinding>(), findings)
    }

    @Test
    fun `identity version build flags and split are blockers`() {
        val findings = ReleaseGateEvaluator.evaluate(
            online = apk("com.example.old", 10),
            candidate = apk(
                packageName = "com.example.new",
                version = 10,
                signer = certB,
                debuggable = true,
                testOnly = true,
                minSdk = 22,
                split = "config.arm64_v8a",
            ),
            metadata = metadata("com.example.new", 10, certB),
            keystore = KeystoreIdentity("release", certC, "CN=Wrong"),
        )

        val codes = findings.map { it.code }.toSet()
        setOf(
            "PACKAGE_MISMATCH",
            "VERSION_NOT_INCREMENTED",
            "ONLINE_CERT_KEYSTORE_MISMATCH",
            "CANDIDATE_CERT_KEYSTORE_MISMATCH",
            "DEBUGGABLE_CANDIDATE",
            "TEST_ONLY_CANDIDATE",
            "MIN_SDK_UNSUPPORTED",
            "SPLIT_APK_UNSUPPORTED",
            "METADATA_MIN_SDK_MISMATCH",
        ).forEach { assertTrue(it in codes, "missing $it") }
    }

    @Test
    fun `support reductions require explicit approval`() {
        val findings = ReleaseGateEvaluator.evaluate(
            online = apk(
                "com.example.app",
                10,
                minSdk = 23,
                abis = setOf("armeabi-v7a", "arm64-v8a"),
            ),
            candidate = apk(
                "com.example.app",
                11,
                minSdk = 26,
                abis = setOf("arm64-v8a"),
                features = setOf("android.hardware.camera"),
            ),
            metadata = metadata(
                "com.example.app",
                11,
                minSdk = 26,
                abis = setOf("arm64-v8a"),
            ),
            keystore = KeystoreIdentity("release", certA, "CN=Release"),
        )

        assertEquals(
            setOf(
                "MIN_SDK_INCREASED",
                "ABI_SUPPORT_REDUCED",
                "REQUIRED_FEATURE_ADDED",
            ),
            findings.filter { it.level == FindingLevel.REQUIRES_APPROVAL }
                .map { it.code }
                .toSet(),
        )
    }


    @Test
    fun `signer topology and metadata mismatches have stable blocker codes`() {
        val online = apk("com.example.app", 10).copy(
            signature = signature().copy(
                signerCount = 2,
                hasSigningLineage = true,
            ),
            splitName = "feature.dynamic",
        )
        val candidate = apk("com.example.app", 11).copy(
            signature = signature().copy(
                signerCount = 2,
                hasSigningLineage = true,
            )
        )
        val metadata = metadata("wrong.package", 12, certB).copy(
            targetSdk = 35,
            debuggable = true,
            abis = setOf("armeabi-v7a"),
        )

        val codes = ReleaseGateEvaluator.evaluate(
            online,
            candidate,
            metadata,
            KeystoreIdentity("release", certA, "CN=Release"),
        ).map { it.code }.toSet()

        setOf(
            "ONLINE_SIGNER_COUNT_UNSUPPORTED",
            "SIGNING_LINEAGE_UNSUPPORTED",
            "ONLINE_SPLIT_APK_UNSUPPORTED",
            "CANDIDATE_SIGNER_COUNT_UNSUPPORTED",
            "CANDIDATE_SIGNING_LINEAGE_UNSUPPORTED",
            "METADATA_PACKAGE_MISMATCH",
            "METADATA_VERSION_MISMATCH",
            "METADATA_TARGET_SDK_MISMATCH",
            "METADATA_DEBUGGABLE_MISMATCH",
            "METADATA_ABI_MISMATCH",
            "METADATA_CERT_KEYSTORE_MISMATCH",
        ).forEach { assertTrue(it in codes, "missing $it") }
    }    private fun signature(digest: String = certA) = ApkSignatureInfo(
        verified = true,
        signerSha256 = setOf(digest),
        signerCount = 1,
        hasSigningLineage = false,
        v1 = true,
        v2 = true,
        v3 = true,
        v31 = false,
    )

    private fun apk(
        packageName: String,
        version: Long,
        signer: String = certA,
        debuggable: Boolean = false,
        testOnly: Boolean = false,
        minSdk: Int = 23,
        split: String? = null,
        abis: Set<String> = setOf("arm64-v8a"),
        features: Set<String> = emptySet(),
    ) = ApkIdentity(
        fileName = "$packageName.apk",
        packageName = packageName,
        versionCode = version,
        versionName = null,
        minSdk = minSdk,
        targetSdk = 36,
        debuggable = debuggable,
        testOnly = testOnly,
        splitName = split,
        extractNativeLibs = false,
        requiredFeatures = features,
        abis = abis,
        signature = signature(signer),
    )

    private fun metadata(
        packageName: String,
        version: Long,
        cert: String = certA,
        minSdk: Int = 23,
        abis: Set<String> = setOf("arm64-v8a"),
    ) = HardenMetadata(
        schemaVersion = 1,
        pluginVersion = "1.0.0",
        runtimeVersion = "1.0.0",
        variantName = "product_64",
        applicationId = packageName,
        versionCode = version,
        minSdk = minSdk,
        targetSdk = 36,
        debuggable = false,
        r8Enabled = false,
        abis = abis,
        expectedCertificateSha256 = cert,
        buildId = "build-1",
    )
}
