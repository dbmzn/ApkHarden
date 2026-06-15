package com.apkharden.packager.core

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

class ApkReader(file: File) : Closeable {
    private val zip = ZipFile(file)

    fun entryNames(): List<String> = zip.entries().toList().map { it.name }

    fun dexNames(): List<String> =
        entryNames().filter { it.matches(Regex("classes\\d*\\.dex")) }
            .sortedBy { name -> // classes.dex first, then classes2, classes3...
                val n = name.removePrefix("classes").removeSuffix(".dex")
                if (n.isEmpty()) 1 else n.toInt()
            }

    fun read(name: String): ByteArray {
        val e = zip.getEntry(name) ?: throw IllegalArgumentException("Missing entry: $name")
        return zip.getInputStream(e).use { it.readBytes() }
    }

    fun manifestBytes(): ByteArray = read("AndroidManifest.xml")

    override fun close() = zip.close()
}
