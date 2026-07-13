package com.apkharden.release.report

import com.apkharden.release.model.FindingLevel
import com.apkharden.release.model.ReleaseAssessment
import com.apkharden.release.model.ReleaseFinding
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseReportWriterTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `report contains status and stable finding code`() {
        val output = File(temp, "report.json")
        ReleaseReportWriter.write(
            ReleaseAssessment(
                listOf(
                    ReleaseFinding(
                        "PACKAGE_MISMATCH",
                        FindingLevel.BLOCKER,
                        "diff",
                    )
                )
            ),
            output,
        )

        val text = output.readText()
        assertTrue(text.contains("NOT_QUALIFIED"))
        assertTrue(text.contains("PACKAGE_MISMATCH"))
    }
}
