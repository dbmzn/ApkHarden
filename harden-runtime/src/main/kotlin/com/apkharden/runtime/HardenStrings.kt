package com.apkharden.runtime

import com.apkharden.crypto.StringCrypto
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

object HardenStrings {
    private val state = AtomicReference<State?>()

    internal fun install(config: HardenConfig, table: HardenStringTable) {
        val installation = Installation(
            applicationId = config.applicationId,
            variantName = config.variantName,
            buildId = config.buildId,
            certificateSha256 = config.certificateSha256,
        )
        while (true) {
            val existing = state.get()
            if (existing != null) {
                check(existing.installation == installation && existing.table === table) {
                    "HardenStrings is already installed with a different table"
                }
                return
            }

            val key = StringCrypto.deriveKey(
                table.fragmentA,
                table.fragmentB,
                config.certificateSha256,
                config.applicationId,
                config.buildId,
            )
            val candidate = State(
                installation = installation,
                table = table,
                key = key,
                cache = AtomicReferenceArray(table.size),
            )
            if (state.compareAndSet(null, candidate)) return
            candidate.clear()
        }
    }

    @JvmStatic
    fun decode(entryId: Int): String {
        val installed = checkNotNull(state.get()) { "HardenStrings is not installed" }
        require(entryId in 0 until installed.table.size) {
            "string entry id $entryId is out of bounds"
        }
        installed.cache.get(entryId)?.let { return it }

        val plaintext = StringCrypto.decrypt(
            installed.table.ciphertext(entryId),
            installed.key,
            installed.table.iv(entryId),
            StringCrypto.entryAad(entryId),
        )
        if (installed.cache.compareAndSet(entryId, null, plaintext)) return plaintext
        return requireNotNull(installed.cache.get(entryId))
    }

    internal fun clear() {
        state.getAndSet(null)?.clear()
    }

    private data class Installation(
        val applicationId: String,
        val variantName: String,
        val buildId: String,
        val certificateSha256: String,
    )

    private class State(
        val installation: Installation,
        val table: HardenStringTable,
        val key: ByteArray,
        val cache: AtomicReferenceArray<String>,
    ) {
        fun clear() {
            key.fill(0)
            for (index in 0 until cache.length()) cache.set(index, null)
        }
    }
}
