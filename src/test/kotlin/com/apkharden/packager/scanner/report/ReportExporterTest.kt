package com.apkharden.packager.scanner.report

import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReportExporterTest {
    private fun report() = ScanReport.of(
        "app.apk", "com.example.demo", "1.0",
        listOf(
            Finding("设备标识", Severity.HIGH, "读取 IMEI", "检测到调用点 getImei", "classes.dex", "改用 OAID", "api:imei"),
            Finding("第三方SDK · 统计分析", Severity.INFO, "友盟统计", "检测到集成 友盟统计", "classes.dex", "声明", "sdk:umeng"),
        ),
    )

    @Test
    fun `markdown contains title, finding and disclaimer`() {
        val md = ReportExporter.toMarkdown(report())
        assertTrue(md.contains("com.example.demo"))
        assertTrue(md.contains("读取 IMEI"))
        assertTrue(md.contains("改用 OAID"))
        assertTrue(md.contains("静态自查"))
    }

    @Test
    fun `html is self-contained with no external links`() {
        val html = ReportExporter.toHtml(report())
        assertTrue(html.trimStart().startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("读取 IMEI"))
        assertTrue(html.contains("<style>"))
        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `html escapes special characters`() {
        val r = ScanReport.of("a.apk", "p", "1",
            listOf(Finding("c", Severity.LOW, "<b>x</b>", "a & b", null, "y", "r")))
        val html = ReportExporter.toHtml(r)
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"))
        assertTrue(html.contains("a &amp; b"))
    }
}
