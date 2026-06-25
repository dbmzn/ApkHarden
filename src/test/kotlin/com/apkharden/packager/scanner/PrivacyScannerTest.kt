package com.apkharden.packager.scanner

import com.apkharden.packager.scanner.model.Severity
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PrivacyScannerTest {
    @TempDir lateinit var tmp: File

    private fun apk(perms: List<String>, debuggable: Boolean): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"; versionName = "1.0"
            perms.forEach { addUsesPermission(it) }
            if (debuggable) getOrCreateApplicationElement()
                .getOrCreateAndroidAttribute("debuggable", 0x0101000f).valueAsBoolean = true
            refreshFull()
        }.bytes
        val dex = File("src/test/resources/sample.dex").readBytes()
        val f = File(tmp, "app.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(dex); z.closeEntry()
        }
        return f
    }

    private val testRules = RuleSet.fromJson(
        sdk = """{"sdks":[{"id":"demo","name":"演示SDK","category":"统计分析","packages":["com/example/sdkdemo/"]}]}""",
        api = """{"apis":[{"id":"demo-api","title":"演示调用","category":"设备标识","severity":"HIGH","methods":["Lcom/example/sdkdemo/TrackingSdk;->helper"],"advice":"a"}]}""",
        perm = """{"permissions":[{"name":"android.permission.READ_PHONE_STATE","title":"读取电话状态","category":"设备标识","severity":"HIGH","advice":"a"}]}""",
        compliance = """{"checks":[{"id":"dbg","type":"DEBUGGABLE_FALSE","title":"开启调试","severity":"HIGH","advice":"a"}]}""",
    )

    @Test
    fun `scans across all four detectors`() {
        val logs = mutableListOf<String>()
        val report = PrivacyScanner.scan(apk(listOf("android.permission.READ_PHONE_STATE"), debuggable = true),
            rules = testRules, log = { logs.add(it) })
        assertEquals("com.example.demo", report.packageName)
        assertTrue(report.findings.any { it.sourceRuleId == "perm:android.permission.READ_PHONE_STATE" })
        assertTrue(report.findings.any { it.sourceRuleId == "sdk:demo" })
        assertTrue(report.findings.any { it.sourceRuleId == "api:demo-api" })
        assertTrue(report.findings.any { it.sourceRuleId == "compliance:dbg" })
        assertTrue((report.summary[Severity.HIGH] ?: 0) >= 3)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `a throwing detector is isolated, others still produce findings`() {
        val boom = object : com.apkharden.packager.scanner.detector.Detector {
            override val name = "炸弹"
            override fun detect(ctx: com.apkharden.packager.scanner.detector.ScanContext) = throw RuntimeException("boom")
        }
        val report = PrivacyScanner.scanWith(
            apk(listOf("android.permission.READ_PHONE_STATE"), debuggable = false),
            rules = testRules,
            detectors = listOf(boom, com.apkharden.packager.scanner.detector.PermissionDetector()),
            log = {},
        )
        assertTrue(report.findings.any { it.severity == Severity.INFO && it.title.contains("未完成") })
        assertTrue(report.findings.any { it.sourceRuleId == "perm:android.permission.READ_PHONE_STATE" })
    }

    @Test
    fun `bundled rules do not false-positive on a trivial apk`() {
        val report = PrivacyScanner.scan(apk(emptyList(), debuggable = false),
            rules = RuleSet.bundled(), log = {})
        assertEquals(0, report.summary[Severity.HIGH] ?: 0, "干净包不应有高危误报")
    }
}
