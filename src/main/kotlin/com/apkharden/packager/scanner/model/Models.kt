package com.apkharden.packager.scanner.model

/** 报告里的严重度，序数即排序权重（HIGH 最靠前）。 */
enum class Severity { HIGH, MEDIUM, LOW, INFO }

/** 携带「值 + 来源 dex 名」，让 Finding 能定位到具体 dex。 */
data class Located<out T>(val value: T, val dex: String)

/** 一条风险项。所有面向用户的文案（title/detail/advice）都来自规则 JSON。 */
data class Finding(
    val category: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val location: String?,
    val advice: String,
    val sourceRuleId: String,
)

/**
 * 报告分层（对标市面隐私合规工具的「问题 vs 自查项」结构）：
 *  - ISSUE 需整改：工具静态即可判定的确定问题（debuggable / targetSdk 过低 / allowBackup 等）。
 *  - REVIEW 需自查：工具无法判定是否合规、需人工核对的披露/告知项（敏感权限、第三方 SDK、
 *    敏感 API 调用点）——这些不是「违规」，而是「确认你是否已在隐私政策声明用途、场景化申请、
 *    同意后调用」。严守「自查而非判定」的定位。
 */
enum class FindingTier(val label: String) { ISSUE("需整改"), REVIEW("需自查") }

/** 按 sourceRuleId 前缀归层：compliance 前缀为确定问题，其余（perm/sdk/api/diag）为需自查。 */
fun Finding.tier(): FindingTier =
    if (sourceRuleId.startsWith("compliance:")) FindingTier.ISSUE else FindingTier.REVIEW

data class ScanReport(
    val apkName: String,
    val packageName: String?,
    val versionName: String?,
    val findings: List<Finding>,
    val summary: Map<Severity, Int>,
) {
    companion object {
        fun of(apkName: String, pkg: String?, versionName: String?, findings: List<Finding>): ScanReport {
            val sorted = findings.sortedWith(compareBy({ it.severity.ordinal }, { it.category }))
            val summary = findings.groupingBy { it.severity }.eachCount()
            return ScanReport(apkName, pkg, versionName, sorted, summary)
        }
    }
}
