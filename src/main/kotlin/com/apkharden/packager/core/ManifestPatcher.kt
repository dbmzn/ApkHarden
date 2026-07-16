package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement

object ManifestPatcher {

    private const val ID_name = 0x01010003   // android:name
    private const val ID_exported = 0x01010010 // android:exported
    private const val ID_process = 0x01010011 // android:process
    private const val ID_authorities = 0x01010018 // android:authorities
    private const val ID_value = 0x01010024  // android:value
    private const val ID_appComponentFactory = 0x0101057a // android:appComponentFactory

    fun readApplicationClass(manifestBytes: ByteArray): String? {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        return m.applicationClassName
    }

    fun readApplicationComponentFactory(manifestBytes: ByteArray): String? {
        val manifest = AndroidManifestBlock.load(manifestBytes.inputStream())
        return manifest.applicationElement
            ?.searchAttributeByResourceId(ID_appComponentFactory)
            ?.valueAsString
            ?.takeIf(String::isNotBlank)
    }

    fun readMetaData(manifestBytes: ByteArray): Map<String, String> {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        val out = LinkedHashMap<String, String>()
        for (child in m.listApplicationElementsByTag("meta-data")) {
            val name = child.searchAttributeByResourceId(ID_name)?.valueAsString ?: continue
            val value = child.searchAttributeByResourceId(ID_value)?.valueAsString ?: ""
            out[name] = value
        }
        return out
    }

    /**
     * Replaces the public APK entry points with the encrypted-DEX shell while retaining the
     * original Application and AppComponentFactory names for runtime delegation.
     */
    fun patchEncryptedShell(
        manifestBytes: ByteArray,
        sigHash: String,
        dexCount: Int,
    ): PatchedShellManifest {
        require(sigHash.matches(Regex("[0-9a-fA-F]{64}"))) {
            "sigHash must contain 64 hexadecimal characters"
        }
        require(dexCount > 0) { "Encrypted shell requires at least one business DEX" }
        val originalApplication = readApplicationClass(manifestBytes).orEmpty()
        val originalFactory = readApplicationComponentFactory(manifestBytes).orEmpty()
        require(originalApplication != Constants.SHELL_APPLICATION) {
            "APK is already protected by ${Constants.SHELL_APPLICATION}"
        }
        val existingMetadata = readMetaData(manifestBytes)
        require(Constants.META_DEX_COUNT !in existingMetadata) {
            "APK already contains ${Constants.META_DEX_COUNT}"
        }

        val manifest = AndroidManifestBlock.load(manifestBytes.inputStream())
        val packageName = requireNotNull(manifest.packageName).also {
            require(it.isNotBlank()) { "Manifest package name is blank" }
        }
        val app = manifest.applicationElement
            ?: throw IllegalStateException("Manifest has no <application> element")
        manifest.applicationClassName = Constants.SHELL_APPLICATION
        app.getOrCreateAndroidAttribute("appComponentFactory", ID_appComponentFactory).valueAsString =
            Constants.SHELL_COMPONENT_FACTORY

        addMeta(app, Constants.META_ORIGINAL_APPLICATION, originalApplication)
        addMeta(app, Constants.META_ORIGINAL_COMPONENT_FACTORY, originalFactory)
        addMeta(app, Constants.META_SIG_HASH, sigHash.lowercase())
        addMeta(app, Constants.META_DEX_COUNT, dexCount.toString())
        addGuardProviders(manifest, app, packageName)

        manifest.refreshFull()
        return PatchedShellManifest(manifest.bytes, originalApplication, originalFactory)
    }

    fun guardProcesses(manifestBytes: ByteArray): Set<String> {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        return m.listApplicationElementsByTag("provider")
            .filter { provider ->
                provider.searchAttributeByResourceId(ID_name)?.valueAsString == Constants.GUARD_PROVIDER
            }
            .map { provider ->
                provider.searchAttributeByResourceId(ID_process)?.valueAsString.orEmpty()
            }
            .toSet()
    }

    private fun addGuardProviders(
        manifest: AndroidManifestBlock,
        app: ResXmlElement,
        packageName: String,
    ) {
        val existingProviders = manifest.listApplicationElementsByTag("provider")
        require(existingProviders.none { provider ->
            provider.searchAttributeByResourceId(ID_name)?.valueAsString == Constants.GUARD_PROVIDER
        }) { "APK is already protected by ${Constants.GUARD_PROVIDER}" }
        val processes = COMPONENT_TAGS
            .flatMap { tag -> manifest.listApplicationElementsByTag(tag) }
            .mapNotNull { element ->
                element.searchAttributeByResourceId(ID_process)?.valueAsString?.takeIf(String::isNotBlank)
            }
            .distinct()
            .sorted()
        addGuardProvider(app, packageName, process = null, suffix = "main")
        processes.forEachIndexed { index, process ->
            addGuardProvider(app, packageName, process, "p${index + 1}")
        }
    }

    private fun addGuardProvider(
        app: ResXmlElement,
        packageName: String,
        process: String?,
        suffix: String,
    ) {
        val provider = app.newElement("provider")
        provider.getOrCreateAndroidAttribute("name", ID_name).valueAsString = Constants.GUARD_PROVIDER
        provider.getOrCreateAndroidAttribute("authorities", ID_authorities).valueAsString =
            "$packageName.apkharden.guard.$suffix"
        provider.getOrCreateAndroidAttribute("exported", ID_exported).valueAsBoolean = false
        if (process != null) {
            provider.getOrCreateAndroidAttribute("process", ID_process).valueAsString = process
        }
    }

    private fun addMeta(app: ResXmlElement, name: String, value: String) {
        val meta = app.newElement("meta-data")
        meta.getOrCreateAndroidAttribute("name", ID_name).valueAsString = name
        meta.getOrCreateAndroidAttribute("value", ID_value).valueAsString = value
    }

    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")
}

data class PatchedShellManifest(
    val bytes: ByteArray,
    val originalApplication: String,
    val originalComponentFactory: String,
)
