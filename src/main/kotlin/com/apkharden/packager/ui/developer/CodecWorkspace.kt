package com.apkharden.packager.ui.developer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.developer.*
import com.apkharden.packager.ui.common.*
import com.apkharden.packager.ui.theme.LocalSemantic

@Composable
internal fun CodecWorkspace(state: CodecWorkspaceState) {
    val task = state.task
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceMenu("格式", state.format, CodecFormat.entries, { it.title }, !task.busy) { state.format = it; state.changed() }
            if (state.format != CodecFormat.JWT) {
                ChoiceMenu("方向", state.encode, listOf(true, false), { if (it) "编码 / 转义" else "解码 / 还原" }, !task.busy) { state.encode = it; state.changed() }
            }
            ToolButton(enabled = !task.busy, onClick = {
                val input = state.input; val format = state.format; val encode = state.encode
                task.run(scope, { CodecTool.convert(input, format, encode) }) { state.output = it.output; state.detail = it.detail; task.message = "转换完成" }
            }) { Text(if (state.format == CodecFormat.JWT) "解析 JWT" else "开始转换") }
            TextButton(enabled = !task.busy && state.output.isNotEmpty() && state.format != CodecFormat.JWT, onClick = {
                state.input = state.output; state.encode = !state.encode; state.changed()
            }) { Text("结果作为输入") }
        }
        Text(state.format.hint, color = LocalSemantic.current.subtle, style = MaterialTheme.typography.body2)
        TaskStatus(task)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EditorPane("输入内容", state.input, task, Modifier.weight(1f), onChange = { state.input = it; state.changed() })
            EditorPane("转换结果", state.output, task, Modifier.weight(1f), defaultName = if (state.format == CodecFormat.JWT) "jwt-decoded.json" else "converted.txt")
        }
        state.detail?.let { Text(it, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary) }
        Text("仅在本机处理；不会自动读取剪贴板或保存输入历史。", color = LocalSemantic.current.subtle, style = MaterialTheme.typography.caption)
    }
}
