package com.apkharden.packager.scanner.report

import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity

object ReportExporter {

    private const val DISCLAIMER =
        "本报告为静态自查，标注的是风险嫌疑项而非合规判定；调用时机/实际行为需结合动态验证。"

    private fun label(s: Severity) = when (s) {
        Severity.HIGH -> "高"; Severity.MEDIUM -> "中"; Severity.LOW -> "低"; Severity.INFO -> "提示"
    }

    fun toMarkdown(r: ScanReport): String = buildString {
        appendLine("# 隐私合规静态扫描报告")
        appendLine()
        appendLine("- APK：${r.apkName}")
        appendLine("- 包名：${r.packageName ?: "未知"}　版本：${r.versionName ?: "未知"}")
        append("- 概览：")
        appendLine(Severity.entries.mapNotNull { s -> r.summary[s]?.let { "${label(s)} $it" } }.joinToString("　"))
        appendLine()
        for ((category, items) in r.findings.groupBy { it.category }) {
            appendLine("## $category")
            appendLine()
            for (f in items) {
                appendLine("- **[${label(f.severity)}] ${f.title}**${f.location?.let { "　@$it" } ?: ""}")
                appendLine("  - ${f.detail}")
                appendLine("  - 建议：${f.advice}")
            }
            appendLine()
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
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>隐私合规扫描报告</title>")
            appendLine("<style>")
            appendLine("body{font-family:system-ui,'Microsoft YaHei',sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#222}")
            appendLine("h1{font-size:20px}h2{font-size:16px;border-bottom:1px solid #eee;padding-bottom:4px;margin-top:28px}")
            appendLine(".badge{display:inline-block;color:#fff;border-radius:4px;padding:1px 8px;font-size:12px;margin-right:6px}")
            appendLine(".item{margin:10px 0;padding:8px 12px;background:#fafafa;border-left:3px solid #ccc;border-radius:4px}")
            appendLine(".loc{color:#888;font-size:12px}.advice{color:#33691e;font-size:13px}.foot{color:#888;font-size:12px;margin-top:28px;border-top:1px solid #eee;padding-top:8px}")
            appendLine("</style></head><body>")
            appendLine("<h1>隐私合规静态扫描报告</h1>")
            appendLine("<p>APK：${esc(r.apkName)}<br>包名：${esc(r.packageName ?: "未知")}　版本：${esc(r.versionName ?: "未知")}</p>")
            append("<p>")
            for (s in Severity.entries) r.summary[s]?.let {
                append("<span class=\"badge\" style=\"background:${color[s]}\">${label(s)} $it</span>")
            }
            appendLine("</p>")
            for ((category, items) in r.findings.groupBy { it.category }) {
                appendLine("<h2>${esc(category)}</h2>")
                for (f in items) {
                    appendLine("<div class=\"item\">")
                    appendLine("<span class=\"badge\" style=\"background:${color[f.severity]}\">${label(f.severity)}</span>")
                    append("<b>${esc(f.title)}</b>")
                    f.location?.let { append(" <span class=\"loc\">@${esc(it)}</span>") }
                    appendLine("<div>${esc(f.detail)}</div>")
                    appendLine("<div class=\"advice\">建议：${esc(f.advice)}</div>")
                    appendLine("</div>")
                }
            }
            appendLine("<p class=\"foot\">${esc(DISCLAIMER)}</p>")
            appendLine("</body></html>")
        }
    }
}
