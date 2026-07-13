package com.apkharden.release.fixture

import com.android.apksig.ApkSigner
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.metadata.HardenMetadata
import com.apkharden.release.model.KeystoreRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestApkFactory {
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_TEST_ONLY = 0x01010272
    private const val ATTR_REQUIRED = 0x0101028e

    fun keystoreRequest(): KeystoreRequest = KeystoreRequest(
        file = findTestKeystore(),
        storePassword = "123456".toCharArray(),
        alias = "test",
        keyPassword = "123456".toCharArray(),
    )

    fun createUnsigned(
        directory: File,
        packageName: String,
        versionCode: Int,
        versionCodeMajor: Int = 0,
        minSdk: Int = 23,
        targetSdk: Int = 36,
        debuggable: Boolean = false,
        testOnly: Boolean = false,
        splitName: String? = null,
        extractNativeLibs: Boolean? = null,
        requiredFeatures: Set<String> = emptySet(),
        abis: Set<String> = emptySet(),
    ): File {
        val manifest = AndroidManifestBlock().apply {
            this.packageName = packageName
            this.versionCode = versionCode
            if (versionCodeMajor != 0) {
                manifestElement
                    .getOrCreateAndroidAttribute("versionCodeMajor", 0x01010576)
                    .apply {
                        valueType = ValueType.DEC
                        data = versionCodeMajor
                    }
            }
            this.minSdkVersion = minSdk
            this.targetSdkVersion = targetSdk
            val app = getOrCreateApplicationElement()
            this.isDebuggable = debuggable
            if (splitName != null) setSplit(splitName, true)
            if (testOnly) {
                app.getOrCreateAndroidAttribute("testOnly", ATTR_TEST_ONLY)
                    .valueAsBoolean = true
            }
            if (extractNativeLibs != null) {
                this.isExtractNativeLibs = extractNativeLibs
            }
            for (feature in requiredFeatures) {
                manifestElement.newElement("uses-feature").apply {
                    getOrCreateAndroidAttribute("name", ATTR_NAME).valueAsString = feature
                    getOrCreateAndroidAttribute("required", ATTR_REQUIRED).valueAsBoolean = true
                }
            }
            refreshFull()
        }.bytes
        return File(directory, "$packageName-$versionCode-unsigned.apk").also { output ->
            ZipOutputStream(output.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(manifest)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(128))
                zip.closeEntry()
                for (abi in abis) {
                    zip.putNextEntry(ZipEntry("lib/$abi/libfixture.so"))
                    zip.write(ByteArray(256))
                    zip.closeEntry()
                }
            }
        }
    }

    fun metadata(
        output: File,
        applicationId: String,
        versionCode: Long,
        abis: Set<String>,
    ): File {
        val certificate = KeystoreReader.load(keystoreRequest())
            .identity
            .certificateSha256
        val value = HardenMetadata(
            schemaVersion = 1,
            pluginVersion = "1.0.0",
            runtimeVersion = "1.0.0",
            variantName = "fixture",
            applicationId = applicationId,
            versionCode = versionCode,
            minSdk = 23,
            targetSdk = 36,
            debuggable = false,
            r8Enabled = false,
            abis = abis,
            expectedCertificateSha256 = certificate,
            buildId = "fixture-build",
        )
        output.writeText(Json.encodeToString(value))
        return output
    }
    fun sign(input: File, output: File): File {
        val loaded = KeystoreReader.load(keystoreRequest())
        val config = ApkSigner.SignerConfig.Builder(
            "TEST",
            loaded.privateKey,
            loaded.certificateChain,
        ).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        return output
    }

    private fun findTestKeystore(): File = sequenceOf(
        File("../src/test/resources/test.jks"),
        File("src/test/resources/test.jks"),
    ).firstOrNull(File::isFile)
        ?: error("test.jks not found")
}
