package com.apkharden.release.crypto

import java.security.MessageDigest
import java.security.cert.X509Certificate

object CertificateDigests {
    fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
}
