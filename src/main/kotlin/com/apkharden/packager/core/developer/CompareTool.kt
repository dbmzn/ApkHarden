package com.apkharden.packager.core.developer

import com.github.difflib.DiffUtils
import com.github.difflib.algorithm.myers.MyersDiffWithLinearSpace
import kotlinx.serialization.json.*
import java.math.BigDecimal

internal enum class DifferenceKind { SAME, ADDED, REMOVED, CHANGED }
internal data class DifferenceRow(
    val label: String, val before: String?, val after: String?, val kind: DifferenceKind,
    val leftLine: Int? = null, val rightLine: Int? = null,
)
internal data class ComparisonResult(val rows: List<DifferenceRow>) {
    val changes get() = rows.count { it.kind != DifferenceKind.SAME }
    val identical get() = changes == 0
    fun summary(): String = if (identical) "内容一致" else rows.filter { it.kind != DifferenceKind.SAME }.joinToString("\n\n") {
        "${it.label}\n- ${it.before ?: "（不存在）"}\n+ ${it.after ?: "（不存在）"}"
    }
}

internal object CompareTool {
    private const val MAX_LINES = 5000
    private const val MAX_CHANGES = 3000

    fun text(left: String, right: String, ignoreWhitespace: Boolean = false): ComparisonResult {
        ToolText.checkSize(left); ToolText.checkSize(right)
        fun lines(value: String) = if (value.isEmpty()) emptyList() else value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val old = lines(left)
        val new = lines(right)
        require(old.size <= MAX_LINES && new.size <= MAX_LINES) { "文本对比每侧最多 $MAX_LINES 行，请缩小内容。" }
        val whitespace = Regex("\\s+")
        fun key(value: String) = if (ignoreWhitespace) value.replace(whitespace, "") else value
        val patch = DiffUtils.diff(old.map(::key), new.map(::key), MyersDiffWithLinearSpace<String>())
        val rows = mutableListOf<DifferenceRow>()
        var l = 0
        var r = 0
        for (delta in patch.deltas.sortedBy { it.source.position }) {
            while (l < delta.source.position) {
                rows += DifferenceRow("${l + 1} / ${r + 1}", old[l], new[r], DifferenceKind.SAME, l + 1, r + 1)
                l++; r++
            }
            repeat(maxOf(delta.source.size(), delta.target.size())) { i ->
                val li = (delta.source.position + i).takeIf { i < delta.source.size() }
                val ri = (delta.target.position + i).takeIf { i < delta.target.size() }
                val kind = when { li == null -> DifferenceKind.ADDED; ri == null -> DifferenceKind.REMOVED; else -> DifferenceKind.CHANGED }
                rows += DifferenceRow("行 ${li?.plus(1) ?: "—"} / ${ri?.plus(1) ?: "—"}", li?.let(old::get), ri?.let(new::get), kind, li?.plus(1), ri?.plus(1))
            }
            l = delta.source.position + delta.source.size()
            r = delta.target.position + delta.target.size()
        }
        while (l < old.size && r < new.size) {
            rows += DifferenceRow("${l + 1} / ${r + 1}", old[l], new[r], DifferenceKind.SAME, l + 1, r + 1)
            l++; r++
        }
        return ComparisonResult(alignChanges(rows))
    }

    // Linear-space Myers may emit adjacent delete/insert deltas for a replacement.
    // Align each changed block into two columns while retaining original line numbers.
    private fun alignChanges(rows: List<DifferenceRow>): List<DifferenceRow> = buildList {
        val pending = mutableListOf<DifferenceRow>()
        fun flush() {
            val left = pending.filter { it.before != null }
            val right = pending.filter { it.after != null }
            repeat(maxOf(left.size, right.size)) { i ->
                val a = left.getOrNull(i); val b = right.getOrNull(i)
                add(DifferenceRow("行 ${a?.leftLine ?: "—"} / ${b?.rightLine ?: "—"}", a?.before, b?.after,
                    when { a == null -> DifferenceKind.ADDED; b == null -> DifferenceKind.REMOVED; else -> DifferenceKind.CHANGED },
                    a?.leftLine, b?.rightLine))
            }
            pending.clear()
        }
        for (row in rows) {
            if (row.kind == DifferenceKind.SAME) { flush(); add(row) } else pending += row
        }
        flush()
    }

    /** Object key order is irrelevant, but array positions and whitespace inside strings matter. */
    fun json(left: String, right: String): ComparisonResult {
        val old = JsonTool.parse(left)
        val new = JsonTool.parse(right)
        val rows = mutableListOf<DifferenceRow>()
        fun visit(a: JsonElement?, b: JsonElement?, path: String) {
            if (a is JsonObject && b is JsonObject) {
                (a.keys + b.keys).sorted().forEach { key ->
                    visit(a[key], b[key], "$path['${key.replace("\\", "\\\\").replace("'", "\\'")}']")
                }
                return
            }
            if (a is JsonArray && b is JsonArray) {
                repeat(maxOf(a.size, b.size)) { i -> visit(a.getOrNull(i), b.getOrNull(i), "$path[$i]") }
                return
            }
            val numbersEqual = a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
                runCatching { BigDecimal(a.content).compareTo(BigDecimal(b.content)) == 0 }.getOrDefault(false)
            if (a == b || numbersEqual) return
            require(rows.size < MAX_CHANGES) { "JSON 差异超过 $MAX_CHANGES 项，请缩小内容。" }
            rows += DifferenceRow(path, a?.let { JsonTool.render(it) }, b?.let { JsonTool.render(it) },
                when { a == null -> DifferenceKind.ADDED; b == null -> DifferenceKind.REMOVED; else -> DifferenceKind.CHANGED })
        }
        visit(old, new, "$")
        return ComparisonResult(rows)
    }
}
