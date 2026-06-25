package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComplianceFileDetectorTest {
    private val rules = RuleSet.fromJson(
        sdk = """{"sdks":[]}""", api = """{"apis":[]}""", perm = """{"permissions":[]}""",
        compliance = """{"checks":[
            {"id":"sdk31","type":"TARGET_SDK_MIN","value":31,"severity":"MEDIUM","advice":"升级 targetSdk"},
            {"id":"dbg","type":"DEBUGGABLE_FALSE","severity":"HIGH","advice":"关闭 debuggable"},
            {"id":"bak","type":"ALLOW_BACKUP_FALSE","severity":"LOW","advice":"关闭备份"}
        ]}""",
    )

    private fun ctx(targetSdk: Int?, debuggable: Boolean?, allowBackup: Boolean?): ScanContext {
        val info = ManifestInfo("com.x", "1.0", targetSdk, emptyList(), debuggable, allowBackup)
        return ScanContext("x.apk", info, DexIndex(emptyList()), emptyList(), rules)
    }

    @Test
    fun `flags low targetSdk, debuggable and allowBackup`() {
        val f = ComplianceFileDetector().detect(ctx(targetSdk = 28, debuggable = true, allowBackup = true))
        assertTrue(f.any { it.sourceRuleId == "compliance:sdk31" })
        assertTrue(f.any { it.sourceRuleId == "compliance:dbg" })
        assertTrue(f.any { it.sourceRuleId == "compliance:bak" })
    }

    @Test
    fun `clean manifest yields no compliance findings`() {
        val f = ComplianceFileDetector().detect(ctx(targetSdk = 34, debuggable = false, allowBackup = false))
        assertTrue(f.isEmpty())
    }
}
