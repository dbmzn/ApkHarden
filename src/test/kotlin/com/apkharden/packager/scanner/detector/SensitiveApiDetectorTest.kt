package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SensitiveApiDetectorTest {
    private val rules = RuleSet.fromJson(
        sdk = """{"sdks":[]}""",
        api = """{"apis":[
            {"id":"demo-api","title":"演示敏感调用","category":"设备标识","severity":"HIGH",
             "methods":["Lcom/example/sdkdemo/TrackingSdk;->helper"],"advice":"核实调用时机"}
        ]}""",
        perm = """{"permissions":[]}""", compliance = """{"checks":[]}""",
    )

    private fun ctx(): ScanContext {
        val dex = File("src/test/resources/sample.dex").readBytes()
        val info = ManifestInfo("com.x", "1.0", 31, emptyList(), false, false)
        return ScanContext("x.apk", info, DexIndex(listOf("classes2.dex" to dex)), emptyList(), rules)
    }

    @Test
    fun `detects referenced sensitive method once, located to dex`() {
        val f = SensitiveApiDetector().detect(ctx())
        val hit = f.single { it.sourceRuleId == "api:demo-api" }
        assertEquals(Severity.HIGH, hit.severity)
        assertEquals("classes2.dex", hit.location)
        assertTrue(hit.detail.contains("helper"))
    }

    @Test
    fun `no false positive when method not referenced`() {
        val r = RuleSet.fromJson(
            sdk = """{"sdks":[]}""",
            api = """{"apis":[{"id":"x","title":"t","category":"c","severity":"HIGH","methods":["Landroid/foo/Bar;->baz"],"advice":"a"}]}""",
            perm = """{"permissions":[]}""", compliance = """{"checks":[]}""",
        )
        val dex = File("src/test/resources/sample.dex").readBytes()
        val info = ManifestInfo("com.x", "1.0", 31, emptyList(), false, false)
        val c = ScanContext("x.apk", info, DexIndex(listOf("classes.dex" to dex)), emptyList(), r)
        assertTrue(SensitiveApiDetector().detect(c).isEmpty())
    }
}
