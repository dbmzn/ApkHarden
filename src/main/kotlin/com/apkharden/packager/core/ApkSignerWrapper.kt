package com.apkharden.packager.core

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File

object ApkSignerWrapper {

    fun sign(input: File, output: File, creds: KeystoreCredentials) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "CERT",
            creds.privateKey,
            creds.certificates,
        ).build()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        signer.sign() // apksig also 4-byte aligns uncompressed entries
    }

    fun verify(apk: File): Boolean {
        val result = ApkVerifier.Builder(apk).build().verify()
        return result.isVerified
    }
}
