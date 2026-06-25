package com.apkharden.packager.scanner

import com.apkharden.packager.scanner.model.Severity
import kotlinx.serialization.Serializable

@Serializable
data class SdkRule(
    val id: String,
    val name: String,
    val vendor: String = "",
    val category: String,
    val packages: List<String>,
    val privacy: String? = null,
    val note: String? = null,
)

@Serializable
data class ApiRule(
    val id: String,
    val title: String,
    val category: String,
    val severity: Severity,
    val methods: List<String>,
    val advice: String,
)

@Serializable
data class PermissionRule(
    val name: String,
    val title: String,
    val category: String,
    val severity: Severity,
    val advice: String,
)

/** type 取值：TARGET_SDK_MIN（看 value）/ DEBUGGABLE_FALSE / ALLOW_BACKUP_FALSE。 */
@Serializable
data class ComplianceCheck(
    val id: String,
    val type: String,
    val value: Int? = null,
    val severity: Severity,
    val advice: String,
)

@Serializable private data class SdkRules(val version: String = "", val sdks: List<SdkRule>)
@Serializable private data class ApiRules(val apis: List<ApiRule>)
@Serializable private data class PermissionRules(val permissions: List<PermissionRule>)
@Serializable private data class ComplianceRules(val checks: List<ComplianceCheck>)

internal object RuleJson {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun sdks(s: String) = json.decodeFromString<SdkRules>(s).sdks
    fun apis(s: String) = json.decodeFromString<ApiRules>(s).apis
    fun permissions(s: String) = json.decodeFromString<PermissionRules>(s).permissions
    fun checks(s: String) = json.decodeFromString<ComplianceRules>(s).checks
}
