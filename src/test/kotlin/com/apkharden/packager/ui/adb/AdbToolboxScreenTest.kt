package com.apkharden.packager.ui.adb

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdbToolboxScreenTest {
    @Test
    fun `capture tools have the highest toolbox priority`() {
        assertEquals(AdbTab.CAPTURE, AdbTab.entries.first())
        assertEquals(AdbTab.CAPTURE, DEFAULT_ADB_TAB)
    }

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

    @Test
    fun `rendered screenshot includes red rectangle and arrow annotations`() {
        val original = BufferedImage(120, 100, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().let { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.dispose()
            }
        }.toPng()

        val rendered = renderAnnotatedScreenshot(
            original,
            listOf(
                ScreenshotAnnotation.Rectangle(AnnotationPoint(10f, 12f), AnnotationPoint(60f, 55f)),
                ScreenshotAnnotation.Arrow(AnnotationPoint(20f, 80f), AnnotationPoint(95f, 30f)),
            ),
        )
        val image = ImageIO.read(ByteArrayInputStream(rendered))

        assertEquals(120, image.width)
        assertEquals(100, image.height)
        assertTrue(image.countRedPixels() > 100, "合成后的 PNG 应包含可见的红色标注")
    }

    @Test
    fun `render without annotations preserves original png bytes`() {
        val original = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB).toPng()

        assertTrue(renderAnnotatedScreenshot(original, emptyList()).contentEquals(original))
    }

    private fun BufferedImage.toPng(): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(this, "png", output)
        output.toByteArray()
    }

    private fun BufferedImage.countRedPixels(): Int = (0 until width).sumOf { x ->
        (0 until height).count { y ->
            val color = Color(getRGB(x, y), true)
            color.red > 200 && color.green < 80 && color.blue < 80
        }
    }
}
