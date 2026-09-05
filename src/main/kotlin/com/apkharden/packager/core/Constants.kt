package com.apkharden.packager.core

object Constants {
    const val GUARD_PROVIDER = "com.apkharden.guard.GuardProvider"
    const val META_SIG_HASH = "com.apkharden.SIG_HASH"
    const val SHELL_APPLICATION = "com.apkharden.shell.ProxyApplication"
    const val SHELL_COMPONENT_FACTORY = "com.apkharden.shell.ShellComponentFactory"
    const val META_ORIGINAL_APPLICATION = "com.apkharden.ORIGINAL_APPLICATION"
    const val META_ORIGINAL_COMPONENT_FACTORY = "com.apkharden.ORIGINAL_COMPONENT_FACTORY"
    const val META_DEX_COUNT = "com.apkharden.DEX_COUNT"
    const val PAYLOAD_DIRECTORY = "assets/.apkharden"
    const val PAYLOAD_METADATA = "$PAYLOAD_DIRECTORY/metadata.properties"
    const val SHELL_LIBRARY_NAME = "libapkharden.so"

    fun encryptedDexEntry(index: Int): String = "$PAYLOAD_DIRECTORY/$index.bin"
}
