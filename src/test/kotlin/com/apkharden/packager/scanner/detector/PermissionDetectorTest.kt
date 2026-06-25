package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PermissionDetectorTest {
    private fun ctx(perms: List<String>): ScanContext {
        val rules = RuleSet.fromJson(
            sdk = """{"sdks":[]}""",
            api = """{"apis":[]}""",
            perm = """{"permissions":[
                {"name":"android.permission.READ_PHONE_STATE","title":"读取电话状态","category":"设备标识","severity":"HIGH","advice":"a"}
            ]}""",
            compliance = """{"checks":[]}""",
        )
        val info = ManifestInfo("com.x", "1.0", 31, perms, false, false)
        return ScanContext("x.apk", info, DexIndex(emptyList()), emptyList(), rules)
    }

    @Test
    fun `flags known sensitive permission with rule severity`() {
        val f = PermissionDetector().detect(ctx(listOf("android.permission.READ_PHONE_STATE")))
        val hit = f.single { it.detail.contains("READ_PHONE_STATE") }
        assertEquals(Severity.HIGH, hit.severity)
        assertEquals("设备标识", hit.category)
    }

    @Test
    fun `unknown permission surfaces as INFO, not dropped`() {
        val f = PermissionDetector().detect(ctx(listOf("android.permission.FOO_BAR")))
        val hit = f.single { it.detail.contains("FOO_BAR") }
        assertEquals(Severity.INFO, hit.severity)
    }
}
