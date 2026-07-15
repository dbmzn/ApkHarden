package com.apkharden.release.report

import com.apkharden.release.model.ApkIdentity
import com.apkharden.release.model.ReleaseAssessment
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

object ReleaseHtmlReportWriter {
    fun write(assessment: ReleaseAssessment, output: File) {
        val findings = assessment.findings.joinToString("\n") { finding ->
            val details = finding.details.toSortedMap().entries.joinToString("\n") { (key, value) ->
                "<dt>${key.html()}</dt><dd>${value.html()}</dd>"
            }
            """
                <article class="finding level-${finding.level.name.lowercase()}">
                  <header><code>${finding.code.html()}</code><strong>${finding.level.name}</strong></header>
                  <p>${finding.message.html()}</p>
                  ${if (details.isEmpty()) "" else "<dl>$details</dl>"}
                </article>
            """.trimIndent()
        }.ifEmpty { "<p class=\"empty\">No findings.</p>" }
        val approvals = assessment.approvedFindingCodes.toSortedSet()
            .joinToString("", prefix = "<ul>", postfix = "</ul>") { code ->
                "<li><code>${code.html()}</code></li>"
            }
            .takeUnless { assessment.approvedFindingCodes.isEmpty() }
            ?: "<p class=\"empty\">No approved findings.</p>"
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>ApkHarden Release Report</title>
              <style>
                body{font-family:system-ui,sans-serif;margin:32px;color:#20242c;background:#f7f8fa}
                main{max-width:1040px;margin:auto}h1,h2{letter-spacing:0}section{margin:24px 0}
                table{width:100%;border-collapse:collapse;background:white}th,td{padding:9px 12px;border:1px solid #dfe3e8;text-align:left;vertical-align:top}
                th{width:220px;background:#f1f3f5}.status{font-size:20px;font-weight:700}.finding{padding:14px;margin:10px 0;background:white;border:1px solid #dfe3e8;border-left-width:5px}
                .level-blocker{border-left-color:#c62828}.level-requires_approval{border-left-color:#c77800}.level-warning{border-left-color:#8a6d00}.level-info{border-left-color:#2463a5}
                .finding header{display:flex;justify-content:space-between;gap:16px}.finding p{margin:10px 0}.finding dl{display:grid;grid-template-columns:220px 1fr;margin:0}.finding dt,.finding dd{padding:5px 0;margin:0;border-top:1px solid #edf0f2}.empty{color:#667085}code{font-family:ui-monospace,monospace}
              </style>
            </head>
            <body><main>
              <h1>ApkHarden Release Report</h1>
              <p class="status">Status: ${assessment.status.name}</p>
              <section><h2>Online APK</h2>${assessment.online.identityTable()}</section>
              <section><h2>Candidate APK</h2>${assessment.candidate.identityTable()}</section>
              <section><h2>Keystore</h2>${assessment.keystore?.let { keystore ->
                  rows(
                      "Alias" to keystore.alias,
                      "Certificate SHA-256" to keystore.certificateSha256,
                      "Certificate subject" to keystore.certificateSubject,
                  )
              } ?: missing()}</section>
              <section><h2>Approved Finding Codes</h2>$approvals</section>
              <section><h2>Findings (${assessment.findings.size})</h2>$findings</section>
            </main></body>
            </html>
        """.trimIndent() + "\n"
        writeTextAtomically(output, html)
    }

    private fun ApkIdentity?.identityTable(): String = this?.let { identity ->
        rows(
            "File" to identity.fileName,
            "Package" to identity.packageName,
            "Version code" to identity.versionCode.toString(),
            "Version name" to identity.versionName.orEmpty(),
            "minSdk" to identity.minSdk.toString(),
            "targetSdk" to identity.targetSdk.toString(),
            "Debuggable" to identity.debuggable.toString(),
            "Test only" to identity.testOnly.toString(),
            "Split name" to identity.splitName.orEmpty(),
            "extractNativeLibs" to identity.extractNativeLibs?.toString().orEmpty(),
            "Required features" to identity.requiredFeatures.toSortedSet().joinToString(),
            "ABIs" to identity.abis.toSortedSet().joinToString(),
            "Signature verified" to identity.signature.verified.toString(),
            "Signer count" to identity.signature.signerCount.toString(),
            "Signer SHA-256" to identity.signature.signerSha256.toSortedSet().joinToString(),
            "Signing lineage" to identity.signature.hasSigningLineage.toString(),
            "V1 / V2 / V3 / V3.1" to listOf(
                identity.signature.v1,
                identity.signature.v2,
                identity.signature.v3,
                identity.signature.v31,
            ).joinToString(" / "),
            "Signature errors" to identity.signature.errors.joinToString(),
        )
    } ?: missing()

    private fun rows(vararg values: Pair<String, String>): String = values.joinToString(
        separator = "\n",
        prefix = "<table><tbody>\n",
        postfix = "\n</tbody></table>",
    ) { (label, value) -> "<tr><th>${label.html()}</th><td>${value.html()}</td></tr>" }

    private fun missing(): String = "<p class=\"empty\">Unavailable.</p>"
}

internal fun writeTextAtomically(output: File, content: String) {
    val destination = output.absoluteFile
    val parent = destination.parentFile
    require(parent.exists() || parent.mkdirs()) { "Unable to create report directory: $parent" }
    val temporary = File.createTempFile("apkharden-report-", ".tmp", parent)
    try {
        temporary.writeText(content, Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}

private fun String.html(): String = buildString(length) {
    this@html.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            },
        )
    }
}
