package com.apkharden.packager.core.developer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompareToolTest {
    @Test fun `inserting a line keeps later lines aligned with original numbers`() {
        val result = CompareTool.text("one\ntwo\nthree", "one\nnew\ntwo\nthree")
        assertEquals(1, result.changes)
        assertEquals(DifferenceKind.ADDED, result.rows[1].kind)
        assertEquals("new", result.rows[1].after)
        assertEquals(2, result.rows[2].leftLine)
        assertEquals(3, result.rows[2].rightLine)
        assertEquals(DifferenceKind.SAME, result.rows[2].kind)
    }
    @Test fun `removal replacement empty files and final newlines are distinguished`() {
        assertTrue(CompareTool.text("", "").identical)
        assertEquals(1, CompareTool.text("a\n", "a").changes)
        assertEquals(DifferenceKind.REMOVED, CompareTool.text("one\ntwo", "one").rows.last().kind)
        assertEquals(DifferenceKind.CHANGED, CompareTool.text("old", "new").rows.single().kind)
        assertEquals(DifferenceKind.ADDED, CompareTool.text("", "line").rows.single().kind)
        assertTrue(CompareTool.text("one\r\ntwo", "one\ntwo").identical)
    }
    @Test fun `whitespace can be ignored without changing displayed source text`() {
        val result = CompareTool.text(" a  b ", "ab", true)
        assertTrue(result.identical)
        assertEquals(" a  b ", result.rows.single().before)
    }
    @Test fun `JSON objects ignore order and numerical notation but preserve string whitespace`() {
        assertTrue(CompareTool.json("{\"a\":1.0,\"b\":2}", "{\"b\":2,\"a\":1}").identical)
        assertEquals(1, CompareTool.json("{\"text\":\"a b\"}", "{\"text\":\"ab\"}").changes)
        assertEquals(2, CompareTool.json("[1,2]", "[2,1]").changes)
    }
    @Test fun `missing fields and null are different and nested paths identify changes`() {
        val result = CompareTool.json("{\"data\":{\"a\":null}}", "{\"data\":{\"b\":null}}")
        assertEquals(2, result.changes)
        assertEquals("$['data']['a']", result.rows[0].label)
        assertEquals("null", result.rows[0].before)
        assertNull(result.rows[0].after)
        assertTrue(result.summary().contains("（不存在）"))
    }
    @Test fun `unrelated large texts retain all rows without quadratic trace storage`() {
        val left = (1..1500).map { "old-$it" }
        val right = (1..1500).map { "new-$it" }
        val result = CompareTool.text(left.joinToString("\n"), right.joinToString("\n"))
        assertFalse(result.identical)
        assertEquals(left, result.rows.mapNotNull { it.before })
        assertEquals(right, result.rows.mapNotNull { it.after })
    }
    @Test fun `large comparisons have an explicit limit`() {
        assertThrows(IllegalArgumentException::class.java) { CompareTool.text("x\n".repeat(5001), "") }
    }
}
