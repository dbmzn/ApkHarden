package com.apkharden.packager.core

import java.io.File

object HardenPipeline {

    /** Loads the embedded prebuilt shell.dex from resources. */
    private fun loadShellDex(): ByteArray =
        (javaClass.getResourceAsStream("/shell.dex")
            ?: error("shell.dex missing from resources — run scripts/build-shell.ps1"))
            .use { it.readBytes() }

    fun harden(
        input: File,
        output: File,
        keystore: File,
        storePass: String,
        alias: String,
        keyPass: String,
        log: (String) -> Unit = {},
    ) {
        require(input.exists()) { "Input APK not found: $input" }
        require(keystore.exists()) { "Keystore not found: $keystore" }

        log("Loading keystore…")
        val creds = KeystoreUtil.load(keystore, storePass, alias, keyPass)
        val sigHash = KeystoreUtil.expectedSigHash(creds)

        log("Reading APK…")
        val (manifestBytes, originalApp, encryptedDexes) = ApkReader(input).use { r ->
            val dexes = r.dexNames()
            require(dexes.isNotEmpty()) { "APK contains no classes.dex" }
            val rawDexes = dexes.map { r.read(it) }
            val manifest = r.manifestBytes()
            log("Linting for hardening-fragile patterns…")
            HardenLinter.lint(r.entryNames(), manifest, rawDexes, log)
            log("Encrypting ${dexes.size} dex file(s)…")
            val enc = rawDexes.map { DexEncryptor.encrypt(it) }
            Triple(manifest, ManifestPatcher.readApplicationClass(manifest), enc)
        }

        log("Patching manifest (entry → ProxyApplication)…")
        val patchedManifest = ManifestPatcher.patch(
            manifestBytes = manifestBytes,
            originalAppClass = originalApp,
            sigHash = sigHash,
            dexCount = encryptedDexes.size,
        )

        log("Repackaging…")
        val unsigned = File.createTempFile("apkharden-unsigned", ".apk")
        try {
            ApkRepackager.repackage(
                input = input,
                output = unsigned,
                patchedManifest = patchedManifest,
                shellDex = loadShellDex(),
                encryptedDexes = encryptedDexes,
            )
            log("Signing (V1+V2+V3)…")
            ApkSignerWrapper.sign(unsigned, output, creds)
        } finally {
            unsigned.delete()
        }

        check(ApkSignerWrapper.verify(output)) { "Output APK failed signature verification" }
        log("Done → ${output.absolutePath}")
    }
}
