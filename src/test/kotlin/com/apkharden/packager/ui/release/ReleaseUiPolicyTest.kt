package com.apkharden.packager.ui.release

import com.apkharden.release.model.ReleaseStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseUiPolicyTest {
    @Test
    fun `analysis requires all release inputs and transient passwords`() {
        assertFalse(ReleaseFormValues().canAnalyze(running = false))
        assertTrue(complete().canAnalyze(running = false))
        assertFalse(complete().copy(storePassword = "").canAnalyze(running = false))
        assertFalse(complete().canAnalyze(running = true))
    }

    @Test
    fun `export requires static verification or higher and output directory`() {
        val form = complete()

        assertFalse(form.canExport(ReleaseStatus.NOT_QUALIFIED, running = false))
        assertTrue(form.canExport(ReleaseStatus.STATIC_VERIFIED, running = false))
        assertTrue(form.canExport(ReleaseStatus.DEVICE_VERIFIED, running = false))
        assertFalse(form.copy(outputDirectory = "").canExport(ReleaseStatus.STATIC_VERIFIED, running = false))
    }

    private fun complete() = ReleaseFormValues(
        onlineApk = "online.apk",
        candidateApk = "candidate.apk",
        metadata = "metadata.json",
        keystore = "release.jks",
        outputDirectory = "release",
        alias = "release",
        storePassword = "store",
        keyPassword = "key",
    )
}
