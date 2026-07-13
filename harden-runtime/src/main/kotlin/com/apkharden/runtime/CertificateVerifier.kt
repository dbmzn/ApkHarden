package com.apkharden.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object CertificateVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, config: HardenConfig): Boolean {
        if (context.packageName != config.applicationId) return false
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            manager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        return matchesSigners(signers, config.certificateSha256)
    }

    internal fun matchesSigners(
        signers: List<ByteArray>,
        expectedSha256: String,
    ): Boolean = signers.size == 1 &&
        sha256(signers.single()).equals(expectedSha256, ignoreCase = true)

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
