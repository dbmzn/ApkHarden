package com.apkharden.gradle

object PluginBuildInfo {
    const val VERSION = "0.1.0"
    const val RUNTIME_VERSION = "0.1.0"
    const val RUNTIME_GROUP = "com.apkharden"
    const val RUNTIME_ARTIFACT = "harden-runtime"
    const val RUNTIME_COORDINATE = "$RUNTIME_GROUP:$RUNTIME_ARTIFACT:$RUNTIME_VERSION"
    const val STRING_CRYPTO_ARTIFACT = "harden-string-crypto"
    const val STRING_CRYPTO_COORDINATE =
        "$RUNTIME_GROUP:$STRING_CRYPTO_ARTIFACT:$RUNTIME_VERSION"
}
