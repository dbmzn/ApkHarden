package com.apkharden.release.gate

import com.apkharden.release.fixture.TestApkFactory
import com.apkharden.release.model.ReleaseRequest
import com.apkharden.release.model.ReleaseStatus
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseAnalyzerTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `valid signed fixture reaches static verified`() {
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
            applicationId = "com.example.app",
            versionCode = 11,
            abis = emptySet(),
        )

        val assessment = ReleaseAnalyzer.analyze(
            ReleaseRequest(
                onlineApk = online,
                candidateApk = candidate,
                metadataFile = metadata,
                keystore = TestApkFactory.keystoreRequest(),
            )
        )

        assertEquals(ReleaseStatus.STATIC_VERIFIED, assessment.status)
        assertEquals(emptyList<String>(), assessment.findings.map { it.code })
        assertEquals("com.example.app", assessment.online?.packageName)
        assertEquals(11L, assessment.candidate?.versionCode)
    }

    @Test
    fun `equal version candidate remains not qualified`() {
        val online = TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, "com.example.app", 10),
            File(temp, "online.apk"),
        )
        val candidate = TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, "com.example.app", 10),
            File(temp, "candidate.apk"),
        )
        val metadata = TestApkFactory.metadata(
            File(temp, "metadata.json"),
            "com.example.app",
            10,
            emptySet(),
        )

        val assessment = ReleaseAnalyzer.analyze(
            ReleaseRequest(
                online,
                candidate,
                metadata,
                TestApkFactory.keystoreRequest(),
            )
        )

        assertEquals(ReleaseStatus.NOT_QUALIFIED, assessment.status)
        assertTrue(assessment.findings.any { it.code == "VERSION_NOT_INCREMENTED" })
    }

    @Test
    fun `compressed invalid native library is blocked when extraction is disabled`() {
        val online = TestApkFactory.sign(
            TestApkFactory.createUnsigned(
                temp,
                "com.example.nativeapp",
                10,
                extractNativeLibs = false,
                abis = setOf("arm64-v8a"),
            ),
            File(temp, "online-native.apk"),
        )
        val candidate = TestApkFactory.sign(
            TestApkFactory.createUnsigned(
                temp,
                "com.example.nativeapp",
                11,
                extractNativeLibs = false,
                abis = setOf("arm64-v8a"),
            ),
            File(temp, "candidate-native.apk"),
        )
        val metadata = TestApkFactory.metadata(
            File(temp, "native-metadata.json"),
            "com.example.nativeapp",
            11,
            setOf("arm64-v8a"),
        )

        val assessment = ReleaseAnalyzer.analyze(
            ReleaseRequest(
                online,
                candidate,
                metadata,
                TestApkFactory.keystoreRequest(),
            )
        )

        assertEquals(ReleaseStatus.NOT_QUALIFIED, assessment.status)
        assertTrue(assessment.findings.any { it.code == "ELF_INSPECTION_FAILED" })
        assertTrue(assessment.findings.any {
            it.code == "NATIVE_LIB_COMPRESSED_WITHOUT_EXTRACTION"
        })
    }}
