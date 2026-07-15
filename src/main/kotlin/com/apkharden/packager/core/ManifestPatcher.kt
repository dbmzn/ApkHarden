package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement

object ManifestPatcher {

    private const val ID_name = 0x01010003   // android:name
    private const val ID_exported = 0x01010010 // android:exported
    private const val ID_process = 0x01010011 // android:process
    private const val ID_authorities = 0x01010018 // android:authorities
    private const val ID_value = 0x01010024  // android:value

    fun readApplicationClass(manifestBytes: ByteArray): String? {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        return m.applicationClassName
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
     * Adds a static guard provider without replacing the business Application.
     *
     * One provider is installed in the main process and one in every explicitly declared app
     * process. This keeps signature and anti-debug checks active when a remote service/provider is
     * the first component launched. The injected class lives in a normal classesN.dex entry.
     */
    fun patchGuard(
        manifestBytes: ByteArray,
        sigHash: String,
    ): ByteArray {
        require(sigHash.matches(Regex("[0-9a-fA-F]{64}"))) {
            "sigHash must contain 64 hexadecimal characters"
        }
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        val packageName = requireNotNull(m.packageName).also {
            require(it.isNotBlank()) { "Manifest package name is blank" }
        }
        val app = m.applicationElement
            ?: throw IllegalStateException("Manifest has no <application> element")
        val existingProviders = m.listApplicationElementsByTag("provider")
        require(existingProviders.none { provider ->
            provider.searchAttributeByResourceId(ID_name)?.valueAsString == Constants.GUARD_PROVIDER
        }) { "APK is already protected by ${Constants.GUARD_PROVIDER}" }
        require(Constants.META_SIG_HASH !in readMetaData(manifestBytes)) {
            "APK already contains ${Constants.META_SIG_HASH}"
        }

        addMeta(app, Constants.META_SIG_HASH, sigHash.lowercase())

        val processes = COMPONENT_TAGS
            .flatMap { tag -> m.listApplicationElementsByTag(tag) }
            .mapNotNull { element ->
                element.searchAttributeByResourceId(ID_process)?.valueAsString?.takeIf(String::isNotBlank)
            }
            .distinct()
            .sorted()

        addGuardProvider(app, packageName, process = null, suffix = "main")
        processes.forEachIndexed { index, process ->
            addGuardProvider(app, packageName, process, "p${index + 1}")
        }

        m.refreshFull()
        return m.bytes
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
