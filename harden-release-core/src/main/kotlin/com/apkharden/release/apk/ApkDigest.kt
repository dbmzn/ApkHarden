package com.apkharden.release.apk

import java.io.File
import java.security.MessageDigest

object ApkDigest {
    fun sha256(apk: File): String {
        require(apk.isFile) { "APK not found: $apk" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
