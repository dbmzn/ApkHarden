package com.apkharden.packager.core.developer

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Base64

internal enum class CodecFormat(val title: String, val hint: String) {
    URL("URL 组件", "用于单个路径或参数值；解码时保留 +。"),
    FORM("表单 URL", "application/x-www-form-urlencoded；空格与 + 相互转换。"),
    BASE64("Base64", "UTF-8 文本与标准 Base64；支持带换行的输入。"),
    BASE64_URL("Base64 URL", "适合 URL 的 Base64URL，编码不带填充等号。"),
    UNICODE("Unicode", "中文和 Emoji 与 \\uXXXX 转义互转。"),
    HTML("HTML 实体", "常用命名实体及十进制、十六进制数字实体。"),
    JSON_STRING("JSON 字符串", "转义生成带引号的 JSON 字符串；还原时包含外层引号。"),
    JWT("JWT 解析", "查看 Header、Payload 和 exp；仅解析，不验证签名。"),
}
internal data class CodecResult(val output: String, val detail: String? = null)

internal object CodecTool {
    private val hex = "0123456789ABCDEF"
    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–")

    fun convert(input: String, format: CodecFormat, encode: Boolean, now: Instant = Instant.now()): CodecResult {
        ToolText.checkSize(input)
        if (format == CodecFormat.JWT) return jwt(input.trim(), now)
        val output = when (format) {
            CodecFormat.URL, CodecFormat.FORM -> if (encode) percentEncode(input, format == CodecFormat.FORM) else percentDecode(input, format == CodecFormat.FORM)
            CodecFormat.BASE64 -> if (encode) Base64.getEncoder().encodeToString(ToolText.utf8(input)) else ToolText.utf8(Base64.getDecoder().decode(input.filterNot(Char::isWhitespace)))
            CodecFormat.BASE64_URL -> if (encode) Base64.getUrlEncoder().withoutPadding().encodeToString(ToolText.utf8(input)) else ToolText.utf8(Base64.getUrlDecoder().decode(input.filterNot(Char::isWhitespace)))
            CodecFormat.UNICODE -> if (encode) unicodeEncode(input) else unicodeDecode(input)
            CodecFormat.HTML -> if (encode) htmlEncode(input) else htmlDecode(input)
            CodecFormat.JSON_STRING -> if (encode) JsonPrimitive(input).toString() else {
                val value = JsonTool.parse(input)
                require(value is JsonPrimitive && value.isString) { "请输入带外层引号的 JSON 字符串。" }
                value.content
            }
            CodecFormat.JWT -> error("Handled above")
        }
        require(output.length <= 2_000_000) { "转换结果超过 200 万字符，请缩小输入。" }
        return CodecResult(output)
    }

    private fun percentEncode(input: String, form: Boolean): String = buildString {
        for (byte in ToolText.utf8(input)) {
            val n = byte.toInt() and 255
            val c = n.toChar()
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._" || c == (if (form) '*' else '~') -> append(c)
                form && c == ' ' -> append('+')
                else -> { append('%'); append(hex[n shr 4]); append(hex[n and 15]) }
            }
        }
    }

    private fun percentDecode(input: String, form: Boolean): String = buildString {
        var i = 0
        while (i < input.length) {
            when {
                input[i] == '%' -> {
                    val bytes = ByteArrayOutputStream()
                    while (i < input.length && input[i] == '%') {
                        require(i + 2 < input.length) { "第 ${i + 1} 个字符处的 % 转义不完整。" }
                        val digits = input.substring(i + 1, i + 3)
                        val n = digits.takeIf { it.all { c -> c in "0123456789abcdefABCDEF" } }?.toIntOrNull(16)
                            ?: throw IllegalArgumentException("第 ${i + 1} 个字符处不是有效的 %XX 转义。")
                        bytes.write(n); i += 3
                    }
                    append(ToolText.utf8(bytes.toByteArray()))
                }
                form && input[i] == '+' -> { append(' '); i++ }
                else -> append(input[i++])
            }
        }
    }

    private fun unicodeEncode(input: String): String {
        ToolText.utf8(input)
        return buildString {
            input.forEach { c ->
                if (c.code !in 32..126 || c == '\\') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
    }

    private fun unicodeDecode(input: String): String = buildString {
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && input.getOrNull(i + 1) == 'u') {
                require(i + 6 <= input.length) { "Unicode 转义必须包含四位十六进制数字。" }
                val digits = input.substring(i + 2, i + 6)
                val n = digits.takeIf { it.all { c -> c in "0123456789abcdefABCDEF" } }?.toIntOrNull(16)
                    ?: throw IllegalArgumentException("无效的 Unicode 转义：${input.substring(i, i + 6)}")
                append(n.toChar()); i += 6
            } else append(input[i++])
        }
    }.also { ToolText.utf8(it) }

    private fun htmlEncode(input: String): String = buildString {
        input.forEach { append(when (it) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it.toString() }) }
    }
    private fun htmlDecode(input: String): String = Regex("&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[A-Za-z][A-Za-z0-9]+);").replace(input) { match ->
        val entity = match.groupValues[1]
        if (!entity.startsWith('#')) entities[entity] ?: match.value else {
            val code = if (entity.startsWith("#x", true)) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
            require(code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) { "HTML 数字实体不是有效 Unicode 字符：${match.value}" }
            String(Character.toChars(code))
        }
    }

    private fun jwt(token: String, now: Instant): CodecResult {
        val parts = token.split('.')
        require(parts.size == 3 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) { "请输入三段式 JWT：Header.Payload.Signature。" }
        fun decode(part: String): JsonObject {
            val value = JsonTool.parse(ToolText.utf8(Base64.getUrlDecoder().decode(part)))
            require(value is JsonObject) { "JWT Header 和 Payload 必须是 JSON 对象。" }
            return value
        }
        val header = decode(parts[0]); val payload = decode(parts[1])
        val exp = payload["exp"]?.let { value ->
            require(value is JsonPrimitive && !value.isString && value != JsonNull) { "JWT exp 必须是数字秒数。" }
            val seconds = value.content.toBigDecimalOrNull() ?: throw IllegalArgumentException("JWT exp 必须是数字秒数。")
            val whole = seconds.setScale(0, RoundingMode.FLOOR)
            Instant.ofEpochSecond(whole.longValueExact(), seconds.subtract(whole).multiply(BigDecimal(1_000_000_000)).toLong())
        }
        val detail = if (exp == null) "仅解析，未验证签名；Payload 未提供 exp。" else
            "仅解析，未验证签名；exp = $exp（UTC），${if (!now.isBefore(exp)) "已过期" else "尚未过期"}。"
        return CodecResult(JsonTool.render(buildJsonObject { put("header", header); put("payload", payload) }), detail)
    }
}
