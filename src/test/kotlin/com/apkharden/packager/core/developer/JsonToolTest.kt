package com.apkharden.packager.core.developer

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonToolTest {
    @Test fun `formatting retains large IDs decimals and escaped string content`() {
        val input = """{"id":9223372036854775808,"amount":0.12345678901234567890,"text":"中文\n😀"}"""
        assertEquals(input, JsonTool.format(JsonTool.format(input), false))
        assertEquals("9223372036854775808", JsonTool.query(input, "$.id"))
        assertEquals("0.12345678901234567890", JsonTool.query(input, "$.amount"))
    }

    @Test fun `jsonpath supports arrays filters recursion and scalar results`() {
        val input = """{"items":[{"name":"A","price":5},{"name":"B","price":12}],"other":{"name":"C"}}"""
        assertEquals("[\"A\",\"B\"]", JsonTool.format(JsonTool.query(input, "$.items[*].name"), false))
        assertEquals("[\"A\"]", JsonTool.format(JsonTool.query(input, "$.items[?(@.price < 10)].name"), false))
        assertEquals("[\"A\",\"B\",\"C\"]", JsonTool.format(JsonTool.query(input, "$..name"), false))
        assertEquals("null", JsonTool.query("null", "$"))
        assertEquals("\"x\"", JsonTool.query("\"x\"", "$"))
    }

    @Test fun `invalid JSON reports location instead of silently fixing input`() {
        val error = assertThrows(IllegalArgumentException::class.java) { JsonTool.parse("{\n  \"a\": } unrelated") }
        assertTrue(error.message!!.contains("JSON 格式错误"))
        assertTrue(error.message!!.contains("第 2 行"))
        for (value in listOf("NaN", "undefined", "01", "{a:1}", "[1,]", "{\"a\":1}extra")) {
            assertThrows(IllegalArgumentException::class.java, { JsonTool.parse(value) }, value)
        }
    }

    @Test fun `tree expands collapses searches and reports truncation`() {
        val value = JsonTool.parse("""{"items":[{"name":"中文"}],"other":false}""")
        assertEquals(1, JsonTool.tree(value, expanded = emptySet()).rows.size)
        assertEquals(3, JsonTool.tree(value).rows.size)
        val expanded = JsonTool.tree(value, expanded = setOf("$", "$['items']", "$['items'][0]"))
        assertTrue(expanded.rows.any { it.path == "$['items'][0]['name']" })
        assertEquals("name", JsonTool.tree(value, search = "中文").rows.single().label)
        assertTrue(JsonTool.tree(value, search = "", limit = 2).truncated)
    }

    @Test fun `duplicate keys are rejected including unicode escaped equivalents`() {
        for (text in listOf("""{"a":1,"a":2}""", """{"a":1,"\u0061":2}""")) {
            val error = assertThrows(IllegalArgumentException::class.java) { JsonTool.parse(text) }
            assertTrue(error.message!!.contains("重复字段"))
        }
        JsonTool.parse("""{"left":{"a":1},"right":{"a":2}}""")
    }

    @Test fun `size depth malformed queries and expensive regex filters are bounded`() {
        assertThrows(IllegalArgumentException::class.java) { JsonTool.parse("[".repeat(129) + "0" + "]".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { JsonTool.parse(" ".repeat(ToolText.MAX_LENGTH + 1)) }
        assertThrows(Exception::class.java) { JsonTool.query("{}", "$.missing") }
        assertThrows(IllegalArgumentException::class.java) { JsonTool.query("[]", "$[?(@.name =~ /x/)]") }
        assertEquals(JsonPrimitive("{}[]"), JsonTool.parse("\"{}[]\""))
    }
}
