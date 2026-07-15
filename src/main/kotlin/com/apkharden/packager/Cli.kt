package com.apkharden.packager

import com.apkharden.packager.core.ProductionHardenPipeline
import java.io.File

/**
 * Headless harden entrypoint for CI / scripting (the GUI [main] stays the default).
 *
 * Usage:
 *   --input <apk> --output <apk> --keystore <jks> --storePass <p> --alias <a> --keyPass <p>
 */
fun main(args: Array<String>) {
    val m = parseArgs(args)
    fun req(k: String): String = m[k] ?: error("Missing required --$k")

    ProductionHardenPipeline.harden(
        input = File(req("input")),
        output = File(req("output")),
        keystore = File(req("keystore")),
        storePass = req("storePass"),
        alias = req("alias"),
        keyPass = req("keyPass"),
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
