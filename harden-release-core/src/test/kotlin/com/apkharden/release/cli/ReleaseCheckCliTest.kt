package com.apkharden.release.cli

import com.apkharden.release.fixture.TestApkFactory
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseCheckCliTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `missing arguments return usage error without reading passwords`() {
        assertEquals(2, ReleaseCheckCli.run(emptyArray(), emptyMap()))
    }

    @Test
    fun `valid fixtures return zero and write static verified report`() {
        val online = TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, "com.example.app", 10),
            File(temp, "online.apk"),
        )
        val candidate = TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, "com.example.app", 11),
            File(temp, "candidate.apk"),
        )
        val metadata = TestApkFactory.metadata(
            File(temp, "metadata.json"),
            "com.example.app",
            11,
            emptySet(),
        )
        val report = File(temp, "report.json")

        val code = ReleaseCheckCli.run(
            arrayOf(
                "--online", online.path,
                "--candidate", candidate.path,
                "--metadata", metadata.path,
                "--keystore", TestApkFactory.keystoreRequest().file.path,
                "--alias", "test",
                "--report", report.path,
            ),
            mapOf(
                "APK_HARDEN_STORE_PASS" to "123456",
                "APK_HARDEN_KEY_PASS" to "123456",
            ),
        )

        assertEquals(0, code)
        assertTrue(report.readText().contains("STATIC_VERIFIED"))
    }
}
