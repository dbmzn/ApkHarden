package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SdkInventoryDetectorTest {
    private val rules = RuleSet.fromJson(
        sdk = """{"sdks":[
            {"id":"demo","name":"演示SDK","vendor":"v","category":"统计分析","packages":["com/example/sdkdemo/"],"note":"n"}
        ]}""",
        api = """{"apis":[]}""", perm = """{"permissions":[]}""", compliance = """{"checks":[]}""",
    )

    private fun ctx(): ScanContext {
        val dex = File("src/test/resources/sample.dex").readBytes()
        val info = ManifestInfo("com.x", "1.0", 31, emptyList(), false, false)
        return ScanContext("x.apk", info, DexIndex(listOf("classes.dex" to dex)), emptyList(), rules)
    }

    @Test
    fun `detects bundled sdk once by package prefix`() {
        val f = SdkInventoryDetector().detect(ctx())
        val hits = f.filter { it.sourceRuleId == "sdk:demo" }
        assertEquals(1, hits.size, "同一 SDK 只出一条")
        assertEquals("演示SDK", hits.single().title)
        assertEquals("classes.dex", hits.single().location)
    }
}
