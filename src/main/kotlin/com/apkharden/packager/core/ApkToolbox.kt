package com.apkharden.packager.core

import java.io.File
import java.util.zip.ZipFile

internal enum class FindingLevel { BLOCKER, WARNING, PASSED, INFO }

internal data class ApkFinding(
    val level: FindingLevel,
    val title: String,
    val detail: String,
)

internal data class ApkEntryStat(
    val name: String,
    val size: Long,
)

internal data class ApkToolboxReport(
    val file: File,
    val info: ApkInfo,
    val fileSize: Long,
    val dexCount: Int,
    val dexBytes: Long,
    val nativeCount: Int,
    val nativeBytes: Long,
    val resourceBytes: Long,
    val findings: List<ApkFinding>,
    val largestEntries: List<ApkEntryStat>,
) {
    val blockerCount: Int get() = findings.count { it.level == FindingLevel.BLOCKER }
    val warningCount: Int get() = findings.count { it.level == FindingLevel.WARNING }
    val score: Int get() = (100 - blockerCount * 25 - warningCount * 8).coerceAtLeast(0)
}

internal data class ApkEntryChange(
    val name: String,
    val oldSize: Long?,
    val newSize: Long?,
) {
    val delta: Long get() = (newSize ?: 0L) - (oldSize ?: 0L)
}

internal data class ApkComparison(
    val oldReport: ApkToolboxReport,
    val newReport: ApkToolboxReport,
    val signaturesMatch: Boolean,
    val changes: List<ApkEntryChange>,
) {
    val addedCount: Int get() = changes.count { it.oldSize == null }
    val removedCount: Int get() = changes.count { it.newSize == null }
    val modifiedCount: Int get() = changes.count { it.oldSize != null && it.newSize != null }
}

internal object ApkToolbox {
    private val dexName = Regex("classes\\d*\\.dex")
    private val nativeName = Regex("lib/[^/]+/[^/]+\\.so")
    private val sensitivePermissions = setOf(
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
    )

    fun analyze(file: File): ApkToolboxReport {
        val info = ApkInspector.inspect(file)
        val entries = entrySizes(file)
        val dexEntries = entries.filterKeys { dexName.matches(it) }
        val nativeEntries = entries.filterKeys { nativeName.matches(it) }
        val resourceBytes = entries
            .filterKeys { it.startsWith("res/") || it == "resources.arsc" || it.startsWith("assets/") }
            .values.sum()
        val findings = buildList {
            if (!info.signature.verified) {
                add(ApkFinding(FindingLevel.BLOCKER, "APK 签名校验失败", "签名缺失、损坏或签名块无法通过验证，不能直接发布。"))
            } else {
                add(ApkFinding(FindingLevel.PASSED, "APK 签名有效", "${info.signature.schemes.joinToString(" + ")} · ${info.signature.signerCount} 个签名证书。"))
            }
            if (info.debuggable) {
                add(ApkFinding(FindingLevel.BLOCKER, "开启了 debuggable", "正式包允许调试，发布前应关闭 android:debuggable。"))
            } else {
                add(ApkFinding(FindingLevel.PASSED, "调试开关正常", "未开启 android:debuggable。"))
            }
            if (info.testOnly) {
                add(ApkFinding(FindingLevel.BLOCKER, "标记为 testOnly", "普通用户设备无法按正式应用方式安装。"))
            }
            if (info.splitName != null) {
                add(ApkFinding(FindingLevel.WARNING, "当前文件是 Split APK", "split=${info.splitName}，单独检查不能代表完整安装集。"))
            }
            if (dexEntries.isEmpty()) {
                add(ApkFinding(FindingLevel.WARNING, "未发现业务 DEX", "该文件可能是资源 Split，或 APK 结构不完整。"))
            } else {
                add(ApkFinding(FindingLevel.INFO, "DEX 构成", "共 ${dexEntries.size} 个 DEX，解压后 ${formatBytes(dexEntries.values.sum())}。"))
            }
            if ("armeabi-v7a" in info.abis && "arm64-v8a" !in info.abis) {
                add(ApkFinding(FindingLevel.WARNING, "缺少 ARM64", "包含 armeabi-v7a，但没有 arm64-v8a；需要确认 64 位设备发布策略。"))
            }
            if ("x86" in info.abis && "x86_64" !in info.abis) {
                add(ApkFinding(FindingLevel.WARNING, "缺少 x86_64", "包含 x86，但没有 x86_64。"))
            }
            if (nativeEntries.isEmpty()) {
                add(ApkFinding(FindingLevel.INFO, "纯 Java/Kotlin 包", "未发现 APK 内 Native SO。"))
            } else {
                add(ApkFinding(FindingLevel.INFO, "Native 构成", "${info.abis.joinToString()} · ${nativeEntries.size} 个 SO · ${formatBytes(nativeEntries.values.sum())}"))
            }
            val sensitive = info.permissions.intersect(sensitivePermissions)
            if (sensitive.isNotEmpty()) {
                add(ApkFinding(FindingLevel.WARNING, "敏感权限需确认", sensitive.joinToString { it.substringAfterLast('.') }))
            } else {
                add(ApkFinding(FindingLevel.PASSED, "敏感权限基线", "未发现工具关注的高敏感权限。"))
            }
            if (info.exportedComponents.isNotEmpty()) {
                add(ApkFinding(FindingLevel.WARNING, "存在导出组件", "共 ${info.exportedComponents.size} 个，请确认均有明确外部调用需求。"))
            } else {
                add(ApkFinding(FindingLevel.PASSED, "组件导出基线", "未发现显式 exported=true 的组件。"))
            }
        }
        return ApkToolboxReport(
            file = file,
            info = info,
            fileSize = file.length(),
            dexCount = dexEntries.size,
            dexBytes = dexEntries.values.sum(),
            nativeCount = nativeEntries.size,
            nativeBytes = nativeEntries.values.sum(),
            resourceBytes = resourceBytes,
            findings = findings,
            largestEntries = entries.entries.sortedByDescending { it.value }.take(12)
                .map { ApkEntryStat(it.key, it.value) },
        )
    }

    fun compare(oldFile: File, newFile: File): ApkComparison {
        val oldReport = analyze(oldFile)
        val newReport = analyze(newFile)
        val oldEntries = entrySizes(oldFile)
        val newEntries = entrySizes(newFile)
        val changes = (oldEntries.keys + newEntries.keys).distinct().mapNotNull { name ->
            val oldSize = oldEntries[name]
            val newSize = newEntries[name]
            if (oldSize == newSize) null else ApkEntryChange(name, oldSize, newSize)
        }.sortedWith(compareByDescending<ApkEntryChange> { kotlin.math.abs(it.delta) }.thenBy { it.name })
        return ApkComparison(
            oldReport = oldReport,
            newReport = newReport,
            signaturesMatch = oldReport.info.signature.signerSha256 == newReport.info.signature.signerSha256 &&
                oldReport.info.signature.signerSha256.isNotEmpty(),
            changes = changes,
        )
    }

    private fun entrySizes(file: File): Map<String, Long> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.associate { entry ->
            entry.name to entry.size.coerceAtLeast(0L)
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    kotlin.math.abs(bytes) >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    kotlin.math.abs(bytes) >= 1024L * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
    kotlin.math.abs(bytes) >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
