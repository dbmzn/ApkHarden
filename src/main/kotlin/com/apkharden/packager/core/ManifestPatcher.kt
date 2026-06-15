package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement

object ManifestPatcher {

    private const val ID_name = 0x01010003   // android:name
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

    fun patch(
        manifestBytes: ByteArray,
        originalAppClass: String?,
        sigHash: String,
        dexCount: Int,
    ): ByteArray {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        m.applicationClassName = Constants.PROXY_APPLICATION

        val app = m.applicationElement
            ?: throw IllegalStateException("Manifest has no <application> element")

        addMeta(app, Constants.META_APP_NAME, originalAppClass ?: "")
        addMeta(app, Constants.META_SIG_HASH, sigHash)
        addMeta(app, Constants.META_DEX_COUNT, dexCount.toString())

        m.refreshFull()
        return m.bytes
    }

    private fun addMeta(app: ResXmlElement, name: String, value: String) {
        val meta = app.newElement("meta-data")
        meta.getOrCreateAndroidAttribute("name", ID_name).valueAsString = name
        meta.getOrCreateAndroidAttribute("value", ID_value).valueAsString = value
    }
}
