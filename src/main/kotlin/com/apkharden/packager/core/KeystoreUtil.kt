package com.apkharden.packager.core

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

class KeystoreCredentials(
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
)

object KeystoreUtil {
    fun load(file: File, storePass: String, alias: String, keyPass: String): KeystoreCredentials {
        // JKS first, fall back to PKCS12 for .jks files that are actually PKCS12.
        val ks = try {
            KeyStore.getInstance("JKS").also { it.load(file.inputStream(), storePass.toCharArray()) }
        } catch (e: Exception) {
            KeyStore.getInstance("PKCS12").also { it.load(file.inputStream(), storePass.toCharArray()) }
        }
        val key = ks.getKey(alias, keyPass.toCharArray()) as? PrivateKey
            ?: throw IllegalArgumentException("No private key for alias '$alias'")
        val chain = (ks.getCertificateChain(alias)
            ?: throw IllegalArgumentException("No certificate chain for alias '$alias'"))
            .map { it as X509Certificate }
        return KeystoreCredentials(key, chain)
    }

    /** SHA-256 of the leaf signing certificate DER (matches PackageManager Signature bytes). */
    fun expectedSigHash(creds: KeystoreCredentials): String {
        val der = creds.certificates.first().encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
