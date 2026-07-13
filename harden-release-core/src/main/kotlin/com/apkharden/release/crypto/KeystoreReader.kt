package com.apkharden.release.crypto

import com.apkharden.release.model.KeystoreIdentity
import com.apkharden.release.model.KeystoreRequest
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class LoadedKeystore(
    val identity: KeystoreIdentity,
    val privateKey: PrivateKey,
    val certificateChain: List<X509Certificate>,
)

object KeystoreReader {
    fun load(request: KeystoreRequest): LoadedKeystore {
        require(request.file.isFile) { "Keystore not found: ${request.file}" }
        val store = loadStore(request)
        val key = store.getKey(request.alias, request.keyPassword) as? PrivateKey
            ?: throw IllegalArgumentException("No private key for alias '${request.alias}'")
        val chain = store.getCertificateChain(request.alias)
            ?.map { it as X509Certificate }
            ?: throw IllegalArgumentException("No certificate chain for alias '${request.alias}'")
        val leaf = chain.first()
        return LoadedKeystore(
            identity = KeystoreIdentity(
                alias = request.alias,
                certificateSha256 = CertificateDigests.sha256(leaf),
                certificateSubject = leaf.subjectX500Principal.name,
            ),
            privateKey = key,
            certificateChain = chain,
        )
    }

    private fun loadStore(request: KeystoreRequest): KeyStore {
        var lastError: Exception? = null
        for (type in listOf("JKS", "PKCS12")) {
            try {
                return KeyStore.getInstance(type).also { store ->
                    request.file.inputStream().use {
                        store.load(it, request.storePassword)
                    }
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalArgumentException(
            "Unable to read keystore as JKS or PKCS12",
            lastError,
        )
    }
}
