package com.apkharden.packager.scanner

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock

data class ManifestInfo(
    val packageName: String?,
    val versionName: String?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val debuggable: Boolean?,
    val allowBackup: Boolean?,
)

object ManifestReader {
    private const val ID_debuggable = 0x0101000f
    private const val ID_allowBackup = 0x01010280

    fun parse(bytes: ByteArray): ManifestInfo {
        val m = AndroidManifestBlock.load(bytes.inputStream())
        val app = m.applicationElement
        fun boolAttr(id: Int): Boolean? = app?.searchAttributeByResourceId(id)?.valueAsBoolean
        return ManifestInfo(
            packageName = m.packageName,
            versionName = m.versionName,
            targetSdk = m.targetSdkVersion,
            permissions = m.usesPermissions.toList(),
            debuggable = boolAttr(ID_debuggable),
            allowBackup = boolAttr(ID_allowBackup),
        )
    }
}
