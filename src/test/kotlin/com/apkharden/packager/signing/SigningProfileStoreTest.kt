package com.apkharden.packager.signing

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.UUID
import java.util.prefs.Preferences

class SigningProfileStoreTest {
    private val preferences = Preferences.userRoot().node(
        "/com/apkharden/packager/test/${UUID.randomUUID()}",
    )
    private val xor: (ByteArray) -> ByteArray = { bytes ->
        bytes.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    private val store = SigningProfileStore(preferences, xor, xor)

    @AfterEach
    fun cleanUp() {
        preferences.removeNode()
        preferences.flush()
    }

    @Test
    fun `saves and loads reusable signing profile without plaintext passwords`() {
        val expected = SigningProfile(
            keystorePath = "C:\\signing\\release.jks",
            alias = "release",
            storePassword = "store-secret",
            keyPassword = "key-secret",
            certificateSha256 = "ab".repeat(32),
        )

        store.save(expected)

        assertEquals(expected, store.load())
        assertFalse(preferences.get("storePassword", "").contains(expected.storePassword))
        assertFalse(preferences.get("keyPassword", "").contains(expected.keyPassword))
    }

    @Test
    fun `clear removes saved profile`() {
        store.save(
            SigningProfile("C:\\release.jks", "release", "123456", "123456", "cd".repeat(32)),
        )

        store.clear()

        assertNull(store.load())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows DPAPI round trips secret for current user`() {
        val plain = "only-current-windows-user".encodeToByteArray()

        val encrypted = WindowsDpapi.protect(plain)

        assertFalse(encrypted.contentEquals(plain))
        assertArrayEquals(plain, WindowsDpapi.unprotect(encrypted))
    }
}
