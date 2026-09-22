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
