package com.apkharden.release.apk

import com.apkharden.release.fixture.TestApkFactory
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApkIdentityReaderTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `reads package version SDK flags features and ABI set`() {
        val input = TestApkFactory.createUnsigned(
            directory = temp,
            packageName = "com.example.product64",
            versionCode = 120,
            minSdk = 23,
            targetSdk = 36,
            debuggable = true,
            extractNativeLibs = false,
            requiredFeatures = setOf("android.hardware.camera"),
            abis = setOf("arm64-v8a"),
        )
        val apk = TestApkFactory.sign(input, File(temp, "signed.apk"))

        val value = ApkIdentityReader.read(apk)

        assertEquals("com.example.product64", value.packageName)
        assertEquals(120L, value.versionCode)
        assertEquals(23, value.minSdk)
        assertEquals(36, value.targetSdk)
        assertTrue(value.debuggable)
        assertEquals(false, value.extractNativeLibs)
        assertEquals(setOf("android.hardware.camera"), value.requiredFeatures)
        assertEquals(setOf("arm64-v8a"), value.abis)
        assertTrue(value.signature.verified)
    }

    @Test
    fun `detects split and testOnly APK`() {
        val apk = TestApkFactory.createUnsigned(
            directory = temp,
            packageName = "com.example.split",
            versionCode = 2,
            testOnly = true,
            splitName = "config.arm64_v8a",
        )

        val value = ApkIdentityReader.read(apk)

        assertEquals("config.arm64_v8a", value.splitName)
        assertTrue(value.testOnly)
    }

    @Test
    fun `combines versionCodeMajor into long versionCode`() {
        val apk = TestApkFactory.createUnsigned(
            directory = temp,
            packageName = "com.example.longversion",
            versionCode = 120,
            versionCodeMajor = 1,
        )

        val value = ApkIdentityReader.read(apk)

        assertEquals((1L shl 32) or 120L, value.versionCode)
    }}
