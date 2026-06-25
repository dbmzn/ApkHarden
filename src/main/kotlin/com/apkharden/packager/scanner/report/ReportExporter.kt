package com.apkharden.packager.scanner.report

import com.apkharden.packager.scanner.model.FindingTier
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import com.apkharden.packager.scanner.model.tier

object ReportExporter {

    private const val DISCLAIMER =
        "本报告为静态自查，标注的是风险嫌疑项而非合规判定；调用时机/实际行为需结合动态验证。"

    // 两层各自的说明：需整改是确定问题；需自查是工具无法判定、需人工核对的披露/告知项。
    private fun tierNote(t: FindingTier) = when (t) {
        FindingTier.ISSUE -> "工具静态判定的配置问题，建议修正。"
        FindingTier.REVIEW -> "工具无法判定是否合规，请逐项核对：是否已在隐私政策声明用途、是否场景化申请、是否在用户同意后调用。"
    }

    private fun label(s: Severity) = when (s) {
        Severity.HIGH -> "高"; Severity.MEDIUM -> "中"; Severity.LOW -> "低"; Severity.INFO -> "提示"
    }

    fun toMarkdown(r: ScanReport): String = buildString {
        val byTier = r.findings.groupBy { it.tier() }
        appendLine("# 隐私合规静态扫描报告")
        appendLine()
        appendLine("- APK：${r.apkName}")
        appendLine("- 包名：${r.packageName ?: "未知"}　版本：${r.versionName ?: "未知"}")
        append("- 概览：")
        appendLine(FindingTier.entries.joinToString("　") { t -> "${t.label} ${byTier[t]?.size ?: 0}" })
        appendLine()
        for (t in FindingTier.entries) {
            val items = byTier[t] ?: continue
            appendLine("## ${t.label}")
            appendLine("> ${tierNote(t)}")
            appendLine()
            for ((category, group) in items.groupBy { it.category }) {
                appendLine("### $category")
                appendLine()
                for (f in group) {
                    appendLine("- **[${label(f.severity)}] ${f.title}**${f.location?.let { "　@$it" } ?: ""}")
                    appendLine("  - ${f.detail}")
                    appendLine("  - 建议：${f.advice}")
                }
                appendLine()
            }
        }
        appendLine("---")
        appendLine("> $DISCLAIMER")
    }

    fun toHtml(r: ScanReport): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val color = mapOf(
            Severity.HIGH to "#d32f2f", Severity.MEDIUM to "#f57c00",
            Severity.LOW to "#fbc02d", Severity.INFO to "#607d8b",
        )
        val tierColor = mapOf(FindingTier.ISSUE to "#d32f2f", FindingTier.REVIEW to "#3f6fb0")
        val byTier = r.findings.groupBy { it.tier() }
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>隐私合规扫描报告</title>")
            appendLine("<style>")
            appendLine("body{font-family:system-ui,'Microsoft YaHei',sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#222}")
            appendLine("h1{font-size:20px}h2{font-size:17px;margin-top:28px}h3{font-size:15px;color:#444;margin:18px 0 6px}")
            appendLine(".note{color:#666;font-size:12px;margin:2px 0 10px}")
            appendLine(".badge{display:inline-block;color:#fff;border-radius:4px;padding:1px 8px;font-size:12px;margin-right:6px}")
            appendLine(".item{margin:10px 0;padding:8px 12px;background:#fafafa;border-left:3px solid #ccc;border-radius:4px}")
            appendLine(".loc{color:#888;font-size:12px}.advice{color:#33691e;font-size:13px}.foot{color:#888;font-size:12px;margin-top:28px;border-top:1px solid #eee;padding-top:8px}")
            appendLine("</style></head><body>")
            appendLine("<h1>隐私合规静态扫描报告</h1>")
            appendLine("<p>APK：${esc(r.apkName)}<br>包名：${esc(r.packageName ?: "未知")}　版本：${esc(r.versionName ?: "未知")}</p>")
            append("<p>")
            for (t in FindingTier.entries) {
                append("<span class=\"badge\" style=\"background:${tierColor[t]}\">${t.label} ${byTier[t]?.size ?: 0}</span>")
            }
            appendLine("</p>")
            for (t in FindingTier.entries) {
                val items = byTier[t] ?: continue
                appendLine("<h2>${t.label}</h2>")
                appendLine("<div class=\"note\">${esc(tierNote(t))}</div>")
                for ((category, group) in items.groupBy { it.category }) {
                    appendLine("<h3>${esc(category)}</h3>")
                    for (f in group) {
                        appendLine("<div class=\"item\">")
                        appendLine("<span class=\"badge\" style=\"background:${color[f.severity]}\">${label(f.severity)}</span>")
                        append("<b>${esc(f.title)}</b>")
                        f.location?.let { append(" <span class=\"loc\">@${esc(it)}</span>") }
                        appendLine("<div>${esc(f.detail)}</div>")
                        appendLine("<div class=\"advice\">建议：${esc(f.advice)}</div>")
                        appendLine("</div>")
                    }
                }
            }
            appendLine("<p class=\"foot\">${esc(DISCLAIMER)}</p>")
            appendLine("</body></html>")
        }
    }
}
