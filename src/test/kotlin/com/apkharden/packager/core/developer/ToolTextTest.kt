package com.apkharden.packager.core.developer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ToolTextTest {
    @TempDir lateinit var directory: Path
    @Test fun `UTF8 file roundtrip and output replacement leave no temporary files`() {
        val file = directory.resolve("示例.txt").toFile()
        file.writeText("old")
        ToolText.write(file, "中文😀\nnew")
        assertEquals("中文😀\nnew", ToolText.read(file))
        assertEquals(listOf("示例.txt"), directory.toFile().listFiles()!!.map { it.name })
    }
    @Test fun `oversized files and malformed UTF8 are rejected`() {
        val file = directory.resolve("invalid.txt").toFile()
        file.writeBytes(byteArrayOf(0xFF.toByte()))
        assertThrows(Exception::class.java) { ToolText.read(file) }
        file.writeBytes(ByteArray(ToolText.MAX_FILE_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { ToolText.read(file) }
    }
}
