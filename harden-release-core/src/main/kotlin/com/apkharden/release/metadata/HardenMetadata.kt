package com.apkharden.release.metadata

import kotlinx.serialization.Serializable

@Serializable
data class HardenMetadata(
    val schemaVersion: Int,
    val pluginVersion: String,
    val runtimeVersion: String,
    val variantName: String,
    val applicationId: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val r8Enabled: Boolean,
    val abis: Set<String>,
    val expectedCertificateSha256: String,
    val buildId: String,
)
