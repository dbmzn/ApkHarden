package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import com.apkharden.packager.scanner.model.Finding

/** 一次扫描的全部输入，传给每个检测器。 */
class ScanContext(
    val apkName: String,
    val manifest: ManifestInfo,
    val dexIndex: DexIndex,
    // APK 内所有 zip 条目名。预留给「隐私政策资源/明文配置」类合规检查（见 plan 末尾 follow-up），
    // 当前 ComplianceFileDetector 只查 manifest 安全位，尚未消费此字段。
    val entryNames: List<String>,
    val rules: RuleSet,
)

/** 每个检测器有单一职责，独立可测；抛异常由 PrivacyScanner 隔离。 */
interface Detector {
    val name: String
    fun detect(ctx: ScanContext): List<Finding>
}
