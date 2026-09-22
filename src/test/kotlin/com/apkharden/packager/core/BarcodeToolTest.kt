package com.apkharden.packager.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.apkharden.packager.ui.adb.screenshotTransferable
import com.apkharden.packager.ui.barcode.clipboardImage
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.datatransfer.StringSelection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BarcodeToolTest {
    @Test fun `center logo survives PNG export and decoding at every offered size and proportion`() {
        val logo = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().let { g -> try {
                g.color = Color(25, 130, 230); g.fillRect(0, 0, 120, 80)
                g.color = Color.WHITE; g.fillRect(25, 30, 70, 20)
            } finally { g.dispose() } }
        }
        for (size in listOf(256, 512, 1024)) for (percent in listOf(12, 16, 20)) {
            val text = "https://example.com/?name=中心图片&size=$size"
            val result = BarcodeTool.generate(CodeKind.QR, text, size, logo, percent)
            assertTrue(BarcodeTool.decode(BarcodeTool.readImage(result.png.inputStream())).contains(DecodedCode("QR_CODE", text)))
            assertEquals(size, result.image.width)
        }
    }
    @Test fun `center images preserve aspect ratio transparency and source pixels`() {
        val wide = BufferedImage(200, 50, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().let { g -> try { g.color = Color.RED; g.fillRect(0, 0, 200, 50) } finally { g.dispose() } }
        }
        val result = BarcodeTool.generate(CodeKind.QR, "https://example.com", centerImage = wide)
        assertEquals(Color.RED.rgb, result.image.getRGB(256, 256))
        assertEquals(Color.WHITE.rgb, result.image.getRGB(256, 280))
        assertEquals(Color.RED.rgb, wide.getRGB(100, 25))
        val transparent = BufferedImage(40, 100, BufferedImage.TYPE_INT_ARGB)
        val clear = BarcodeTool.generate(CodeKind.QR, "透明图片测试", centerImage = transparent)
        assertEquals(Color.WHITE.rgb, clear.image.getRGB(256, 256))
        assertEquals("透明图片测试", BarcodeTool.decode(clear.image).single().text)
    }
    @Test fun `logo options cannot apply to linear barcodes or cover arbitrary portions`() {
        val logo = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
        assertThrows(IllegalArgumentException::class.java) { BarcodeTool.generate(CodeKind.CODE128, "ABC", centerImage = logo) }
        for (percent in listOf(0, 11, 21, 100)) {
            assertThrows(IllegalArgumentException::class.java) { BarcodeTool.generate(CodeKind.QR, "a", centerImage = logo, centerPercent = percent) }
        }
        assertArrayEquals(BarcodeTool.generate(CodeKind.QR, "plain").png,
            BarcodeTool.generate(CodeKind.QR, "plain", centerImage = null, centerPercent = 20).png)
    }
    @Test fun `every offered format generates a decodable PNG with exact normalized content`() {
        for (kind in CodeKind.entries) {
            val text = if (kind == CodeKind.QR) " 中文 / 文件 👋\nhttps://example.com/?a=1&b=2 " else kind.example
            val code = BarcodeTool.generate(kind, text)
            val fromPng = BarcodeTool.readImage(code.png.inputStream())
            assertTrue(BarcodeTool.decode(fromPng).contains(DecodedCode(kind.format.name, code.content)), kind.name)
        }
    }
    @Test fun `EAN fills checksum and rejects incorrect checksum or invalid alphabets`() {
        assertEquals("4006381333931", BarcodeTool.normalizedContent(CodeKind.EAN13, "400638133393"))
        assertEquals("96385074", BarcodeTool.normalizedContent(CodeKind.EAN8, "9638507"))
        listOf(CodeKind.EAN13 to "4006381333932", CodeKind.EAN8 to "96385075", CodeKind.CODE128 to "中文",
            CodeKind.CODE39 to "lowercase", CodeKind.ITF to "123", CodeKind.ITF to "12ab", CodeKind.QR to "").forEach { (kind, text) ->
            assertThrows(IllegalArgumentException::class.java) { BarcodeTool.generate(kind, text) }
        }
    }
    @Test fun `recognizes rotated inverted barcode`() {
        val source = BarcodeTool.generate(CodeKind.CODE128, "Rotation-123").image
        val rotated = BufferedImage(source.height, source.width, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) for (x in 0 until source.width) rotated.setRGB(source.height - 1 - y, x, source.getRGB(x, y) xor 0x00ffffff)
        assertEquals("Rotation-123", BarcodeTool.decode(rotated).first().text)
    }
    @Test fun `multiple QR codes are returned without duplicate results`() {
        val left = BarcodeTool.generate(CodeKind.QR, "first", 256).image
        val right = BarcodeTool.generate(CodeKind.QR, "第二个", 256).image
        val combined = BufferedImage(620, 320, BufferedImage.TYPE_INT_RGB)
        combined.createGraphics().let { g -> try {
            g.color = Color.WHITE; g.fillRect(0, 0, 620, 320)
            g.drawImage(left, 20, 30, null); g.drawImage(right, 340, 30, null)
        } finally { g.dispose() } }
        assertEquals(setOf("first", "第二个"), BarcodeTool.decode(combined).map { it.text }.toSet())
    }
    @Test fun `also reads common two dimensional formats not offered by generator`() {
        for (format in listOf(BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417)) {
            val matrix = MultiFormatWriter().encode("Test-123", format, 500, 300)
            val input = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) input.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            assertTrue(BarcodeTool.decode(input).any { it.text == "Test-123" }, format.name)
        }
    }
    @Test fun `clipboard PNG and native image flavors both decode`() {
        val generated = BarcodeTool.generate(CodeKind.QR, "clipboard test")
        for (mac in listOf(true, false)) {
            val read = clipboardImage(screenshotTransferable(generated.png, mac))
            assertEquals("clipboard test", BarcodeTool.decode(read).first().text)
        }
        assertThrows(IllegalStateException::class.java) { clipboardImage(StringSelection("plain text")) }
    }
    @Test fun `blank images and invalid image bytes are rejected with useful errors`() {
        assertThrows(IllegalStateException::class.java) { BarcodeTool.decode(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)) }
        assertThrows(IllegalArgumentException::class.java) { BarcodeTool.readImage("not an image".byteInputStream()) }
    }
}
