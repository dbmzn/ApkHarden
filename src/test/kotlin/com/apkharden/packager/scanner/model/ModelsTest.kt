package com.apkharden.packager.scanner.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelsTest {
    @Test
    fun `summary counts findings by severity`() {
        val findings = listOf(
            Finding("设备标识", Severity.HIGH, "a", "d", null, "x", "r1"),
            Finding("位置", Severity.HIGH, "b", "d", "classes.dex", "x", "r2"),
            Finding("其它", Severity.LOW, "c", "d", null, "x", "r3"),
        )
        val report = ScanReport.of("app.apk", "com.x", "1.0", findings)
        assertEquals(2, report.summary[Severity.HIGH])
        assertEquals(1, report.summary[Severity.LOW])
        assertNull(report.summary[Severity.INFO])
        // 排序：HIGH 在 LOW 之前
        assertEquals(Severity.HIGH, report.findings.first().severity)
    }

    @Test
    fun `tier splits certain issues from self-check items`() {
        fun f(ruleId: String) = Finding("c", Severity.HIGH, "t", "d", null, "a", ruleId)
        // compliance 前缀 = 工具静态可判定的确定问题
        assertEquals(FindingTier.ISSUE, f("compliance:dbg").tier())
        // 权限/SDK/敏感API/诊断 = 需人工核对的自查项，不是违规判定
        assertEquals(FindingTier.REVIEW, f("perm:android.permission.ACCESS_FINE_LOCATION").tier())
        assertEquals(FindingTier.REVIEW, f("sdk:umeng").tier())
        assertEquals(FindingTier.REVIEW, f("api:imei").tier())
        assertEquals(FindingTier.REVIEW, f("diag:权限检测").tier())
    }
}
