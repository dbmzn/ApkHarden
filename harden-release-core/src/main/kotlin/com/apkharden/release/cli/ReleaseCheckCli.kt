package com.apkharden.release.cli

import com.apkharden.release.gate.ReleaseAnalyzer
import com.apkharden.release.model.KeystoreRequest
import com.apkharden.release.model.ReleaseRequest
import com.apkharden.release.model.ReleaseStatus
import com.apkharden.release.report.ReleaseReportWriter
import java.io.File

object ReleaseCheckCli {
    private val required = setOf(
        "online",
        "candidate",
        "metadata",
        "keystore",
        "alias",
        "report",
    )

    fun run(
        args: Array<String>,
        env: Map<String, String> = System.getenv(),
    ): Int {
        val values = parseArguments(args) ?: return usageError()
        if (!values.keys.containsAll(required)) return usageError()
        val storePassword = env["APK_HARDEN_STORE_PASS"]
        val keyPassword = env["APK_HARDEN_KEY_PASS"]
        if (storePassword == null || keyPassword == null) {
            System.err.println(
                "APK_HARDEN_STORE_PASS and APK_HARDEN_KEY_PASS must be set"
            )
            return 3
        }

        return try {
            val approvals = values["approve"]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()
            val storeChars = storePassword.toCharArray()
            val keyChars = keyPassword.toCharArray()
            val assessment = try {
                ReleaseAnalyzer.analyze(
                    ReleaseRequest(
                        onlineApk = File(values.getValue("online")),
                        candidateApk = File(values.getValue("candidate")),
                        metadataFile = File(values.getValue("metadata")),
                        keystore = KeystoreRequest(
                            file = File(values.getValue("keystore")),
                            storePassword = storeChars,
                            alias = values.getValue("alias"),
                            keyPassword = keyChars,
                        ),
                        approvedFindingCodes = approvals,
                    )
                )
            } finally {
                storeChars.fill('\u0000')
                keyChars.fill('\u0000')
            }
            ReleaseReportWriter.write(
                assessment,
                File(values.getValue("report")),
            )
            println("status=${assessment.status}")
            assessment.findings.forEach {
                println("${it.level}:${it.code}:${it.message}")
            }
            if (assessment.status == ReleaseStatus.STATIC_VERIFIED) 0 else 1
        } catch (error: Exception) {
            System.err.println(error.message ?: error.javaClass.simpleName)
            4
        }
    }

    private fun parseArguments(args: Array<String>): Map<String, String>? {
        if (args.size % 2 != 0) return null
        val values = linkedMapOf<String, String>()
        for (index in args.indices step 2) {
            val option = args[index]
            if (!option.startsWith("--") || option.length == 2) return null
            val key = option.removePrefix("--")
            if (key in values) return null
            values[key] = args[index + 1]
        }
        return values
    }

    private fun usageError(): Int {
        System.err.println(
            "Usage: --online old.apk --candidate new.apk --metadata metadata.json " +
                "--keystore release.jks --alias alias --report report.json"
        )
        return 2
    }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(ReleaseCheckCli.run(args))
}
