package com.apkharden.packager.signing

import java.util.UUID
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.OS

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "APK_HARDEN_KEYCHAIN_TEST", matches = "true")
class MacKeychainTest {
    @Test
    fun `keychain survives new protector instance and rejects ciphertext tampering`() {
        val service = "com.apkharden.verification.${UUID.randomUUID()}"
        val keychain = MacKeychain(service)
        try {
            val plain = "test-only-password-签名".encodeToByteArray()
            val encrypted = keychain.protect(plain)
            assertFalse(encrypted.contentEquals(plain))
            assertArrayEquals(plain, MacKeychain(service).unprotect(encrypted))
            assertFalse(encrypted.contentEquals(keychain.protect(plain)))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            assertThrows(AEADBadTagException::class.java) { keychain.unprotect(encrypted) }
        } finally { keychain.deleteKey() }
    }
}
