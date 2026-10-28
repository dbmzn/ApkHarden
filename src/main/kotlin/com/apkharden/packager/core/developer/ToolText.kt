package com.apkharden.packager.core.developer

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object ToolText {
    const val MAX_LENGTH = 500_000
    const val MAX_FILE_BYTES = 2_000_000

    fun checkSize(text: String) {
        require(text.length <= MAX_LENGTH) { "内容过大，请控制在 $MAX_LENGTH 个字符以内。" }
    }

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun utf8(text: String): ByteArray {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(bytes.remaining()).also(bytes::get)
    }

    fun read(file: File): String {
        require(file.isFile) { "请选择文本文件。" }
        val bytes = file.inputStream().use { it.readNBytes(MAX_FILE_BYTES + 1) }
        require(bytes.size <= MAX_FILE_BYTES) { "文本文件不能超过 2 MB。" }
        return utf8(bytes).also(::checkSize)
    }

    fun write(file: File, text: String) {
        val target = file.absoluteFile.toPath()
        val temporary = Files.createTempFile(target.parent, ".apkharden-text-", ".tmp")
        try {
            Files.write(temporary, utf8(text))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
