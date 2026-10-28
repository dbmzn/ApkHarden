package com.apkharden.packager.core.image

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal enum class ImageMode(val title: String) { SMART("智能压缩"), LOSSLESS("无损优化") }
internal enum class ImageQuality(val title: String, val jpeg: Int, val pngMin: Int, val pngMax: Int) {
    SMALL("体积优先", 82, 65, 85), BALANCED("均衡", 90, 80, 95), HIGH("画质优先", 95, 90, 100),
}
internal enum class ImageKind(val extension: String) { PNG("png"), JPEG("jpg") }
internal data class ImageInfo(val kind: ImageKind, val width: Int, val height: Int, val bitDepth: Int,
    val hasColorProfile: Boolean, val orientation: Int = 1)
internal data class ImageInput(val original: File, val snapshot: File, val info: ImageInfo) {
    val size: Long get() = snapshot.length()
}
internal data class ImageResult(val input: ImageInput, val file: File, val mode: ImageMode,
    val quality: ImageQuality, val note: String) {
    val size: Long get() = file.length()
    val saved: Long get() = input.size - size
    val percent: Double get() = if (input.size == 0L) 0.0 else saved * 100.0 / input.size
}

internal class ImageEngines(private val directory: File = locate()) : AutoCloseable {
    private val processes = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    companion object {
        val names = listOf("pngquant", "oxipng", "cjpeg", "djpeg", "jpegtran")
        private fun locate(): File {
            System.getenv("APK_HARDEN_IMAGE_TOOLS")?.takeIf(String::isNotBlank)?.let { return File(it) }
            System.getProperty("compose.application.resources.dir")?.let { return File(it, "image-tools") }
            val os = System.getProperty("os.name").lowercase()
            return File("build/app-resources/${if (os.startsWith("mac")) "macos" else if (os.contains("win")) "windows" else "linux"}/image-tools")
        }
    }
    fun executable(name: String): File = File(directory, name + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    fun missing(): List<String> = names.filter { !executable(it).canExecute() }
    fun run(name: String, args: List<String>, work: File, cancelled: () -> Boolean): Int {
        val binary = executable(name).absoluteFile
        check(binary.canExecute()) { "缺少本地图片引擎 $name，请安装包含图片工具的完整应用。" }
        if (cancelled()) throw CancellationException("已取消")
        val log = File(work, "$name.log")
        val process = ProcessBuilder(listOf(binary.path) + args).redirectErrorStream(true).redirectOutput(log).start()
        processes += process
        try {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已取消")
                check(System.nanoTime() < end) { "图片处理超过 120 秒，请尝试更小的图片。" }
            }
            if (cancelled()) throw CancellationException("已取消")
            val code = process.exitValue()
            check(code == 0 || (name == "pngquant" && code in listOf(98, 99))) {
                "$name 处理失败（$code），图片可能损坏或使用了不支持的编码。"
            }
            return code
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            log.delete()
            processes -= process
        }
    }
    override fun close() { processes.forEach { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) } }
}

/** All work happens on snapshots. Originals and existing export files are never overwritten. */
internal class ImageCompressionSession(val engines: ImageEngines = ImageEngines()) : AutoCloseable {
    val directory: File = Files.createTempDirectory("apkharden-images-").toFile()
    private val shutdown = Thread({ engines.close(); directory.deleteRecursively() }, "image-workspace-cleanup")
    init { Runtime.getRuntime().addShutdownHook(shutdown) }
    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        const val MAX_PIXELS = 24_000_000L
        const val MAX_SESSION_BYTES = 256L * 1024 * 1024
        const val MAX_FILES = 100
    }

    fun import(file: File): ImageInput {
        require(file.isFile) { "文件不存在或不是普通文件" }
        require(file.length() in 1..MAX_BYTES) { "单张图片须为 1 字节至 32 MB" }
        require(directory.listFiles().orEmpty().count { it.name.startsWith("input-") } < MAX_FILES) { "每批最多 100 张" }
        require(directory.listFiles().orEmpty().filter { it.name.startsWith("input-") }.sumOf(File::length) + file.length() <= MAX_SESSION_BYTES) {
            "当前批次已达到 256 MB，请清空后分批处理"
        }
        // Bound the read even if the selected file grows while being imported.
        val bytes = file.inputStream().use { it.readNBytes((MAX_BYTES + 1).toInt()) }
        require(bytes.size <= MAX_BYTES) { "单张图片不能超过 32 MB" }
        val info = inspectImage(bytes)
        val snapshot = File(directory, "input-${UUID.randomUUID()}.${info.kind.extension}")
        snapshot.writeBytes(bytes)
        return ImageInput(file.canonicalFile, snapshot, info)
    }

    fun compress(input: ImageInput, mode: ImageMode, quality: ImageQuality,
        cancelled: () -> Boolean = { false }): ImageResult {
        val work = Files.createTempDirectory(directory.toPath(), "work-").toFile()
        val output = File(directory, "result-${UUID.randomUUID()}.${input.info.kind.extension}")
        var note = if (mode == ImageMode.LOSSLESS) "无损优化，保留尺寸及色彩/方向信息" else "智能有损压缩，保留尺寸与格式"
        try {
            val candidate = File(work, "candidate.${input.info.kind.extension}")
            if (input.info.kind == ImageKind.PNG) {
                var source = input.snapshot
                if (mode == ImageMode.SMART && input.info.bitDepth <= 8 && !input.info.hasColorProfile) {
                    val quantized = File(work, "quantized.png")
                    val code = engines.run("pngquant", listOf("--quality=${quality.pngMin}-${quality.pngMax}", "--speed", "3",
                        "--output", quantized.path, "--", input.snapshot.path), work, cancelled)
                    if (code == 0) source = quantized
                    else note = "颜色量化未达到画质门槛，已回退无损优化"
                } else if (mode == ImageMode.SMART) note = "高位深或含色彩配置的 PNG，已回退无损优化"
                engines.run("oxipng", listOf("-o", "3", "--out", candidate.path, "--", source.path), work, cancelled)
            } else if (mode == ImageMode.LOSSLESS) {
                engines.run("jpegtran", listOf("-copy", "all", "-optimize", "-progressive", "-outfile", candidate.path, input.snapshot.path), work, cancelled)
            } else {
                val ppm = File(work, "decoded.ppm")
                engines.run("djpeg", listOf("-outfile", ppm.path, input.snapshot.path), work, cancelled)
                val encoded = File(work, "encoded.jpg")
                engines.run("cjpeg", listOf("-quality", quality.jpeg.toString(), "-sample", "1x1", "-progressive", "-outfile", encoded.path, ppm.path), work, cancelled)
                // Preserve EXIF/ICC. Do not copy Adobe APP14: the encoder may use a different color transform.
                candidate.writeBytes(restoreJpegMetadata(input.snapshot.readBytes(), encoded.readBytes()))
            }
            check(candidate.isFile && candidate.length() > 0) { "压缩引擎未生成有效图片" }
            if (cancelled()) throw CancellationException("已取消")
            val resultInfo = inspectImage(candidate.readBytes())
            check(resultInfo.width == input.info.width && resultInfo.height == input.info.height && resultInfo.kind == input.info.kind) {
                "结果尺寸或格式异常，未采用压缩结果"
            }
            if (candidate.length() >= input.size) {
                input.snapshot.copyTo(output)
                note = "没有更小的结果，保留原始文件"
            } else candidate.copyTo(output)
            return ImageResult(input, output, mode, quality, note)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally { work.deleteRecursively() }
    }

    fun export(result: ImageResult, folder: File): File {
        require(folder.isDirectory) { "请选择存在的输出文件夹" }
        val base = result.input.original.nameWithoutExtension
        for (index in 0..10000) {
            val suffix = if (index == 0) "" else "-$index"
            val target = File(folder, "$base-compressed$suffix.${result.input.info.kind.extension}")
            try {
                // CREATE_NEW reserves a name atomically; existing files and symlinks are not touched.
                Files.createFile(target.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) { continue }
            try {
                result.file.inputStream().use { source -> target.outputStream().use { source.copyTo(it) } }
                return target
            } catch (error: Throwable) { target.delete(); throw error }
        }
        error("同名输出过多，请更换文件夹")
    }

    fun remove(input: ImageInput, result: ImageResult?) { result?.file?.delete(); input.snapshot.delete() }
    override fun close() {
        engines.close(); directory.deleteRecursively()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }
}

private fun int32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
        ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)

internal fun inspectImage(bytes: ByteArray): ImageInfo {
    require(bytes.size <= ImageCompressionSession.MAX_BYTES) { "图片超过 32 MB" }
    val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    val jpeg = bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
    require(png || jpeg) { "仅支持静态 PNG 和 JPEG 图片" }
    var depth = 8
    var profile = false
    var orientation = 1
    if (png) {
        var position = 8
        var end = false
        while (position + 12 <= bytes.size) {
            val length = int32(bytes, position)
            require(length >= 0 && position.toLong() + length + 12 <= bytes.size) { "PNG 数据不完整" }
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            require(type != "acTL") { "暂不支持 APNG 动画，请使用静态图片" }
            if (type == "IHDR") { require(length == 13) { "PNG 头部无效" }; depth = bytes[position + 16].toInt() and 255 }
            // Keep profile-bearing or EXIF-bearing PNGs on the lossless route.
            if (type in listOf("iCCP", "cHRM", "gAMA", "eXIf")) profile = true
            position += length + 12
            if (type == "IEND") { end = true; break }
        }
        require(end) { "PNG 缺少结束标记" }
    } else {
        jpegSegments(bytes).forEach { segment ->
            require(!(segment.marker == 0xe2 && segment.bytes.size >= 8 && String(segment.bytes, 4, 4, Charsets.US_ASCII) == "MPF\u0000")) {
                "暂不支持含多幅图像或增益图的 JPEG，请先导出普通静态 JPEG"
            }
            if (segment.marker == 0xe2) profile = true
            if (segment.marker == 0xe1) orientation = exifOrientation(segment.bytes) ?: orientation
            if (segment.marker in listOf(0xc0, 0xc1, 0xc2)) {
                require(segment.bytes.size >= 10 && segment.bytes[4].toInt() == 8 && segment.bytes[9].toInt() in listOf(1, 3)) {
                    "暂不支持高位深或 CMYK JPEG"
                }
            }
        }
    }
    ImageIO.createImageInputStream(bytes.inputStream()).use { stream ->
        val readers = ImageIO.getImageReaders(stream)
        require(readers.hasNext()) { "无法读取图片" }
        val reader = readers.next()
        try {
            reader.input = stream
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= ImageCompressionSession.MAX_PIXELS) { "图片不能超过 2400 万像素" }
            return ImageInfo(if (png) ImageKind.PNG else ImageKind.JPEG, width, height, depth, profile, orientation)
        } finally { reader.dispose() }
    }
}

internal data class JpegSegment(val marker: Int, val bytes: ByteArray)
internal fun jpegSegments(bytes: ByteArray): List<JpegSegment> {
    val segments = mutableListOf<JpegSegment>()
    var offset = 2
    while (offset < bytes.size) {
        val start = offset
        require(bytes[offset] == 0xff.toByte()) { "JPEG 标记无效" }
        while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
        require(offset < bytes.size) { "JPEG 数据不完整" }
        val marker = bytes[offset++].toInt() and 255
        if (marker == 0xda || marker == 0xd9) return segments
        require(offset + 2 <= bytes.size) { "JPEG 数据不完整" }
        val length = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        require(length >= 2 && offset + length <= bytes.size) { "JPEG 段长度无效" }
        segments += JpegSegment(marker, bytes.copyOfRange(start, offset + length))
        offset += length
    }
    error("JPEG 缺少图像数据")
}

internal fun restoreJpegMetadata(original: ByteArray, encoded: ByteArray): ByteArray {
    val metadata = jpegSegments(original).filter { it.marker in 0xe1..0xed || it.marker == 0xef || it.marker == 0xfe }.flatMap { it.bytes.asIterable() }.toByteArray()
    // JFIF APP0 must remain immediately after SOI.
    val offset = 2 + jpegSegments(encoded).takeWhile { it.marker == 0xe0 }.sumOf { it.bytes.size }
    return encoded.copyOfRange(0, offset) + metadata + encoded.copyOfRange(offset, encoded.size)
}

/** Parse just IFD0 orientation; all EXIF bytes are retained verbatim during compression. */
private fun exifOrientation(segment: ByteArray): Int? = runCatching {
    if (segment.size < 18 || String(segment, 4, 6, Charsets.US_ASCII) != "Exif\u0000\u0000") return null
    val buffer = java.nio.ByteBuffer.wrap(segment, 10, segment.size - 10).slice()
    buffer.order(if (buffer.get(0) == 'I'.code.toByte()) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
    val ifd = buffer.getInt(4)
    val count = buffer.getShort(ifd).toInt() and 65535
    for (i in 0 until count.coerceAtMost(1024)) {
        val p = ifd + 2 + i * 12
        if ((buffer.getShort(p).toInt() and 65535) == 0x112) return (buffer.getShort(p + 8).toInt() and 65535).takeIf { it in 1..8 }
    }
    null
}.getOrNull()

/** Decode a bounded preview, then respect EXIF orientation for both sides. */
internal fun imagePreview(file: File, orientation: Int, maximum: Int = 1800): BufferedImage {
    ImageIO.createImageInputStream(file).use { stream ->
        val readers = ImageIO.getImageReaders(stream)
        require(readers.hasNext()) { "无法预览图片" }
        val reader = readers.next()
        try {
            reader.input = stream
            val sample = ((maxOf(reader.getWidth(0), reader.getHeight(0)) + maximum - 1) / maximum).coerceAtLeast(1)
            val image = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
            if (orientation == 1) return image
            val w = image.width; val h = image.height
            val rotated = BufferedImage(if (orientation >= 5) h else w, if (orientation >= 5) w else h, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until h) for (x in 0 until w) {
                val (dx, dy) = when (orientation) {
                    2 -> w - 1 - x to y; 3 -> w - 1 - x to h - 1 - y; 4 -> x to h - 1 - y
                    5 -> y to x; 6 -> h - 1 - y to x; 7 -> h - 1 - y to w - 1 - x; 8 -> y to w - 1 - x
                    else -> x to y
                }
                rotated.setRGB(dx, dy, image.getRGB(x, y))
            }
            return rotated
        } finally { reader.dispose() }
    }
}
