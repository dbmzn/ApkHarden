package com.apkharden.release.metadata

import java.io.File
import kotlinx.serialization.json.Json

object HardenMetadataReader {
    private val json = Json {
        ignoreUnknownKeys = false
    }

    fun read(file: File): HardenMetadata {
        require(file.isFile) { "Metadata file not found: $file" }
        val value = json.decodeFromString<HardenMetadata>(file.readText())
        require(value.schemaVersion == 1) {
            "Unsupported metadata schema: ${value.schemaVersion}"
        }
        require(value.pluginVersion.isNotBlank()) { "Metadata pluginVersion is blank" }
        require(value.runtimeVersion.isNotBlank()) { "Metadata runtimeVersion is blank" }
        require(value.variantName.isNotBlank()) { "Metadata variantName is blank" }
        require(value.applicationId.isNotBlank()) { "Metadata applicationId is blank" }
        require(value.versionCode >= 0) { "Metadata versionCode is negative" }
        require(value.minSdk > 0) { "Metadata minSdk is invalid" }
        require(value.targetSdk > 0) { "Metadata targetSdk is invalid" }
        require(value.buildId.isNotBlank()) { "Metadata buildId is blank" }
        require(value.expectedCertificateSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Metadata certificate SHA-256 is malformed"
        }
        return value.copy(
            expectedCertificateSha256 = value.expectedCertificateSha256.lowercase(),
            abis = value.abis.toSortedSet(),
        )
    }
}
