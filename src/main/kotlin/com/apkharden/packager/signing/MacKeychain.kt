package com.apkharden.packager.signing

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The per-user encryption key stays in Keychain, never in Preferences or command arguments. */
internal class MacKeychain(private val service: String = "com.apkharden.packager.signing") {
    private val account = "profile-encryption-key".encodeToByteArray()
    private val serviceBytes = service.encodeToByteArray()
    private val security by lazy { Native.load("Security", Security::class.java) }

    fun protect(plain: ByteArray): ByteArray {
        val key = key(create = true)
        try {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return byteArrayOf(1) + nonce + cipher.doFinal(plain)
        } finally { key.fill(0) }
    }

    fun unprotect(encrypted: ByteArray): ByteArray {
        require(encrypted.size >= 29 && encrypted[0] == 1.toByte()) { "签名密码格式无效，请重新保存配置" }
        val key = key(create = false)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
            return cipher.doFinal(encrypted, 13, encrypted.size - 13)
        } finally { key.fill(0) }
    }

    @Synchronized
    private fun key(create: Boolean): ByteArray {
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes,
            account.size, account, length, data, null)
        if (status == 0) {
            try {
                check(length.value == 32) { "钥匙串中的签名加密密钥长度无效" }
                return data.value.getByteArray(0, length.value)
            } finally { security.SecKeychainItemFreeContent(null, data.value) }
        }
        check(status == -25300 && create) { "无法读取签名加密密钥（macOS Keychain $status），请解锁钥匙串或重新保存配置" }
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val added = security.SecKeychainAddGenericPassword(null, serviceBytes.size, serviceBytes,
                account.size, account, generated.size, generated, null)
            check(added == 0 || added == -25299) { "无法保存签名加密密钥（macOS Keychain $added）" }
        } finally { generated.fill(0) }
        return key(create = false)
    }

    // Used only for isolated verification keys; clearing a profile does not invalidate other profiles.
    internal fun deleteKey() {
        val item = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes,
            account.size, account, null, null, item)
        if (found == -25300) return
        check(found == 0) { "Keychain lookup failed: $found" }
        try { check(security.SecKeychainItemDelete(item.value) == 0) }
        finally { Native.load("CoreFoundation", CoreFoundation::class.java).CFRelease(item.value) }
    }

    private interface Security : Library {
        fun SecKeychainFindGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, passwordLength: IntByReference?, password: PointerByReference?, item: PointerByReference?): Int
        fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, passwordLength: Int, password: ByteArray, item: PointerByReference?): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer?): Int
        fun SecKeychainItemDelete(item: Pointer): Int
    }
    private interface CoreFoundation : Library { fun CFRelease(value: Pointer) }
}

internal object PlatformSecrets {
    private val mac by lazy { MacKeychain() }
    private val os get() = System.getProperty("os.name")
    val description: String get() = when {
        os.startsWith("Mac", true) -> "签名密码使用 AES-GCM 加密，加密密钥保存在当前用户的 macOS 钥匙串中；不会以明文写入配置。"
        os.startsWith("Windows", true) -> "签名密码使用 Windows DPAPI 加密，只能由当前 Windows 用户解密；不会以明文写入配置。"
        else -> "当前系统暂不支持安全保存签名密码。"
    }
    fun protect(value: ByteArray): ByteArray = when {
        os.startsWith("Mac", true) -> mac.protect(value)
        os.startsWith("Windows", true) -> WindowsDpapi.protect(value)
        else -> error("当前系统暂不支持安全保存签名密码")
    }
    fun unprotect(value: ByteArray): ByteArray = when {
        os.startsWith("Mac", true) -> mac.unprotect(value)
        os.startsWith("Windows", true) -> WindowsDpapi.unprotect(value)
        else -> error("当前系统暂不支持安全读取签名密码")
    }
}
