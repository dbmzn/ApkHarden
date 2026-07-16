package com.apkharden.packager.ui.adb

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdbToolboxScreenTest {
    @Test
    fun `capture file uses Downloads and timestamped default name`() {
        val file = captureFile("screenshot", "png", LocalDateTime.of(2026, 7, 16, 15, 30, 12))

        assertEquals("ApkHarden-screenshot-20260716-153012.png", file.name)
        assertTrue(file.parentFile.absolutePath.endsWith("Downloads"))
    }

    @Test
    fun `diagnostics package uses Downloads and timestamped zip name`() {
        val file = captureFile("diagnostics", "zip", LocalDateTime.of(2026, 7, 16, 16, 45, 8))

        assertEquals("ApkHarden-diagnostics-20260716-164508.zip", file.name)
        assertTrue(file.parentFile.absolutePath.endsWith("Downloads"))
    }
}
