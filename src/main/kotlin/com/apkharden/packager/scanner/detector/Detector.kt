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
    val entryNames: List<String>,
    val rules: RuleSet,
)

/** 每个检测器有单一职责，独立可测；抛异常由 PrivacyScanner 隔离。 */
interface Detector {
    val name: String
    fun detect(ctx: ScanContext): List<Finding>
}
