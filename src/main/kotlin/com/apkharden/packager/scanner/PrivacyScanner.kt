package com.apkharden.packager.scanner

import com.apkharden.packager.core.ApkReader
import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.detector.*
import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import java.io.File

object PrivacyScanner {

    private fun defaultDetectors(): List<Detector> = listOf(
        PermissionDetector(), SdkInventoryDetector(), SensitiveApiDetector(), ComplianceFileDetector(),
    )

    fun scan(apk: File, rules: RuleSet = RuleSet.bundled(), log: (String) -> Unit = {}): ScanReport =
        scanWith(apk, rules, defaultDetectors(), log)

    /** 只读分析：单个检测器抛异常被隔离成一条 INFO，绝不让整体扫描失败。 */
    fun scanWith(apk: File, rules: RuleSet, detectors: List<Detector>, log: (String) -> Unit): ScanReport {
        require(apk.exists()) { "APK 不存在：$apk" }
        log("读取 APK…")
        val (manifestBytes, dexes, entryNames) = ApkReader(apk).use { r ->
            val names = r.dexNames()
            require(names.isNotEmpty()) { "APK 中没有 classes.dex" }
            Triple(r.manifestBytes(), names.map { it to r.read(it) }, r.entryNames())
        }

        log("解析 manifest…")
        val manifest = ManifestReader.parse(manifestBytes)
        log("索引 ${dexes.size} 个 dex…")
        val ctx = ScanContext(apk.name, manifest, DexIndex(dexes), entryNames, rules)

        val findings = ArrayList<Finding>()
        for (d in detectors) {
            log("检测：${d.name}…")
            try {
                findings += d.detect(ctx)
            } catch (t: Throwable) {
                findings += Finding("扫描诊断", Severity.INFO, "${d.name} 未完成",
                    "该检测器执行出错：${t.message}", null,
                    "其余结果不受影响；如需排查可反馈此包。", "diag:${d.name}")
            }
        }
        log("汇总 ${findings.size} 项…")
        return ScanReport.of(apk.name, manifest.packageName, manifest.versionName, findings)
    }
}
