package com.apkharden.release.fixture

import com.android.apksig.ApkSigner
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.model.KeystoreRequest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestApkFactory {
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
    ): File {
        val manifest = AndroidManifestBlock().apply {
            this.packageName = packageName
            this.versionCode = versionCode
            this.minSdkVersion = 23
            this.targetSdkVersion = 36
            getOrCreateApplicationElement()
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
            }
        }
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
