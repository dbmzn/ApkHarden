package com.apkharden.release.signing

import com.android.apksig.ApkSigner
import com.apkharden.release.apk.ApkSignatureReader
import com.apkharden.release.apk.ZipAlignmentInspector
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.model.KeystoreRequest
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object ReleaseSigner {
    fun sign(input: File, output: File, request: KeystoreRequest) {
        require(input.isFile) { "Candidate APK not found: $input" }
        require(input.canonicalFile != output.canonicalFile) {
            "Input and output APK must differ"
        }
        val outputFile = output.absoluteFile
        val parent = outputFile.parentFile
        require(parent.exists() || parent.mkdirs()) {
            "Unable to create output directory: $parent"
        }
        val before = contentFingerprint(input)
        val loaded = KeystoreReader.load(request)
        val temporary = File.createTempFile("apkharden-release-", ".apk", parent)
        temporary.delete()
        try {
            val signer = ApkSigner.SignerConfig.Builder(
                "RELEASE",
                loaded.privateKey,
                loaded.certificateChain,
            ).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(input)
                .setOutputApk(temporary)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()

            check(before == contentFingerprint(temporary)) {
                "Signing changed non-signature APK entries"
            }
            val signature = ApkSignatureReader.read(temporary)
            check(signature.verified && signature.v1 && signature.v2 && signature.v3) {
                "Signed APK failed V1/V2/V3 verification"
            }
            check(signature.signerSha256 == setOf(loaded.identity.certificateSha256)) {
                "Signed APK certificate differs from keystore"
            }
            check(ZipAlignmentInspector.inspect(temporary).all { it.aligned16k }) {
                "Signing broke 16KB native ZIP alignment"
            }
            moveIntoPlace(temporary, outputFile)
        } finally {
            temporary.delete()
        }
    }

    private fun contentFingerprint(apk: File): Map<String, EntryFingerprint> =
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.name.startsWith("META-INF/") }
                .associate { entry ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        DigestInputStream(input, digest).use { stream ->
                            val buffer = ByteArray(8192)
                            while (stream.read(buffer) != -1) {
                                // DigestInputStream updates the digest while draining the entry.
                            }
                        }
                    }
                    entry.name to EntryFingerprint(
                        method = entry.method,
                        size = entry.size,
                        crc = entry.crc,
                        sha256 = digest.digest().joinToString("") {
                            "%02x".format(it)
                        },
                    )
                }
        }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), REPLACE_EXISTING)
        }
    }

    private data class EntryFingerprint(
        val method: Int,
        val size: Long,
        val crc: Long,
        val sha256: String,
    )
}
