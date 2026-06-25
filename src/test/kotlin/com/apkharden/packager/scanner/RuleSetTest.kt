package com.apkharden.packager.scanner

import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuleSetTest {
    @Test
    fun `bundled rules load and are non-empty`() {
        val rs = RuleSet.bundled()
        assertTrue(rs.sdks.isNotEmpty())
        assertTrue(rs.apis.isNotEmpty())
        assertTrue(rs.permissions.isNotEmpty())
        assertTrue(rs.checks.isNotEmpty())
        assertTrue(rs.apis.any { it.severity == Severity.HIGH })
        assertTrue(rs.sdks.all { it.packages.isNotEmpty() && it.name.isNotBlank() })
    }

    @Test
    fun `fromJson parses custom rules`() {
        val rs = RuleSet.fromJson(
            sdk = """{"sdks":[{"id":"x","name":"X","category":"测试","packages":["com/x/"]}]}""",
            api = """{"apis":[{"id":"a","title":"A","category":"测试","severity":"LOW","methods":["Lx;->y"],"advice":"z"}]}""",
            perm = """{"permissions":[]}""",
            compliance = """{"checks":[]}""",
        )
        assertEquals("X", rs.sdks.single().name)
        assertEquals(Severity.LOW, rs.apis.single().severity)
    }
}
