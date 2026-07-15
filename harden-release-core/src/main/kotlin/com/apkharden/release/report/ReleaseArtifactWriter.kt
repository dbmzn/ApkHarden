package com.apkharden.release.report

import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

object ReleaseArtifactWriter {
    fun writeCertificatePem(certificate: X509Certificate, output: File) {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)
        writeTextAtomically(
            output,
            "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n",
        )
    }

    fun writeChecksums(files: Collection<File>, output: File) {
        require(files.isNotEmpty()) { "No release artifacts supplied for checksums" }
        val uniqueNames = files.map(File::getName)
        require(uniqueNames.toSet().size == uniqueNames.size) {
            "Release artifact names must be unique"
        }
        val content = files.sortedBy(File::getName).joinToString("\n", postfix = "\n") { file ->
            require(file.isFile) { "Release artifact not found: $file" }
            "${file.sha256()}  ${file.name}"
        }
        writeTextAtomically(output, content)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
