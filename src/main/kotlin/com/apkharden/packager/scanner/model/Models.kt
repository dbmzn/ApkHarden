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
