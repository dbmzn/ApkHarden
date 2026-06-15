package com.apkharden.packager.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRepackager {

    private val dexRegex = Regex("classes\\d*\\.dex")

    fun repackage(
        input: File,
        output: File,
        patchedManifest: ByteArray,
        shellDex: ByteArray,
        encryptedDexes: List<ByteArray>,
    ) {
        ZipFile(input).use { zin ->
            ZipOutputStream(output.outputStream()).use { zout ->
                // 1. Copy originals except dex, manifest, and old signatures.
                for (e in zin.entries()) {
                    val name = e.name
                    if (name.matches(dexRegex)) continue
                    if (name == "AndroidManifest.xml") continue
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                         name.endsWith(".EC") || name.endsWith(".SF") ||
                         name == "META-INF/MANIFEST.MF")
                    ) continue

                    // Preserve the original compression method. resources.arsc and other
                    // STORED entries must stay uncompressed (required on targetSdk 30+).
                    val copy = ZipEntry(name)
                    if (e.method == ZipEntry.STORED) {
                        copy.method = ZipEntry.STORED
                        copy.size = e.size
                        copy.compressedSize = e.size
                        copy.crc = e.crc
                    } else {
                        copy.method = ZipEntry.DEFLATED
                    }
                    zout.putNextEntry(copy)
                    zin.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
                // 2. Patched manifest.
                write(zout, "AndroidManifest.xml", patchedManifest)
                // 3. Shell becomes classes.dex.
                write(zout, "classes.dex", shellDex)
                // 4. Encrypted original dexes as assets.
                encryptedDexes.forEachIndexed { i, bytes ->
                    write(zout, Constants.encryptedDexEntry(i), bytes)
                }
            }
        }
    }

    private fun write(zout: ZipOutputStream, name: String, bytes: ByteArray) {
        zout.putNextEntry(ZipEntry(name))
        zout.write(bytes)
        zout.closeEntry()
    }
}
