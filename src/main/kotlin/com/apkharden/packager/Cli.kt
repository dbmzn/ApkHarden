package com.apkharden.packager

import com.apkharden.packager.core.ProductionHardenPipeline
import com.apkharden.packager.signing.SigningProfileStore
import java.io.File

/**
 * Headless harden entrypoint for CI / scripting (the GUI [main] stays the default).
 *
 * Usage:
 *   --input <apk> --output <apk> --keystore <jks> --storePass <p> --alias <a> --keyPass <p>
 *   --input <apk> --output <apk> --savedProfile true
 */
fun main(args: Array<String>) {
    val m = parseArgs(args)
    fun req(k: String): String = m[k] ?: error("Missing required --$k")
    val savedProfile = if (m["savedProfile"]?.toBooleanStrictOrNull() == true) {
        SigningProfileStore().load() ?: error("No saved signing profile")
    } else {
        null
    }

    ProductionHardenPipeline.harden(
        input = File(req("input")),
        output = File(req("output")),
        keystore = File(savedProfile?.keystorePath ?: req("keystore")),
        storePass = savedProfile?.storePassword ?: req("storePass"),
        alias = savedProfile?.alias ?: req("alias"),
        keyPass = savedProfile?.keyPassword ?: req("keyPass"),
        log = { println("[harden] $it") },
    )
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val value = args.getOrNull(i + 1) ?: error("Missing value for $a")
            m[key] = value
            i += 2
        } else i += 1
    }
    return m
}
