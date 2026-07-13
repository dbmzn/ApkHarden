package com.apkharden.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardenRuntimeTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val config = HardenConfig(
        applicationId = application.packageName,
        variantName = "androidTest",
        buildId = "runtime-test",
        certificateSha256 = "0".repeat(64),
    )

    @Test
    fun debuggableTestApplicationIsRejected() {
        assertEquals(HardenFailure.DEBUGGABLE, AntiDebug.detect(application))
    }

    @Test
    fun validReleaseLikeContextInitializesOnce() {
        val antiDebugChecks = AtomicInteger()
        val certificateChecks = AtomicInteger()
        val installer = RuntimeInstaller(
            antiDebugCheck = {
                antiDebugChecks.incrementAndGet()
                null
            },
            certificateCheck = { _, _ ->
                certificateChecks.incrementAndGet()
                true
            },
            failureRecorder = FailureRecorder(),
            terminator = ProcessTerminator { error("must not terminate") },
        )

        repeat(5) { installer.install(application, config) }

        assertEquals(1, antiDebugChecks.get())
        assertEquals(1, certificateChecks.get())
    }

    @Test
    fun failedInstallRecordsMinimalDiagnosticsBeforeTermination() {
        val failureFile = File(application.filesDir, FailureRecorder.RELATIVE_PATH)
        failureFile.delete()
        val termination = runCatching {
            RuntimeInstaller(
                antiDebugCheck = { HardenFailure.DEBUGGER_CONNECTED },
                certificateCheck = { _, _ -> error("certificate check must not run") },
                failureRecorder = FailureRecorder(),
                terminator = ProcessTerminator { throw TestTermination() },
            ).install(application, config)
        }.exceptionOrNull()

        assertTrue(termination is TestTermination)
        assertTrue(failureFile.isFile)
        val record = JSONObject(failureFile.readText())
        assertEquals(setOf("code", "runtimeVersion", "process"), record.keys().asSequence().toSet())
        assertEquals(HardenFailure.DEBUGGER_CONNECTED.name, record.getString("code"))
        assertEquals(RuntimeBuildInfo.VERSION, record.getString("runtimeVersion"))
        assertNotNull(record.getString("process"))
    }

    private class TestTermination : RuntimeException()
}
