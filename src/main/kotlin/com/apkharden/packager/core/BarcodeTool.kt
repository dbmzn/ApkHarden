package com.apkharden.packager.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

internal enum class CodeKind(val label: String, val format: BarcodeFormat, val help: String, val example: String) {
    QR("二维码", BarcodeFormat.QR_CODE, "支持文字、中文、链接和多行内容", "https://example.com"),
    CODE128("Code 128", BarcodeFormat.CODE_128, "1–80 个可打印英文字符或数字", "ApkHarden-2026"),
    EAN13("EAN-13", BarcodeFormat.EAN_13, "12 或 13 位数字；12 位时自动补齐校验位", "690123456789"),
    EAN8("EAN-8", BarcodeFormat.EAN_8, "7 或 8 位数字；7 位时自动补齐校验位", "9638507"),
    CODE39("Code 39", BarcodeFormat.CODE_39, "1–80 个大写字母、数字，或 - . 空格 $ / + %", "APK-2026"),
    ITF("ITF", BarcodeFormat.ITF, "2–80 位数字，长度必须为偶数", "1234567890"),
}
internal data class GeneratedCode(val image: BufferedImage, val png: ByteArray, val content: String, val kind: CodeKind)
internal data class DecodedCode(val format: String, val text: String)

internal object BarcodeTool {
    private const val MAX_PIXELS = 20_000_000L
    fun normalizedContent(kind: CodeKind, text: String): String {
        require(text.isNotEmpty()) { "请输入要生成的内容" }
        when (kind) {
            CodeKind.QR -> require(text.length <= 8000) { "二维码内容过长，请缩短后重试" }
            CodeKind.CODE128 -> require(text.length in 1..80 && text.all { it.code in 32..126 }) { kind.help }
            CodeKind.CODE39 -> require(text.length in 1..80 && text.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%" }) { kind.help }
            CodeKind.ITF -> require(text.length in 2..80 && text.length % 2 == 0 && text.all { it in '0'..'9' }) { kind.help }
            CodeKind.EAN13, CodeKind.EAN8 -> {
                val length = if (kind == CodeKind.EAN13) 13 else 8
                require(text.length in (length - 1)..length && text.all { it in '0'..'9' }) { kind.help }
                val payload = text.take(length - 1)
                val sum = payload.reversed().mapIndexed { index, c -> (c - '0') * if (index % 2 == 0) 3 else 1 }.sum()
                val normalized = payload + ((10 - sum % 10) % 10)
                require(text.length == length - 1 || text == normalized) { "校验位不正确；可输入前 ${length - 1} 位数字自动补齐" }
                return normalized
            }
        }
        return text
    }
    fun generate(kind: CodeKind, text: String, qrSize: Int = 512,
        centerImage: BufferedImage? = null, centerPercent: Int = 16): GeneratedCode {
        require(qrSize in 256..2048) { "二维码尺寸应为 256–2048 像素" }
        if (centerImage != null) {
            require(kind == CodeKind.QR) { "中心图片仅适用于二维码" }
            require(centerPercent in 12..20) { "中心图片比例应为 12%–20%" }
            checkSize(centerImage.width, centerImage.height)
        }
        val content = normalizedContent(kind, text)
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to if (kind == CodeKind.QR) 4 else 12)
        if (centerImage != null) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H
        val matrix = try {
            MultiFormatWriter().encode(content, kind.format, if (kind == CodeKind.QR) qrSize else 1000,
                if (kind == CodeKind.QR) qrSize else 280, hints)
        } catch (e: WriterException) { throw IllegalArgumentException("内容超出码图容量，请缩短后重试", e) }
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) image.setRGB(x, y, if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt())
        if (centerImage != null) {
            drawCenterImage(image, centerImage, centerPercent)
            val matches = try { decode(image).any { it.format == BarcodeFormat.QR_CODE.name && it.text == content } }
                catch (_: IllegalStateException) { false }
            require(matches) { "加入图片后未通过识别校验，请减小图片比例、缩短内容或移除图片后重试" }
        }
        return GeneratedCode(image, png(image), content, kind)
    }

    private fun drawCenterImage(qr: BufferedImage, logo: BufferedImage, percent: Int) {
        // The whole white backing, including its padding, fits inside the chosen proportion.
        val side = minOf(qr.width, qr.height) * percent / 100
        val padding = maxOf(2, side / 12)
        val available = side - padding * 2
        val scale = minOf(available.toDouble() / logo.width, available.toDouble() / logo.height)
        val width = maxOf(1, (logo.width * scale).toInt())
        val height = maxOf(1, (logo.height * scale).toInt())
        qr.createGraphics().let { g ->
            try {
                g.color = Color.WHITE
                g.fillRect((qr.width - side) / 2, (qr.height - side) / 2, side, side)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(logo, (qr.width - width) / 2, (qr.height - height) / 2, width, height, null)
            } finally { g.dispose() }
        }
    }
    fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }

    fun readImage(file: File): BufferedImage {
        require(file.isFile && file.length() <= 64L * 1024 * 1024) { "请选择不超过 64 MB 的图片文件" }
        return file.inputStream().use(::readImage)
    }
    fun readImage(input: InputStream): BufferedImage = ImageIO.createImageInputStream(input).use { stream ->
        requireNotNull(stream) { "无法读取图片" }
        val readers = ImageIO.getImageReaders(stream)
        require(readers.hasNext()) { "图片格式不支持，请使用 PNG、JPEG、BMP 或 GIF" }
        val reader = readers.next()
        try {
            reader.input = stream
            checkSize(reader.getWidth(0), reader.getHeight(0))
            reader.read(0)
        } finally { reader.dispose() }
    }
    private fun checkSize(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) { "图片过大，请裁剪至 2000 万像素以内" }
    }
    fun decode(image: BufferedImage): List<DecodedCode> {
        checkSize(image.width, image.height)
        // Transparent screenshots and clipboard images should be interpreted on white.
        var current = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { g -> try { g.color = Color.WHITE; g.fillRect(0, 0, width, height); g.drawImage(image, 0, 0, null) } finally { g.dispose() } }
        }
        val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)
        repeat(4) {
            val rgb = current.getRGB(0, 0, current.width, current.height, null, 0, current.width)
            val source = RGBLuminanceSource(current.width, current.height, rgb)
            val found = mutableListOf<Result>()
            for (candidate in listOf(source, source.invert())) {
                val bitmap = BinaryBitmap(HybridBinarizer(candidate))
                try { found += QRCodeMultiReader().decodeMultiple(bitmap, hints) } catch (_: ReaderException) { }
                try { found += GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap, hints) } catch (_: ReaderException) { }
            }
            if (found.isNotEmpty()) return found.map { DecodedCode(it.barcodeFormat.toString(), it.text) }.distinct()
            val rotated = BufferedImage(current.height, current.width, BufferedImage.TYPE_INT_RGB)
            rotated.createGraphics().let { g -> try { g.translate(rotated.width, 0); g.rotate(Math.PI / 2); g.drawImage(current, 0, 0, null) } finally { g.dispose() } }
            current = rotated
        }
        error("未识别到二维码或条形码，请使用清晰原图，保留码图四周空白，或裁剪后重试")
    }
}
