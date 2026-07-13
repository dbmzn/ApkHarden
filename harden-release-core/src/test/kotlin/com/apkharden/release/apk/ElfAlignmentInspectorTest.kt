package com.apkharden.release.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElfAlignmentInspectorTest {
    @Test
    fun `ELF64 LOAD with 16KB alignment and congruent addresses passes`() {
        assertTrue(ElfAlignmentInspector.inspect(elf64(16384, 0, 0)).compatible16k)
    }

    @Test
    fun `ELF64 LOAD with 4KB alignment fails`() {
        assertFalse(ElfAlignmentInspector.inspect(elf64(4096, 0, 0)).compatible16k)
    }

    @Test
    fun `ELF64 incongruent file and virtual offsets fail`() {
        assertFalse(ElfAlignmentInspector.inspect(elf64(16384, 4096, 0)).compatible16k)
    }

    @Test
    fun `ELF32 LOAD with 16KB alignment passes`() {
        assertTrue(ElfAlignmentInspector.inspect(elf32(16384, 0, 0)).compatible16k)
    }

    private fun elf64(alignment: Long, fileOffset: Long, virtualAddress: Long): ByteArray {
        val data = ByteBuffer.allocate(64 + 56).order(ByteOrder.LITTLE_ENDIAN)
        putIdent(data, elfClass = 2)
        data.putLong(32, 64)
        data.putShort(54, 56)
        data.putShort(56, 1)
        data.putInt(64, 1)
        data.putLong(72, fileOffset)
        data.putLong(80, virtualAddress)
        data.putLong(112, alignment)
        return data.array()
    }

    private fun elf32(alignment: Int, fileOffset: Int, virtualAddress: Int): ByteArray {
        val data = ByteBuffer.allocate(52 + 32).order(ByteOrder.LITTLE_ENDIAN)
        putIdent(data, elfClass = 1)
        data.putInt(28, 52)
        data.putShort(42, 32)
        data.putShort(44, 1)
        data.putInt(52, 1)
        data.putInt(56, fileOffset)
        data.putInt(60, virtualAddress)
        data.putInt(80, alignment)
        return data.array()
    }

    private fun putIdent(buffer: ByteBuffer, elfClass: Int) {
        buffer.put(0, 0x7f.toByte())
        buffer.put(1, 'E'.code.toByte())
        buffer.put(2, 'L'.code.toByte())
        buffer.put(3, 'F'.code.toByte())
        buffer.put(4, elfClass.toByte())
        buffer.put(5, 1.toByte())
    }
}
