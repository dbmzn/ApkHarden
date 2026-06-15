package com.apkharden.packager.core

object Constants {
    const val PROXY_APPLICATION = "com.apkharden.shell.ProxyApplication"

    const val META_APP_NAME = "com.apkharden.APP_NAME"
    const val META_SIG_HASH = "com.apkharden.SIG_HASH"
    const val META_DEX_COUNT = "com.apkharden.DEX_COUNT"

    const val ENCRYPTED_DEX_DIR = "d"            // assets/d/<index>
    fun encryptedDexEntry(index: Int) = "assets/$ENCRYPTED_DEX_DIR/$index"

    // 16-byte AES key shared with the shell (shell stores it XOR'd with 0x5A).
    val AES_KEY = byteArrayOf(
        0x31, 0x6B, 0x9F.toByte(), 0x24, 0xC8.toByte(), 0x0A, 0x55, 0xE3.toByte(),
        0x77, 0x12, 0xAB.toByte(), 0x4D, 0x90.toByte(), 0x6E, 0x88.toByte(), 0x1F
    )
}
