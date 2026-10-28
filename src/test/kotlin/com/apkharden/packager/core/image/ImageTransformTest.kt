package com.apkharden.packager.core.image

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

class ImageTransformTest {
    @TempDir lateinit var directory: File
    @BeforeEach fun engines() { assumeTrue(ImageEngines().missing().isEmpty()) }
    private fun png(name: String = "source.png"): File {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 80) for (x in 0 until 120) image.setRGB(x, y,
            if (x < 40) Color(240, 30, 20, 255).rgb else if (x < 80) Color(20, 220, 40, 128).rgb else Color(30, 40, 220, 0).rgb)
        return File(directory, name).also { ImageIO.write(image, "png", it) }
    }
    private fun process(session: ImageCompressionSession, input: ImageInput, output: ImageOutput,
        resize: ResizeMode = ResizeMode.ORIGINAL, width: Int? = 1280, height: Int? = 1280,
        crop: CropRegion? = null, background: Int = 0xffffff): ImageResult =
        session.process(input, ImageMode.LOSSLESS, ImageQuality.HIGH, ImageEditSettings(output, resize, width, height, background = background), crop)

    @Test fun `all six conversion directions emit matching magic and extension`() {
        ImageCompressionSession().use { session ->
            val original = session.import(png())
            val jpg = process(session, original, ImageOutput.JPEG)
            val webp = process(session, original, ImageOutput.WEBP)
            val sources = listOf(original, session.import(jpg.file), session.import(webp.file))
            for (input in sources) for (format in listOf(ImageOutput.PNG, ImageOutput.JPEG, ImageOutput.WEBP)) {
                if (input.info.kind == format.kind) continue
                val result = process(session, input, format)
                assertEquals(format.kind, inspectImage(result.file.readBytes()).kind)
                assertEquals(120, result.info.width); assertEquals(80, result.info.height)
                assertEquals(format.kind!!.extension, session.export(result, directory).extension)
                assertTrue(result.transformed)
                assertEquals(120, imagePreview(result.file, result.info.orientation).width)
            }
        }
    }

    @Test fun `lossless WebP preserves RGBA and alpha round trip into PNG`() {
        ImageCompressionSession().use { session ->
            val input = session.import(png())
            val webp = process(session, input, ImageOutput.WEBP)
            assertPixels(ImageIO.read(input.snapshot), imagePreview(webp.file, 1))
            val restored = process(session, session.import(webp.file), ImageOutput.PNG)
            assertPixels(ImageIO.read(input.snapshot), ImageIO.read(restored.file))
            val compressed = session.compress(session.import(webp.file), ImageMode.LOSSLESS, ImageQuality.BALANCED)
            assertPixels(imagePreview(webp.file, 1), imagePreview(compressed.file, 1))
        }
    }

    @Test fun `transparent JPEG conversion uses explicit background`() {
        ImageCompressionSession().use { session ->
            val input = session.import(png())
            val white = ImageIO.read(process(session, input, ImageOutput.JPEG).file)
            val black = ImageIO.read(process(session, input, ImageOutput.JPEG, background = 0).file)
            val w = Color(white.getRGB(110, 40)); val b = Color(black.getRGB(110, 40))
            assertTrue(w.red > 248 && w.green > 248 && w.blue > 248)
            assertTrue(b.red < 7 && b.green < 7 && b.blue < 7)
            val half = Color(white.getRGB(60, 40))
            assertTrue(half.red in 130..145 && half.green in 230..245)
        }
    }

    @Test fun `crop and resize use selected region and retain edited results even when larger`() {
        ImageCompressionSession().use { session ->
            val input = session.import(png())
            val crop = process(session, input, ImageOutput.PNG, crop = CropRegion(40, 10, 30, 50))
            assertEquals(30, crop.info.width); assertEquals(50, crop.info.height)
            assertEquals(ImageIO.read(input.snapshot).getRGB(50, 20), ImageIO.read(crop.file).getRGB(10, 10))
            val enlarged = process(session, input, ImageOutput.PNG, ResizeMode.EXACT, 1200, 800)
            assertEquals(1200, enlarged.info.width); assertEquals(800, enlarged.info.height)
            assertTrue(enlarged.size > input.size)
            assertTrue(enlarged.transformed)
        }
    }

    @Test fun `resize plans preserve aspect respect no enlargement and reject overflow`() {
        val info = ImageInfo(ImageKind.PNG, 1200, 800, 8, false)
        val fit = planImageEdit(info, ImageEditSettings(resize = ResizeMode.FIT, width = 600, height = 600), null)
        assertEquals(600 to 400, fit.width to fit.height)
        val single = planImageEdit(info, ImageEditSettings(resize = ResizeMode.FIT, width = null, height = 200), null)
        assertEquals(300 to 200, single.width to single.height)
        assertFalse(planImageEdit(info, ImageEditSettings(resize = ResizeMode.FIT, width = 2400, height = 1600), null).changed)
        val doubled = planImageEdit(info, ImageEditSettings(resize = ResizeMode.FIT, width = 2400, height = 1600, enlarge = true), null)
        assertEquals(2400 to 1600, doubled.width to doubled.height)
        val percent = planImageEdit(info, ImageEditSettings(resize = ResizeMode.PERCENT, percent = 25), CropRegion(10, 10, 400, 200))
        assertEquals(100 to 50, percent.width to percent.height)
        assertThrows(IllegalArgumentException::class.java) { planImageEdit(info, ImageEditSettings(resize = ResizeMode.EXACT, width = 0), null) }
        assertThrows(IllegalArgumentException::class.java) { planImageEdit(info, ImageEditSettings(resize = ResizeMode.EXACT, width = 24000, height = 24000), null) }
        assertThrows(IllegalArgumentException::class.java) { planImageEdit(info, ImageEditSettings(), CropRegion(Int.MAX_VALUE, 0, 20, 20)) }
        assertThrows(IllegalArgumentException::class.java) { planImageEdit(info, ImageEditSettings(output = ImageOutput.WEBP, resize = ResizeMode.EXACT, width = 17000, height = 1), null) }
    }

    @Test fun `rotated JPEG crop follows displayed coordinates and removes obsolete EXIF`() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); g.color = Color.RED; g.fillRect(0, 0, 60, 80); g.color = Color.BLUE; g.fillRect(60, 0, 60, 80); g.dispose()
        val jpg = File(directory, "rotated.jpg").also { ImageIO.write(image, "jpeg", it) }
        val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8); putShort(1)
            putShort(0x112); putShort(3); putInt(1); putShort(6); putShort(0); putInt(0)
        }.array()
        val payload = "Exif\u0000\u0000".toByteArray() + tiff
        jpg.writeBytes(jpg.readBytes().let { it.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (payload.size + 2).toByte()) + payload + it.copyOfRange(2, it.size) })
        ImageCompressionSession().use { session ->
            val input = session.import(jpg)
            val result = process(session, input, ImageOutput.PNG, crop = CropRegion(0, 0, 80, 50))
            assertEquals(80 to 50, result.info.width to result.info.height)
            assertEquals(1, result.info.orientation)
            val color = Color(ImageIO.read(result.file).getRGB(40, 25))
            assertTrue(color.red > 240 && color.blue < 15)
        }
    }

    @Test fun `reject animated WebP oversized headers and truncated chunks`() {
        ImageCompressionSession().use { session ->
            val result = process(session, session.import(png()), ImageOutput.WEBP)
            val bytes = result.file.readBytes()
            assertThrows(IllegalArgumentException::class.java) { inspectImage(bytes.copyOf(bytes.size - 1)) }
            val animated = bytes + "ANIM".toByteArray() + ByteArray(4)
            ByteBuffer.wrap(animated).order(ByteOrder.LITTLE_ENDIAN).putInt(4, animated.size - 8)
            assertThrows(IllegalArgumentException::class.java) { inspectImage(animated) }
        }
    }

    @Test fun `editing normalizes embedded PNG ICC into sRGB`() {
        val png = png()
        val profile = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).data
        val compressed = java.io.ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use { it.write(profile) } }.toByteArray()
        val chunk = pngChunk("iCCP", "linear".toByteArray() + byteArrayOf(0, 0) + compressed)
        val bytes = png.readBytes(); png.writeBytes(bytes.copyOfRange(0, 33) + chunk + bytes.copyOfRange(33, bytes.size))
        ImageCompressionSession().use { session ->
            val input = session.import(png)
            val result = process(session, input, ImageOutput.PNG, crop = CropRegion(0, 0, 30, 50))
            val c = Color(ImageIO.read(result.file).getRGB(10, 10))
            assertTrue(c.green > 80, "Linear RGB green 30 should be converted to sRGB")
            assertFalse(result.info.hasColorProfile)
        }
    }

    @Test fun `WebP ICC and orientation are honored when converting`() {
        ImageCompressionSession().use { session ->
            val encoded = process(session, session.import(png()), ImageOutput.WEBP)
            val vp8 = webpChunks(encoded.file.readBytes()).single { it.name == "VP8L" || it.name == "VP8 " }
            val canvas = byteArrayOf(0x38, 0, 0, 0, 119, 0, 0, 79, 0, 0)
            val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8); putShort(1)
                putShort(0x112); putShort(3); putInt(1); putShort(6); putShort(0); putInt(0)
            }.array()
            val chunks = listOf(ImageChunk("VP8X", canvas), ImageChunk("ICCP", ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).data), vp8, ImageChunk("EXIF", tiff))
            val body = java.io.ByteArrayOutputStream().apply {
                write("WEBP".toByteArray())
                chunks.forEach { chunk ->
                    write(chunk.name.toByteArray()); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunk.data.size).array()); write(chunk.data)
                    if (chunk.data.size % 2 == 1) write(0)
                }
            }.toByteArray()
            val source = File(directory, "profile-oriented.webp")
            source.writeBytes("RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body)
            val input = session.import(source)
            assertEquals(6, input.info.orientation)
            val converted = process(session, input, ImageOutput.PNG)
            assertEquals(80 to 120, converted.info.width to converted.info.height)
            assertEquals(1, converted.info.orientation)
            val color = Color(ImageIO.read(converted.file).getRGB(40, 20))
            assertTrue(color.green > 80)
        }
    }

    @Test fun `cancelled edits leave no result or work files and preserve original`() {
        ImageCompressionSession().use { session ->
            val input = session.import(png()); val before = input.original.readBytes()
            assertThrows(CancellationException::class.java) { session.process(input, ImageMode.LOSSLESS, ImageQuality.HIGH, ImageEditSettings(output = ImageOutput.WEBP), cancelled = { true }) }
            assertTrue(session.directory.listFiles()!!.none { it.name.startsWith("result-") || it.name.startsWith("work-") })
            assertArrayEquals(before, input.original.readBytes())
        }
    }

    private fun assertPixels(a: BufferedImage, b: BufferedImage) {
        assertEquals(a.width to a.height, b.width to b.height)
        assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
    }
    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray() + data
        return ByteBuffer.allocate(body.size + 8).putInt(data.size).put(body).putInt(CRC32().apply { update(body) }.value.toInt()).array()
    }
}
