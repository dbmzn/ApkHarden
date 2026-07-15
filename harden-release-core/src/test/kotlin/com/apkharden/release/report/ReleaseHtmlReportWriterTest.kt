package com.apkharden.release.report

import com.apkharden.release.model.FindingLevel
import com.apkharden.release.model.ReleaseAssessment
import com.apkharden.release.model.ReleaseFinding
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseHtmlReportWriterTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `html is deterministic escaped and preserves finding fields`() {
        val assessment = ReleaseAssessment(
            findings = listOf(
                ReleaseFinding(
                    code = "PACKAGE_<MISMATCH>",
                    level = FindingLevel.BLOCKER,
                    message = "<script>alert('x')</script>",
                    details = mapOf("candidate" to "a&b", "online" to "a<b"),
                ),
            ),
        )
        val first = File(temp, "first.html")
        val second = File(temp, "second.html")

        ReleaseHtmlReportWriter.write(assessment, first)
        ReleaseHtmlReportWriter.write(assessment, second)

        assertEquals(first.readText(), second.readText())
        val html = first.readText()
        assertTrue(html.contains("PACKAGE_&lt;MISMATCH&gt;"))
        assertTrue(html.contains("BLOCKER"))
        assertTrue(html.contains("a&amp;b"))
        assertTrue(html.contains("a&lt;b"))
        assertFalse(html.contains("<script>"))
    }
}
