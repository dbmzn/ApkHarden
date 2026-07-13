package com.apkharden.release.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseModelsTest {
    @Test
    fun `blocker prevents static verification`() {
        val result = ReleaseAssessment(
            listOf(ReleaseFinding("PACKAGE_MISMATCH", FindingLevel.BLOCKER, "diff"))
        )
        assertEquals(ReleaseStatus.NOT_QUALIFIED, result.status)
    }

    @Test
    fun `approval finding requires matching approval code`() {
        val finding = ReleaseFinding(
            "MIN_SDK_INCREASED",
            FindingLevel.REQUIRES_APPROVAL,
            "raised",
        )
        assertEquals(
            ReleaseStatus.NOT_QUALIFIED,
            ReleaseAssessment(listOf(finding)).status,
        )
        assertEquals(
            ReleaseStatus.STATIC_VERIFIED,
            ReleaseAssessment(
                listOf(finding),
                setOf("MIN_SDK_INCREASED"),
            ).status,
        )
    }
}
