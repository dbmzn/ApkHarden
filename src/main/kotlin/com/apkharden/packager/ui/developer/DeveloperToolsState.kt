package com.apkharden.packager.ui.developer

import androidx.compose.runtime.*
import com.apkharden.packager.core.developer.*
import kotlinx.serialization.json.JsonElement
import java.time.ZoneId

internal enum class DeveloperTool(val title: String, val keywords: String) {
    JSON("JSON 工具", "json 格式化 压缩 校验 树形 jsonpath 字段 查询"),
    DIFF("文本对比", "diff 文本 比较 差异 json"),
    CODEC("编码解码", "url base64 unicode html jwt 转义 字符串"),
    TIME("时间工具", "时间戳 timestamp 时区 日期 cron 定时 时间差"),
    IMAGE("图片工具", "图片 压缩 转换 裁剪 尺寸 缩放 png jpeg jpg webp 照片 无损 批量 image"),
}
internal class ToolTask {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    fun clearStatus() { error = null; message = null }
}
internal class JsonWorkspaceState {
    val task = ToolTask()
    var input by mutableStateOf("")
    var output by mutableStateOf("")
    var element by mutableStateOf<JsonElement?>(null)
    var path by mutableStateOf("$.items[*].name")
    var treeView by mutableStateOf(false)
    var search by mutableStateOf("")
    var expanded by mutableStateOf(setOf("$"))
    fun edit(value: String) { input = value; output = ""; element = null; task.clearStatus() }
    fun result(value: String, parsed: JsonElement) { output = value; element = parsed; expanded = setOf("$"); search = "" }
}
internal class DiffWorkspaceState {
    val task = ToolTask()
    var left by mutableStateOf("")
    var right by mutableStateOf("")
    var jsonMode by mutableStateOf(false)
    var ignoreWhitespace by mutableStateOf(false)
    var onlyChanges by mutableStateOf(false)
    var result by mutableStateOf<ComparisonResult?>(null)
    fun changed() { result = null; task.clearStatus() }
}
internal class CodecWorkspaceState {
    val task = ToolTask()
    var input by mutableStateOf("")
    var output by mutableStateOf("")
    var detail by mutableStateOf<String?>(null)
    var format by mutableStateOf(CodecFormat.URL)
    var encode by mutableStateOf(true)
    fun changed() { output = ""; detail = null; task.clearStatus() }
}
internal enum class TimePage(val title: String) { CONVERT("时间转换"), DIFFERENCE("时间差"), CRON("Cron 预览") }
internal class TimeWorkspaceState {
    val task = ToolTask()
    var page by mutableStateOf(TimePage.CONVERT)
    var zone by mutableStateOf(ZoneId.systemDefault().id)
    var unit by mutableStateOf(TimestampUnit.AUTO)
    var timestamp by mutableStateOf("")
    var date by mutableStateOf("")
    var start by mutableStateOf("")
    var end by mutableStateOf("")
    var cron by mutableStateOf("*/5 * * * *")
    var cronFormat by mutableStateOf(CronFormat.UNIX)
    var after by mutableStateOf("")
    var output by mutableStateOf("")
    fun changed() { output = ""; task.clearStatus() }
}

/** Session-only working data. Stored above navigation so changing tools does not lose inputs. */
internal class DeveloperToolsState {
    var selected by mutableStateOf(DeveloperTool.JSON)
    var search by mutableStateOf("")
    var focusSearchSignal by mutableStateOf(0)
    val json = JsonWorkspaceState()
    val diff = DiffWorkspaceState()
    val codec = CodecWorkspaceState()
    val time = TimeWorkspaceState()
    val image = com.apkharden.packager.ui.image.ImageWorkspaceState()
}
