package com.apkharden.release.apk

import com.android.apksig.ApkVerifier
import com.apkharden.release.crypto.CertificateDigests
import com.apkharden.release.model.ApkSignatureInfo
import java.io.File

object ApkSignatureReader {
    fun read(apk: File): ApkSignatureInfo {
        require(apk.isFile) { "APK not found: $apk" }
        val result = ApkVerifier.Builder(apk).build().verify()
        val certificates = result.signerCertificates
        return ApkSignatureInfo(
            verified = result.isVerified,
            signerSha256 = certificates.map(CertificateDigests::sha256).toSet(),
            signerCount = certificates.size,
            hasSigningLineage = result.signingCertificateLineage != null,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            v31 = result.isVerifiedUsingV31Scheme,
            errors = result.allErrors.map { it.toString() },
        )
    }
}
