package com.apkharden.packager.core.image

import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.color.ColorSpace
import java.awt.color.ICC_ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.awt.image.ColorConvertOp
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import kotlin.math.*

internal enum class ImageOutput(val title: String, val kind: ImageKind?) { KEEP("保持原格式", null), PNG("PNG", ImageKind.PNG), JPEG("JPEG", ImageKind.JPEG), WEBP("WebP", ImageKind.WEBP) }
internal enum class ResizeMode(val title: String) { ORIGINAL("保持原尺寸"), FIT("等比适配"), EXACT("指定宽高（拉伸）"), PERCENT("百分比") }
internal data class CropRegion(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class ImageEditSettings(val output: ImageOutput = ImageOutput.KEEP, val resize: ResizeMode = ResizeMode.ORIGINAL,
    val width: Int? = 1280, val height: Int? = 1280, val percent: Int = 100, val enlarge: Boolean = false, val background: Int = 0xffffff) {
    val active: Boolean get() = output != ImageOutput.KEEP || resize != ResizeMode.ORIGINAL
}
internal val ImageInfo.displayWidth: Int get() = if (orientation in 5..8) height else width
internal val ImageInfo.displayHeight: Int get() = if (orientation in 5..8) width else height
internal data class ImageEditPlan(val crop: CropRegion, val width: Int, val height: Int, val kind: ImageKind, val changed: Boolean)

internal fun planImageEdit(info: ImageInfo, settings: ImageEditSettings, crop: CropRegion?): ImageEditPlan {
    val full = CropRegion(0, 0, info.displayWidth, info.displayHeight)
    val region = crop ?: full
    require(region.x >= 0 && region.y >= 0 && region.width > 0 && region.height > 0 &&
        region.x.toLong() + region.width <= full.width && region.y.toLong() + region.height <= full.height) { "裁剪范围超出图片边界" }
    fun dimension(value: Int?) = require(value == null || value in 1..32768) { "目标宽高须为 1–32768 像素" }
    val size = when (settings.resize) {
        ResizeMode.ORIGINAL -> region.width to region.height
        ResizeMode.EXACT -> {
            dimension(settings.width); dimension(settings.height)
            require(settings.width != null && settings.height != null) { "指定宽高需同时填写宽度和高度" }
            settings.width to settings.height
        }
        ResizeMode.FIT -> {
            dimension(settings.width); dimension(settings.height)
            require(settings.width != null || settings.height != null) { "等比适配至少填写一个目标边长" }
            var ratio = min(settings.width?.toDouble()?.div(region.width) ?: Double.POSITIVE_INFINITY,
                settings.height?.toDouble()?.div(region.height) ?: Double.POSITIVE_INFINITY)
            if (!settings.enlarge) ratio = min(1.0, ratio)
            max(1, (region.width * ratio).roundToInt()) to max(1, (region.height * ratio).roundToInt())
        }
        ResizeMode.PERCENT -> {
            require(settings.percent in 1..1000) { "缩放比例须为 1%–1000%" }
            max(1, (region.width * (settings.percent / 100.0)).roundToInt()) to max(1, (region.height * (settings.percent / 100.0)).roundToInt())
        }
    }
    val (width, height) = size
    require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= ImageCompressionSession.MAX_PIXELS) { "输出不能超过 2400 万像素或单边 32768 像素" }
    val kind = settings.output.kind ?: info.kind
    require(kind != ImageKind.WEBP || (width <= 16383 && height <= 16383)) { "WebP 的单边不能超过 16383 像素" }
    return ImageEditPlan(region, width, height, kind, region != full || width != full.width || height != full.height || kind != info.kind)
}

internal fun transformImage(session: ImageCompressionSession, input: ImageInput, mode: ImageMode, quality: ImageQuality,
    settings: ImageEditSettings, crop: CropRegion?, cancelled: () -> Boolean): ImageResult {
    val plan = planImageEdit(input.info, settings, crop)
    if (!plan.changed) return session.compress(input, mode, quality, cancelled)
    val work = Files.createTempDirectory(session.directory.toPath(), "work-edit-").toFile()
    val output = File(session.directory, "result-${UUID.randomUUID()}.${plan.kind.extension}")
    fun checkCancel() { if (cancelled()) throw CancellationException("已取消") }
    try {
        checkCancel()
        val original = imagePreview(input.snapshot, input.info.orientation, Int.MAX_VALUE, session.engines, cancelled)
        check(original.width == input.info.displayWidth && original.height == input.info.displayHeight) { "解码尺寸与图片头部不一致" }
        val region = plan.crop
        val selected = original.getSubimage(region.x, region.y, region.width, region.height)
        val pixels = resizeImage(selected, plan.width, plan.height, cancelled)
        checkCancel()
        when (plan.kind) {
            ImageKind.JPEG -> {
                val ppm = File(work, "pixels.ppm")
                ppm.outputStream().buffered().use { stream ->
                    stream.write("P6\n${pixels.width} ${pixels.height}\n255\n".toByteArray(Charsets.US_ASCII))
                    val row = ByteArray(pixels.width * 3)
                    for (y in 0 until pixels.height) {
                        checkCancel()
                        val colors = pixels.getRGB(0, y, pixels.width, 1, null, 0, pixels.width)
                        for (x in colors.indices) {
                            val a = colors[x].ushr(24)
                            for (c in 0..2) {
                                val shift = (2 - c) * 8
                                row[x * 3 + c] = ((((colors[x].ushr(shift) and 255) * a + (settings.background.ushr(shift) and 255) * (255 - a)) + 127) / 255).toByte()
                            }
                        }
                        stream.write(row)
                    }
                }
                session.engines.run("cjpeg", listOf("-quality", quality.jpeg.toString(), "-sample", "1x1", "-progressive", "-outfile", output.path, ppm.path), work, cancelled)
            }
            ImageKind.PNG, ImageKind.WEBP -> {
                val canonical = File(work, "pixels.png")
                check(ImageIO.write(pixels, "png", canonical)) { "无法编码 PNG" }
                checkCancel()
                if (plan.kind == ImageKind.WEBP) {
                    session.engines.run("cwebp", webpEncodeArgs(mode, quality) + listOf("-o", output.path, "--", canonical.path), work, cancelled)
                } else {
                    val decodedInput = ImageInput(input.original, canonical, ImageInfo(ImageKind.PNG, plan.width, plan.height, 8, false))
                    val optimized = session.compress(decodedInput, mode, quality, cancelled)
                    try { optimized.file.copyTo(output) } finally { optimized.file.delete() }
                }
            }
        }
        checkCancel()
        check(output.length() in 1..ImageCompressionSession.MAX_RESULT_BYTES) { "结果超过 128 MB，请减小输出尺寸" }
        val info = inspectImage(output.readBytes())
        check(info.kind == plan.kind && info.width == plan.width && info.height == plan.height && info.orientation == 1) { "处理结果的尺寸、格式或方向异常" }
        val note = "${if (crop != null) "裁剪 → " else ""}${plan.width} × ${plan.height} · ${plan.kind.name} · " +
            if (plan.kind == ImageKind.JPEG) "已按背景色合成，JPEG 重新编码（有损）" else "已重新编码为 8 位 sRGB，保留透明度"
        return ImageResult(input, output, mode, quality, note, info, true)
    } catch (error: Throwable) { output.delete(); throw error }
    finally { work.deleteRecursively() }
}

internal fun webpEncodeArgs(mode: ImageMode, quality: ImageQuality): List<String> =
    if (mode == ImageMode.LOSSLESS) listOf("-lossless", "-exact", "-q", "100", "-m", "6")
    else listOf("-q", quality.jpeg.toString(), "-m", "6", "-alpha_q", "100", "-exact")

/** Repeated downsampling and premultiplied alpha avoid dark halos around transparent edges. */
internal fun resizeImage(source: BufferedImage, width: Int, height: Int, cancelled: () -> Boolean): BufferedImage {
    if (source.width == width && source.height == height) {
        val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            if (cancelled()) throw CancellationException("已取消")
            copy.setRGB(0, y, width, 1, source.getRGB(0, y, width, 1, null, 0, width), 0, width)
        }
        return copy
    }
    var current = source
    do {
        if (cancelled()) throw CancellationException("已取消")
        val w = if (current.width > width * 2) max(width, current.width / 2) else width
        val h = if (current.height > height * 2) max(height, current.height / 2) else height
        val next = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        next.createGraphics().let { g ->
            try {
                g.composite = AlphaComposite.Src
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(current, 0, 0, w, h, null)
            } finally { g.dispose() }
        }
        current = next
    } while (current.width != width || current.height != height)
    return current
}

/** ImageIO handles JPEG ICC profiles. PNG/WebP metadata requires explicit color conversion. */
internal fun normalizeImageColor(image: BufferedImage, bytes: ByteArray, kind: ImageKind): BufferedImage {
    if (kind == ImageKind.JPEG) return image
    val chunks = if (kind == ImageKind.PNG) pngChunks(bytes) else webpChunks(bytes)
    val profileBytes = if (kind == ImageKind.WEBP) chunks.firstOrNull { it.name == "ICCP" }?.data else {
        chunks.firstOrNull { it.name == "iCCP" }?.data?.let { data ->
            val zero = data.indexOf(0)
            require(zero >= 1 && zero + 2 < data.size && data[zero + 1].toInt() == 0) { "PNG 色彩配置无效" }
            java.util.zip.InflaterInputStream(data.copyOfRange(zero + 2, data.size).inputStream()).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        }
    }
    if (profileBytes != null) {
        require(profileBytes.size <= 4 * 1024 * 1024) { "色彩配置超过 4 MB" }
        val profile = ICC_Profile.getInstance(profileBytes)
        require(profile.numComponents == image.colorModel.numColorComponents) { "色彩配置与图片通道不匹配" }
        // The BufferedImage overload uses its ColorModel's profile, which ImageIO does not
        // replace with PNG/WebP ICC. Supply raw component rasters to honor the embedded profile.
        val count = profile.numComponents
        require(count in listOf(1, 3)) { "只支持灰度或 RGB 色彩配置" }
        val samples = java.awt.image.Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_BYTE, image.width, image.height, count, null)
        for (y in 0 until image.height) {
            val row = IntArray(image.width * count)
            val rgb = image.getRGB(0, y, image.width, 1, null, 0, image.width)
            for (x in 0 until image.width) {
                if (count == 1) row[x] = (image.raster.getSample(x, y, 0) * 255L / ((1L shl image.raster.sampleModel.getSampleSize(0)) - 1)).toInt()
                else for (c in 0..2) row[x * 3 + c] = rgb[x].ushr((2 - c) * 8) and 255
            }
            samples.setPixels(0, y, image.width, 1, row)
        }
        val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val rgbOutput = result.raster.createWritableChild(0, 0, image.width, image.height, 0, 0, intArrayOf(0, 1, 2))
        ColorConvertOp(ICC_ColorSpace(profile), ColorSpace.getInstance(ColorSpace.CS_sRGB), null).filter(samples, rgbOutput)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            result.setRGB(x, y, (result.getRGB(x, y) and 0xffffff) or (image.getRGB(x, y) and -0x1000000))
        }
        return result
    }
    if (kind == ImageKind.PNG && chunks.none { it.name == "sRGB" }) {
        chunks.firstOrNull { it.name == "cHRM" }?.data?.let { data ->
            require(data.size == 32) { "PNG 色度配置无效" }
            val buffer = java.nio.ByteBuffer.wrap(data)
            val values = List(8) { buffer.int }
            require(values == listOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)) {
                "此 PNG 使用非标准色度且没有 ICC，暂不支持预览或编辑；可继续无损优化"
            }
        }
        chunks.firstOrNull { it.name == "gAMA" }?.data?.let { data ->
            require(data.size == 4) { "PNG Gamma 无效" }
            val gamma = java.nio.ByteBuffer.wrap(data).int / 100000.0
            require(gamma > 0) { "PNG Gamma 无效" }
            if (abs(gamma - 0.45455) > 0.0001) {
                val lut = IntArray(256) { c ->
                    val linear = (c / 255.0).pow(1 / gamma)
                    ((if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1 / 2.4) - 0.055) * 255).roundToInt().coerceIn(0, 255)
                }
                val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val c = image.getRGB(x, y)
                    result.setRGB(x, y, (c and -0x1000000) or (lut[c.ushr(16) and 255] shl 16) or (lut[c.ushr(8) and 255] shl 8) or lut[c and 255])
                }
                return result
            }
        }
    }
    return image
}
