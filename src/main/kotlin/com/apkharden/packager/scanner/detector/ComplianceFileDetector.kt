package com.apkharden.packager.scanner.detector

import com.apkharden.packager.scanner.model.Finding

/** manifest 安全位/配置体检：targetSdk 下限、debuggable、allowBackup。 */
class ComplianceFileDetector : Detector {
    override val name = "合规配置检查"

    override fun detect(ctx: ScanContext): List<Finding> {
        val info = ctx.manifest
        val out = ArrayList<Finding>()
        for (c in ctx.rules.checks) {
            val triggered: Pair<String, String>? = when (c.type) {
                "TARGET_SDK_MIN" -> {
                    val min = c.value ?: continue
                    val t = info.targetSdk
                    if (t != null && t < min) "targetSdk=$t（要求≥$min）" to "AndroidManifest.xml" else null
                }
                "DEBUGGABLE_FALSE" ->
                    if (info.debuggable == true) "android:debuggable=true" to "AndroidManifest.xml" else null
                "ALLOW_BACKUP_FALSE" ->
                    if (info.allowBackup == true) "android:allowBackup=true" to "AndroidManifest.xml" else null
                else -> null
            }
            if (triggered != null) {
                out += Finding("合规配置", c.severity, c.id.replaceFirstChar { it.uppercase() },
                    triggered.first, triggered.second, c.advice, "compliance:${c.id}")
            }
        }
        return out
    }
}
