package com.apkharden.packager.core.image

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import javax.imageio.ImageIO

class ImageCompressionTest {
    @TempDir lateinit var temp: File
    @BeforeEach fun requireEngines() { assumeTrue(ImageEngines().missing().isEmpty(), "Native image engines must be prepared") }
    private fun fixture(name: String = "测试 图片.png", alpha: Boolean = true): File {
        val image = BufferedImage(900, 600, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(238, 247, 242); graphics.fillRect(0, 0, 900, 600)
        for (y in 0 until 300) for (x in 0 until 900) image.setRGB(x, y, Color(x * 255 / 900, y * 255 / 300, 180, if (alpha) (x + y) % 256 else 255).rgb)
        graphics.color = Color(15, 30, 40); graphics.font = Font("SansSerif", Font.PLAIN, 28)
        graphics.drawString("PNG / JPEG · 0123456789 · 压缩前后", 40, 400)
        graphics.dispose()
        return File(temp, name).also { ImageIO.write(image, if (name.endsWith(".jpg")) "jpeg" else "png", it) }
    }

    @Test fun `lossless PNG keeps every decoded pixel including transparent RGB`() {
        ImageCompressionSession().use { session ->
            val source = fixture()
            val before = source.readBytes()
            val input = session.import(source)
            val result = session.compress(input, ImageMode.LOSSLESS, ImageQuality.BALANCED)
            assertTrue(result.size <= input.size)
            assertPixelsEqual(ImageIO.read(source), ImageIO.read(result.file))
            assertArrayEquals(before, source.readBytes())
        }
    }

    @Test fun `smart PNG reduces a gradient fixture without changing dimensions or losing alpha`() {
        ImageCompressionSession().use { session ->
            val input = session.import(fixture())
            val result = session.compress(input, ImageMode.SMART, ImageQuality.BALANCED)
            assertTrue(result.saved > 0)
            val decoded = ImageIO.read(result.file)
            assertEquals(900, decoded.width); assertEquals(600, decoded.height)
            assertTrue(decoded.colorModel.hasAlpha())
            assertEquals(0, decoded.getRGB(0, 0).ushr(24))
            assertEquals(255, decoded.getRGB(400, 500).ushr(24))
            println("PNG gradient: ${input.size} -> ${result.size} (${result.percent}%)")
        }
    }

    @Test fun `JPEG lossless retains pixels and EXIF orientation and smart retains metadata`() {
        ImageCompressionSession().use { session ->
            val source = fixture("photo.jpg", false)
            val exif = exifSegment(6)
            source.writeBytes(source.readBytes().let { it.copyOfRange(0, 2) + exif + it.copyOfRange(2, it.size) })
            val input = session.import(source)
            assertEquals(6, input.info.orientation)
            val lossless = session.compress(input, ImageMode.LOSSLESS, ImageQuality.BALANCED)
            assertPixelsEqual(ImageIO.read(source), ImageIO.read(lossless.file))
            val smart = session.compress(input, ImageMode.SMART, ImageQuality.BALANCED)
            assertEquals(6, inspectImage(smart.file.readBytes()).orientation)
            assertArrayEquals(exif, jpegSegments(smart.file.readBytes()).single { it.marker == 0xe1 }.bytes)
            val preview = imagePreview(smart.file, 6)
            assertEquals(600, preview.width); assertEquals(900, preview.height)
        }
    }

    @Test fun `high depth PNG uses lossless route`() {
        val source = File(temp, "16bit.png")
        val image = BufferedImage(80, 60, BufferedImage.TYPE_USHORT_GRAY)
        for (y in 0 until 60) for (x in 0 until 80) image.raster.setSample(x, y, 0, (x * 601 + y * 97) % 65536)
        ImageIO.write(image, "png", source)
        ImageCompressionSession().use { session ->
            val result = session.compress(session.import(source), ImageMode.SMART, ImageQuality.SMALL)
            assertEquals(16, inspectImage(result.file.readBytes()).bitDepth)
            assertPixelsEqual(ImageIO.read(source), ImageIO.read(result.file))
        }
    }

    @Test fun `already compressed tiny input never grows`() {
        val source = File(temp, "small.png")
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", source)
        ImageCompressionSession().use { session ->
            val input = session.import(source)
            val result = session.compress(input, ImageMode.SMART, ImageQuality.HIGH)
            assertTrue(result.size <= input.size)
            if (result.saved == 0L) assertArrayEquals(source.readBytes(), result.file.readBytes())
        }
    }

    @Test fun `import snapshots source and export avoids existing files`() {
        val session = ImageCompressionSession()
        val directory = session.directory
        session.use {
            val source = fixture()
            val input = session.import(source)
            val bytes = input.snapshot.readBytes()
            source.writeText("source changed after import")
            assertArrayEquals(bytes, input.snapshot.readBytes())
            val result = session.compress(input, ImageMode.LOSSLESS, ImageQuality.BALANCED)
            val reserved = File(temp, "${source.nameWithoutExtension}-compressed.png").apply { writeText("existing file") }
            val a = session.export(result, temp); val b = session.export(result, temp)
            assertNotEquals(a, b); assertNotEquals(source, a)
            assertEquals("existing file", reserved.readText())
            assertArrayEquals(result.file.readBytes(), a.readBytes())
            assertEquals("source changed after import", source.readText())
        }
        assertFalse(directory.exists())
    }

    @Test fun `reject animation unsupported files truncated data and excessive pixels`() {
        assertThrows(IllegalArgumentException::class.java) { inspectImage("GIF89a".toByteArray()) }
        val valid = fixture().readBytes()
        assertThrows(IllegalArgumentException::class.java) { inspectImage(valid.copyOf(25)) }
        val apng = valid.copyOfRange(0, 33) + pngChunk("acTL", ByteArray(8)) + valid.copyOfRange(33, valid.size)
        assertThrows(IllegalArgumentException::class.java) { inspectImage(apng) }
        val oversized = valid.clone()
        ByteBuffer.wrap(oversized).putInt(16, 100000).putInt(20, 100000)
        assertThrows(IllegalArgumentException::class.java) { inspectImage(oversized) }
        val jpg = fixture("multi.jpg", false).readBytes()
        val multiPicture = jpg.copyOfRange(0, 2) + byteArrayOf(-1, -30, 0, 6, 77, 80, 70, 0) + jpg.copyOfRange(2, jpg.size)
        assertThrows(IllegalArgumentException::class.java) { inspectImage(multiPicture) }
    }

    @Test fun `JPEG metadata restore retains JFIF first and excludes old Adobe transform`() {
        val jpg = fixture("metadata.jpg", false).readBytes()
        val adobe = byteArrayOf(-1, -18, 0, 14) + "Adobe".toByteArray() + ByteArray(7)
        val tagged = jpg.copyOfRange(0, 2) + adobe + exifSegment(8) + jpg.copyOfRange(2, jpg.size)
        val restored = jpegSegments(restoreJpegMetadata(tagged, jpg))
        assertEquals(0xe0, restored.first().marker)
        assertTrue(restored.none { it.marker == 0xee })
        assertTrue(restored.any { it.marker == 0xe1 })
    }

    @Test fun `cancellation cleans temporary files and preserves input`() {
        ImageCompressionSession().use { session ->
            val input = session.import(fixture())
            assertThrows(CancellationException::class.java) { session.compress(input, ImageMode.SMART, ImageQuality.HIGH) { true } }
            assertTrue(input.snapshot.isFile)
            assertTrue(session.directory.listFiles()!!.none { it.name.startsWith("work-") || it.name.startsWith("result-") })
        }
    }

    @Test fun `cancellation interrupts an active native process`() {
        ImageCompressionSession().use { session ->
            val input = session.import(fixture())
            val checks = java.util.concurrent.atomic.AtomicInteger()
            assertThrows(CancellationException::class.java) {
                session.compress(input, ImageMode.SMART, ImageQuality.HIGH) { checks.incrementAndGet() > 1 }
            }
            assertTrue(checks.get() >= 2)
            assertTrue(input.snapshot.isFile)
            assertTrue(session.directory.listFiles()!!.none { it.name.startsWith("work-") })
        }
    }

    @Test fun `missing engine gives actionable failure without corrupting files`() {
        ImageCompressionSession(ImageEngines(File(temp, "missing"))).use { session ->
            val input = session.import(fixture())
            val failure = assertThrows(IllegalStateException::class.java) { session.compress(input, ImageMode.LOSSLESS, ImageQuality.BALANCED) }
            assertTrue(failure.message!!.contains("缺少本地图片引擎"))
            assertTrue(input.original.isFile)
        }
    }

    @Test fun `benchmark screenshots gradient and upstream photo with unchanged dimensions`() {
        assumeTrue(File("build/image-tools-build/mozjpeg/testimages/vgl_5674_0098.bmp").isFile, "Requires macOS source build fixtures")
        val folder = File("build/image-compression-report").apply { mkdirs() }
        val gradient = fixture("gradient.png").copyTo(File(folder, "gradient.png"), overwrite = true)
        val screenshot = File(folder, "screenshot.png")
        ImageIO.write(ImageIO.read(File("docs/screenshots/developer-json.jpg")), "png", screenshot)
        val photo = File(folder, "photo.jpg")
        val image = ImageIO.read(File("build/image-tools-build/mozjpeg/testimages/vgl_5674_0098.bmp"))
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(photo).use { out ->
            writer.output = out
            val param = writer.defaultWriteParam.apply { compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.98f }
            writer.write(null, javax.imageio.IIOImage(image, null, null), param)
        }
        writer.dispose()
        val lines = mutableListOf("| Sample | Mode | Before | After | Saved |", "|---|---|---:|---:|---:|")
        ImageCompressionSession().use { session ->
            for (file in listOf(gradient, screenshot, photo)) {
                val input = session.import(file)
                for (mode in ImageMode.entries) {
                    val result = session.compress(input, mode, ImageQuality.BALANCED)
                    val target = File(folder, "${file.nameWithoutExtension}-${mode.name.lowercase()}.${input.info.kind.extension}")
                    result.file.copyTo(target, overwrite = true)
                    lines += "| ${file.name} | ${mode.title} | ${input.size} | ${result.size} | ${"%.1f".format(result.percent)}% |"
                    if (mode == ImageMode.LOSSLESS) assertPixelsEqual(ImageIO.read(input.snapshot), ImageIO.read(result.file))
                    assertTrue(result.size <= input.size)
                }
            }
        }
        File(folder, "results.md").writeText(lines.joinToString("\n") + "\n")
        println(lines.joinToString("\n"))
    }

    private fun assertPixelsEqual(a: BufferedImage, b: BufferedImage) {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
    }
    private fun exifSegment(orientation: Int): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write("Exif\u0000\u0000".toByteArray())
            write(ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8)
                putShort(1); putShort(0x112); putShort(3); putInt(1); putShort(orientation.toShort()); putShort(0); putInt(0)
            }.array())
        }.toByteArray()
        return byteArrayOf(-1, -31, 0, (payload.size + 2).toByte()) + payload
    }
    private fun pngChunk(type: String, bytes: ByteArray): ByteArray {
        val body = type.toByteArray() + bytes
        return ByteBuffer.allocate(body.size + 8).putInt(bytes.size).put(body).putInt(CRC32().apply { update(body) }.value.toInt()).array()
    }
}
