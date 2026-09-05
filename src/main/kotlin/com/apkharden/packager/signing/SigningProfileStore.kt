package com.apkharden.packager.signing

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.prefs.Preferences

data class SigningProfile(
    val keystorePath: String,
    val alias: String,
    val storePassword: String,
    val keyPassword: String,
    val certificateSha256: String,
)

internal object WindowsDpapi {
    fun protect(value: ByteArray): ByteArray = Crypt32Util.cryptProtectData(value)
    fun unprotect(value: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(value)
}

/**
 * Stores the reusable signing profile for the current OS user.
 * Passwords are encrypted with the OS secret protector before they enter Preferences.
 */
class SigningProfileStore internal constructor(
    private val preferences: Preferences,
    private val protectSecret: (ByteArray) -> ByteArray,
    private val unprotectSecret: (ByteArray) -> ByteArray,
) {
    constructor() : this(
        Preferences.userRoot().node(PREFERENCES_NODE),
        PlatformSecrets::protect,
        PlatformSecrets::unprotect,
    )

    fun load(): SigningProfile? {
        val keystorePath = preferences.get(KEYSTORE_PATH, "").trim()
        val alias = preferences.get(ALIAS, "").trim()
        val encryptedStorePassword = preferences.get(STORE_PASSWORD, "")
        val encryptedKeyPassword = preferences.get(KEY_PASSWORD, "")
        if (keystorePath.isEmpty() || alias.isEmpty() ||
            encryptedStorePassword.isEmpty() || encryptedKeyPassword.isEmpty()
        ) return null

        return SigningProfile(
            keystorePath = keystorePath,
            alias = alias,
            storePassword = decrypt(encryptedStorePassword),
            keyPassword = decrypt(encryptedKeyPassword),
            certificateSha256 = preferences.get(CERTIFICATE_SHA256, ""),
        )
    }

    fun save(profile: SigningProfile) {
        // Finish both encryption operations before changing an existing configuration.
        val storePassword = encrypt(profile.storePassword)
        val keyPassword = encrypt(profile.keyPassword)
        preferences.put(KEYSTORE_PATH, profile.keystorePath)
        preferences.put(ALIAS, profile.alias)
        preferences.put(STORE_PASSWORD, storePassword)
        preferences.put(KEY_PASSWORD, keyPassword)
        preferences.put(CERTIFICATE_SHA256, profile.certificateSha256)
        preferences.flush()
    }

    fun clear() {
        preferences.clear()
        preferences.flush()
    }

    private fun encrypt(value: String): String {
        val encrypted = protectSecret(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun decrypt(value: String): String {
        val encrypted = Base64.getDecoder().decode(value)
        return String(unprotectSecret(encrypted), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PREFERENCES_NODE = "/com/apkharden/packager/signing"
        const val KEYSTORE_PATH = "keystorePath"
        const val ALIAS = "alias"
        const val STORE_PASSWORD = "storePassword"
        const val KEY_PASSWORD = "keyPassword"
        const val CERTIFICATE_SHA256 = "certificateSha256"
    }
}
