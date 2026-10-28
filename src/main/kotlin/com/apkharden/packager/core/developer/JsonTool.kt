package com.apkharden.packager.core.developer

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.spi.json.AbstractJsonProvider
import kotlinx.serialization.json.*
import java.io.InputStream
import java.math.BigDecimal

internal data class JsonTreeRow(val path: String, val label: String, val summary: String, val depth: Int, val container: Boolean)
internal data class JsonTreeResult(val rows: List<JsonTreeRow>, val truncated: Boolean)

internal object JsonTool {
    private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val pathConfig = Configuration.builder().jsonProvider(KotlinJsonProvider()).build()

    fun parse(input: String): JsonElement {
        ToolText.checkSize(input)
        val text = input.removePrefix("\uFEFF")
        require(text.isNotBlank()) { "请先输入 JSON。" }
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEachIndexed { index, c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 128) { "JSON 嵌套不能超过 128 层（位置 $index）。" } }
                '}', ']' -> depth--
            }
        }
        val element = try { Json.parseToJsonElement(text) } catch (error: Exception) {
            val raw = error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
            val offset = Regex("offset (\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            val location = offset?.coerceIn(0, text.length)?.let { at ->
                val before = text.take(at)
                "（第 ${before.count { it == '\n' } + 1} 行，第 ${at - before.lastIndexOf('\n')} 列）"
            }.orEmpty()
            throw IllegalArgumentException("JSON 格式错误$location：${raw.take(220)}")
        }
        rejectDuplicateKeys(text)
        validateValues(element)
        return element
    }

    private fun validateValues(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::validateValues)
            is JsonArray -> value.forEach(::validateValues)
            is JsonPrimitive -> if (!value.isString && value != JsonNull) {
                require(value.booleanOrNull != null || number.matches(value.content)) { "无效 JSON 值：${value.content.take(60)}" }
            }
        }
    }

    fun format(input: String, pretty: Boolean = true): String = render(parse(input), pretty)
    fun render(value: JsonElement, pretty: Boolean = true): String {
        if (!pretty) return value.toString()
        return buildString {
            fun indent(depth: Int) { repeat(depth * 2) { append(' ') } }
            fun write(node: JsonElement, depth: Int) {
                when (node) {
                    is JsonObject -> {
                        append('{')
                        node.entries.forEachIndexed { index, entry ->
                            if (index > 0) append(',')
                            append('\n'); indent(depth + 1)
                            append(JsonPrimitive(entry.key).toString()); append(": "); write(entry.value, depth + 1)
                        }
                        if (node.isNotEmpty()) { append('\n'); indent(depth) }
                        append('}')
                    }
                    is JsonArray -> {
                        append('[')
                        node.forEachIndexed { index, child ->
                            if (index > 0) append(',')
                            append('\n'); indent(depth + 1); write(child, depth + 1)
                        }
                        if (node.isNotEmpty()) { append('\n'); indent(depth) }
                        append(']')
                    }
                    else -> append(node.toString())
                }
                require(length <= 2_000_000) { "格式化结果超过 200 万字符，请缩小输入。" }
            }
            write(value, 0)
        }
    }

    private fun rejectDuplicateKeys(text: String) {
        val containers = ArrayDeque<MutableSet<String>?>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> containers.addLast(mutableSetOf())
                '[' -> containers.addLast(null)
                '}', ']' -> containers.removeLast()
                '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    val end = i + 1
                    var next = end
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (text.getOrNull(next) == ':') {
                        val key = Json.parseToJsonElement(text.substring(start, end)).jsonPrimitive.content
                        require(containers.last()?.add(key) != false) { "发现重复字段「${key.take(60)}」，请先修正，避免内容被覆盖。" }
                    }
                }
            }
            i++
        }
    }

    fun query(input: String, path: String): String = render(queryValue(input, path))

    fun queryValue(input: String, path: String): JsonElement {
        val json = parse(input)
        val expression = path.trim()
        require(expression.isNotEmpty() && expression.length <= 1000) { "请输入 JSONPath（最长 1000 字符）。" }
        require(!expression.contains("=~")) { "暂不支持 JSONPath 正则过滤；可使用字段、数组、通配符和条件比较。" }
        if (expression == "$") return json
        val document = toNative(json) ?: throw IllegalArgumentException("null 根值只能查询 $。")
        val result: Any? = JsonPath.using(pathConfig).parse(document).read(expression)
        return fromNative(result)
    }

    fun tree(value: JsonElement, search: String = "", expanded: Set<String> = setOf("$"), limit: Int = 3000): JsonTreeResult {
        require(limit > 0)
        val rows = mutableListOf<JsonTreeRow>()
        val query = search.trim()
        var truncated = false
        fun visit(node: JsonElement, path: String, label: String, depth: Int) {
            if (truncated) return
            val container = node is JsonObject || node is JsonArray
            val summary = when (node) {
                is JsonObject -> "{ ${node.size} 个字段 }"
                is JsonArray -> "[ ${node.size} 项 ]"
                else -> node.toString().take(220)
            }
            if (query.isBlank() || path.contains(query, true) || (!container && node.toString().contains(query, true))) {
                if (rows.size == limit) { truncated = true; return }
                rows += JsonTreeRow(path, label, summary, depth, container)
            }
            if (container && (query.isNotBlank() || path in expanded)) {
                when (node) {
                    is JsonObject -> node.forEach { (key, child) ->
                        visit(child, "$path['${key.replace("\\", "\\\\").replace("'", "\\'")}']", key, depth + 1)
                    }
                    is JsonArray -> node.forEachIndexed { i, child -> visit(child, "$path[$i]", "[$i]", depth + 1) }
                    else -> Unit
                }
            }
        }
        visit(value, "$", "$", 0)
        return JsonTreeResult(rows, truncated)
    }

    private fun toNative(value: JsonElement): Any? = when (value) {
        is JsonObject -> value.mapValuesTo(linkedMapOf()) { toNative(it.value) }
        is JsonArray -> value.mapTo(arrayListOf(), ::toNative)
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: BigDecimal(value.content)
    }

    private fun fromNative(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to fromNative(it.value) })
        is Iterable<*> -> JsonArray(value.map(::fromNative))
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /** Keeps numbers exact instead of converting IDs and decimals through floating point. */
    private class KotlinJsonProvider : AbstractJsonProvider() {
        override fun parse(json: String): Any? = toNative(JsonTool.parse(json))
        override fun parse(stream: InputStream, charset: String): Any? = parse(stream.reader(charset(charset)).readText())
        override fun toJson(obj: Any?): String = fromNative(obj).toString()
        override fun createArray(): Any = arrayListOf<Any?>()
        override fun createMap(): Any = linkedMapOf<String, Any?>()
    }
}
