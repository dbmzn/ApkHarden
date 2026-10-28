package com.apkharden.packager.core.image

internal data class ImageChunk(val name: String, val data: ByteArray)
private fun le(bytes: ByteArray, offset: Int, count: Int): Long = (0 until count).fold(0L) { value, i ->
    value or ((bytes[offset + i].toLong() and 255) shl (8 * i))
}
internal fun isWebp(bytes: ByteArray): Boolean = bytes.size >= 12 &&
    String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

internal fun webpChunks(bytes: ByteArray): List<ImageChunk> {
    require(isWebp(bytes) && le(bytes, 4, 4) + 8 == bytes.size.toLong()) { "WebP 容器长度无效" }
    val chunks = mutableListOf<ImageChunk>()
    var p = 12
    while (p + 8 <= bytes.size) {
        require(chunks.size < 4096) { "WebP 分块数量过多" }
        val name = String(bytes, p, 4, Charsets.US_ASCII)
        val size = le(bytes, p + 4, 4)
        val end = p.toLong() + 8 + size + (size and 1)
        require(end <= bytes.size) { "WebP 数据不完整" }
        require(name != "ANIM" && name != "ANMF") { "暂不支持动画 WebP，请选择静态图片" }
        chunks += ImageChunk(name, bytes.copyOfRange(p + 8, p + 8 + size.toInt()))
        p = end.toInt()
    }
    require(p == bytes.size) { "WebP 尾部数据无效" }
    return chunks
}

internal fun inspectWebp(bytes: ByteArray): ImageInfo {
    val chunks = webpChunks(bytes)
    val extended = chunks.singleOrNull { it.name == "VP8X" }?.data
    val canvas = extended?.let {
        require(it.size == 10 && (it[0].toInt() and 2) == 0) { "暂不支持动画或无效 WebP" }
        (le(it, 4, 3).toInt() + 1) to (le(it, 7, 3).toInt() + 1)
    }
    val pixels = chunks.filter { it.name == "VP8 " || it.name == "VP8L" }
    require(pixels.size == 1) { "WebP 必须包含一幅静态图像" }
    val chunk = pixels.single(); val data = chunk.data
    val dimensions = if (chunk.name == "VP8L") {
        require(data.size >= 5 && data[0] == 0x2f.toByte()) { "无效的无损 WebP" }
        val bits = le(data, 1, 4)
        require(bits shr 29 == 0L) { "不支持此 WebP 版本" }
        ((bits and 0x3fff).toInt() + 1) to (((bits shr 14) and 0x3fff).toInt() + 1)
    } else {
        require(data.size >= 10 && data[0].toInt() and 1 == 0 &&
            data.copyOfRange(3, 6).contentEquals(byteArrayOf(0x9d.toByte(), 1, 0x2a))) { "无效的有损 WebP" }
        (le(data, 6, 2).toInt() and 0x3fff) to (le(data, 8, 2).toInt() and 0x3fff)
    }
    require(canvas == null || canvas == dimensions) { "WebP 画布与图像尺寸不一致" }
    val (width, height) = dimensions
    require(width > 0 && height > 0 && width.toLong() * height <= ImageCompressionSession.MAX_PIXELS) { "图片不能超过 2400 万像素" }
    return ImageInfo(ImageKind.WEBP, width, height, 8, chunks.any { it.name == "ICCP" },
        chunks.firstOrNull { it.name == "EXIF" }?.data?.let(::metadataOrientation) ?: 1)
}

internal fun pngChunks(bytes: ByteArray): List<ImageChunk> {
    val chunks = mutableListOf<ImageChunk>()
    var p = 8
    while (p + 12 <= bytes.size) {
        val size = java.nio.ByteBuffer.wrap(bytes, p, 4).int
        require(size >= 0 && p.toLong() + size + 12 <= bytes.size) { "PNG 数据不完整" }
        val name = String(bytes, p + 4, 4, Charsets.US_ASCII)
        // Only metadata is needed; do not duplicate all compressed pixel data.
        if (name in listOf("iCCP", "gAMA", "cHRM", "eXIf", "sRGB")) chunks += ImageChunk(name, bytes.copyOfRange(p + 8, p + 8 + size))
        require(chunks.size <= 256) { "PNG 色彩与方向配置过多" }
        p += size + 12
        if (name == "IEND") break
    }
    return chunks
}

/** TIFF orientation, with or without the six-byte Exif prefix. */
internal fun metadataOrientation(bytes: ByteArray): Int? = runCatching {
    val offset = if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "Exif\u0000\u0000") 6 else 0
    val buffer = java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset).slice()
    require(buffer.get(0) == buffer.get(1) && buffer.get(0) in listOf('I'.code.toByte(), 'M'.code.toByte()))
    buffer.order(if (buffer.get(0) == 'I'.code.toByte()) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
    require(buffer.getShort(2).toInt() == 42)
    val ifd = buffer.getInt(4)
    val count = buffer.getShort(ifd).toInt() and 65535
    for (i in 0 until count.coerceAtMost(1024)) {
        val p = ifd + 2 + i * 12
        if ((buffer.getShort(p).toInt() and 65535) == 0x112 && buffer.getShort(p + 2).toInt() == 3 && buffer.getInt(p + 4) == 1) {
            return (buffer.getShort(p + 8).toInt() and 65535).takeIf { it in 1..8 }
        }
    }
    null
}.getOrNull()
