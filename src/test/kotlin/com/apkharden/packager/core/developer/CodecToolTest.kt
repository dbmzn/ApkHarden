package com.apkharden.packager.core.developer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class CodecToolTest {
    @Test fun `all reversible codecs round trip Chinese emoji slashes and newlines`() {
        val text = "中文😀\n+ &<\"'> / \\u4e2d"
        for (format in CodecFormat.entries.filter { it != CodecFormat.JWT }) {
            val encoded = CodecTool.convert(text, format, true).output
            assertEquals(text, CodecTool.convert(encoded, format, false).output, format.title)
        }
    }
    @Test fun `URL component and form URL treat plus and space differently`() {
        assertEquals("a%20b%2Bc", CodecTool.convert("a b+c", CodecFormat.URL, true).output)
        assertEquals("a+b%2Bc", CodecTool.convert("a b+c", CodecFormat.FORM, true).output)
        assertEquals("a+b", CodecTool.convert("a+b", CodecFormat.URL, false).output)
        assertEquals("a b", CodecTool.convert("a+b", CodecFormat.FORM, false).output)
    }
    @Test fun `bad escapes and non UTF8 binary are not silently replaced`() {
        for (text in listOf("%", "%GG", "%FF", "%E4x%BD%A0", "%+1")) {
            assertThrows(Exception::class.java) { CodecTool.convert(text, CodecFormat.URL, false) }
        }
        assertThrows(Exception::class.java) { CodecTool.convert("/w==", CodecFormat.BASE64, false) }
        assertThrows(Exception::class.java) { CodecTool.convert("\\uD800", CodecFormat.UNICODE, false) }
        assertThrows(IllegalArgumentException::class.java) { CodecTool.convert("\\u+001", CodecFormat.UNICODE, false) }
        assertThrows(IllegalArgumentException::class.java) { CodecTool.convert("{}", CodecFormat.JSON_STRING, false) }
    }
    @Test fun `HTML numeric entities handle supplementary code points without double decoding`() {
        assertEquals("😀 © &lt;", CodecTool.convert("&#x1F600; &copy; &amp;lt;", CodecFormat.HTML, false).output)
        assertThrows(IllegalArgumentException::class.java) { CodecTool.convert("&#x110000;", CodecFormat.HTML, false) }
    }
    @Test fun `JWT expiry is in seconds with a clear unverified status`() {
        fun part(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val token = part("{\"alg\":\"none\"}") + "." + part("{\"sub\":\"demo\",\"exp\":1700000000.5}") + "."
        val result = CodecTool.convert(token, CodecFormat.JWT, false, Instant.ofEpochSecond(1700000001))
        assertTrue(result.detail!!.contains("未验证签名"))
        assertTrue(result.detail.contains("已过期"))
        assertTrue(result.detail.contains("2023-11-14T22:13:20.500Z"))
        assertTrue(result.output.contains("demo"))
        assertThrows(IllegalArgumentException::class.java) { CodecTool.convert("not-a-token", CodecFormat.JWT, false) }
    }
}
