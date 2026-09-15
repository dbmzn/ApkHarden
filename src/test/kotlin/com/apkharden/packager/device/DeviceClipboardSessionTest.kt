package com.apkharden.packager.device

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class DeviceClipboardSessionTest {
    private fun frame(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            val utf8 = text.toByteArray(Charsets.UTF_8)
            writeByte(0); writeInt(utf8.size); write(utf8)
        }
    }.toByteArray()

    @Test fun `reads fragmented UTF8 messages without losing whitespace or frame boundaries`() {
        val text = "  手机复制 👋\nhttps://example.com/?a=1&b=2\n"
        val fragmented = object : FilterInputStream(ByteArrayInputStream(frame(text) + frame("next"))) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        DataInputStream(fragmented).use {
            assertEquals(text, readClipboardMessage(it))
            assertEquals("next", readClipboardMessage(it))
        }
    }

    @Test fun `accepts empty text and maximum protocol size`() {
        for (text in listOf("", "a".repeat(CLIPBOARD_MAX_BYTES))) {
            assertEquals(text, readClipboardMessage(DataInputStream(ByteArrayInputStream(frame(text)))))
        }
    }

    @Test fun `rejects invalid length unknown type and truncated data`() {
        for (length in listOf(-1, CLIPBOARD_MAX_BYTES + 1, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream().also {
                DataOutputStream(it).apply { writeByte(0); writeInt(length) }
            }.toByteArray()
            assertThrows(IllegalStateException::class.java) {
                readClipboardMessage(DataInputStream(ByteArrayInputStream(bytes)))
            }
        }
        assertThrows(IllegalStateException::class.java) {
            readClipboardMessage(DataInputStream(ByteArrayInputStream(byteArrayOf(2))))
        }
        assertThrows(EOFException::class.java) {
            readClipboardMessage(DataInputStream(ByteArrayInputStream(frame("hello").dropLast(1).toByteArray())))
        }
    }

    /** Reads existing text only; never changes the phone or desktop clipboard or logs the text. */
    @Test
    @EnabledIfEnvironmentVariable(named = "APK_HARDEN_CLIPBOARD_TEST", matches = ".+")
    fun `connected device returns existing clipboard text`() {
        val device = AdbDeviceService.listDevices().single { it.serial == System.getenv("APK_HARDEN_CLIPBOARD_TEST") }
        repeat(2) {
            val session = DeviceClipboardSession(device)
            try {
                val deadline = System.nanoTime() + 20_000_000_000L
                while (session.state.text == null && System.nanoTime() < deadline) Thread.sleep(100)
                assertTrue(session.state.connected, session.state.message)
                assertNotNull(session.state.text, "设备没有返回文字，请先在手机复制文字后再运行")
            } finally {
                session.close()
                assertTrue(session.awaitStopped(35_000), "设备连接资源应在关闭后释放")
            }
        }
    }
}
