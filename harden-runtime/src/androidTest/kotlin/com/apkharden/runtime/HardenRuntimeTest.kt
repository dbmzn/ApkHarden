package com.apkharden.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.apkharden.crypto.StringCrypto
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
        HardenStrings.clear()
        val antiDebugChecks = AtomicInteger()
        val certificateChecks = AtomicInteger()
        val fragmentA = ByteArray(16) { 1 }
        val fragmentB = ByteArray(16) { 2 }
        val iv = ByteArray(12) { it.toByte() }
        val key = StringCrypto.deriveKey(
            fragmentA,
            fragmentB,
            config.certificateSha256,
            config.applicationId,
            config.buildId,
        )
        val table = HardenStringTable(
            fragmentA,
            fragmentB,
            arrayOf(iv),
            arrayOf(StringCrypto.encrypt("runtime", key, iv, StringCrypto.entryAad(0))),
        )
        val installer = RuntimeInstaller(
            antiDebugCheck = {
                antiDebugChecks.incrementAndGet()
                null
            },
            certificateCheck = { _, _ ->
                certificateChecks.incrementAndGet()
                true
            },
            stringTableLoader = { _, _ -> table },
            failureRecorder = FailureRecorder(),
            terminator = ProcessTerminator { error("must not terminate") },
        )

        repeat(5) { installer.install(application, config) }

        assertEquals(1, antiDebugChecks.get())
        assertEquals(1, certificateChecks.get())
        assertEquals("runtime", HardenStrings.decode(0))
        HardenStrings.clear()
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
