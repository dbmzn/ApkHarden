package com.apkharden.release.apk

import com.apkharden.release.model.ApkIdentity
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipFile

object ApkIdentityReader {
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_REQUIRED = 0x0101028e
    private const val ATTR_TEST_ONLY = 0x01010272
    private val nativeEntry = Regex("lib/([^/]+)/[^/]+\\.so")

    fun read(apk: File): ApkIdentity {
        require(apk.isFile) { "APK not found: $apk" }
        return ZipFile(apk).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalArgumentException("APK has no AndroidManifest.xml")
            val manifest = zip.getInputStream(manifestEntry).use {
                AndroidManifestBlock.load(it)
            }
            val application = manifest.applicationElement
                ?: throw IllegalArgumentException("Manifest has no application")
            val features = manifest.manifestElement
                .getElements("uses-feature").asSequence()
                .filter {
                    it.searchAttributeByResourceId(ATTR_REQUIRED)?.valueAsBoolean != false
                }
                .mapNotNull {
                    it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                }
                .toSortedSet()
            val abis = zip.entries().asSequence()
                .mapNotNull { nativeEntry.matchEntire(it.name)?.groupValues?.get(1) }
                .toSortedSet()
            ApkIdentity(
                fileName = apk.name,
                packageName = requireNotNull(manifest.packageName) {
                    "Manifest package is missing"
                },
                versionCode = requireNotNull(manifest.versionCode) {
                    "Manifest versionCode is missing"
                }.toLong(),
                versionName = manifest.versionName,
                minSdk = manifest.minSdkVersion ?: 1,
                targetSdk = manifest.targetSdkVersion ?: 1,
                debuggable = manifest.isDebuggable,
                testOnly = application
                    .searchAttributeByResourceId(ATTR_TEST_ONLY)
                    ?.valueAsBoolean == true,
                splitName = manifest.split,
                extractNativeLibs = manifest.isExtractNativeLibs,
                requiredFeatures = features,
                abis = abis,
                signature = ApkSignatureReader.read(apk),
            )
        }
    }
}
