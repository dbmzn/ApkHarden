package com.apkharden.packager.ui.developer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.developer.JsonTool
import com.apkharden.packager.ui.common.*
import com.apkharden.packager.ui.theme.LocalSemantic

@Composable
internal fun JsonWorkspace(state: JsonWorkspaceState, onCompare: (String) -> Unit) {
    val task = state.task
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(enabled = !task.busy, onClick = {
                val input = state.input
                task.run(scope, { val parsed = JsonTool.parse(input); JsonTool.render(parsed) to parsed }) { (text, parsed) -> state.result(text, parsed); task.message = "格式化完成" }
            }) { Text("格式化") }
            ToolOutlinedButton(enabled = !task.busy, onClick = {
                val input = state.input
                task.run(scope, { val parsed = JsonTool.parse(input); JsonTool.render(parsed, false) to parsed }) { (text, parsed) -> state.result(text, parsed); task.message = "压缩完成" }
            }) { Text("压缩") }
            ToolOutlinedButton(enabled = !task.busy, onClick = {
                val input = state.input
                task.run(scope, { JsonTool.parse(input) }) { state.result(input, it); task.message = "JSON 校验通过" }
            }) { Text("校验") }
            TextButton(enabled = !task.busy, onClick = {
                state.edit("""{
  "requestId": 9223372036854775808,
  "items": [
    {"name": "ApkHarden", "price": 12.50, "enabled": true},
    {"name": "开发工具", "price": 8.20, "enabled": false}
  ]
}""")
            }) { Text("填入示例") }
            Checkbox(state.treeView, { state.treeView = it })
            Text("树形查看", style = MaterialTheme.typography.body2)
            TextButton(enabled = !task.busy && state.output.isNotEmpty(), onClick = { onCompare(state.output) }) { Text("作为右侧对比") }
        }
        TaskStatus(task)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EditorPane("输入 JSON", state.input, task, Modifier.weight(1f), onChange = state::edit)
            if (state.treeView) JsonTreePanel(state, Modifier.weight(1f))
            else EditorPane("输出 JSON", state.output, task, Modifier.weight(1f), defaultName = "result.json")
        }
        ToolPanel(Modifier.fillMaxWidth()) {
            Text("JSONPath 查询", style = MaterialTheme.typography.subtitle1)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTextField(state.path, { state.path = it; task.clearStatus() }, Modifier.weight(1f), enabled = !task.busy,
                    label = { Text("如 $.items[*].name") }, singleLine = true)
                ToolButton(enabled = !task.busy, onClick = {
                    val input = state.input; val path = state.path
                    task.run(scope, { val value = JsonTool.queryValue(input, path); JsonTool.render(value) to value }) { (text, value) -> state.result(text, value); task.message = "查询完成，结果显示在输出区" }
                }) { Text("查询") }
            }
            Spacer(Modifier.height(8.dp))
            Text("支持字段、数组、通配符、递归查找和条件比较；结果不会覆盖输入。", style = MaterialTheme.typography.caption, color = sem.subtle)
        }
    }
}

@Composable
private fun JsonTreePanel(state: JsonWorkspaceState, modifier: Modifier) {
    val sem = LocalSemantic.current
    val clipboard = LocalClipboardManager.current
    val tree = remember(state.element, state.search, state.expanded) {
        state.element?.let { JsonTool.tree(it, state.search, state.expanded) }
    }
    ToolPanel(modifier) {
        Text("输出树", style = MaterialTheme.typography.subtitle1)
        ToolTextField(state.search, { state.search = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("搜索字段、路径或值") })
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
            if (tree == null) item { Text("先格式化、校验或查询 JSON", color = sem.subtle) }
            else if (tree.rows.isEmpty()) item { Text("没有匹配内容", color = sem.subtle) }
            items(tree?.rows.orEmpty(), key = { it.path }) { row ->
                Row(Modifier.fillMaxWidth().semantics { contentDescription = "${row.path}，${row.summary}" }.clickable(enabled = row.container && state.search.isBlank()) {
                    state.expanded = if (row.path in state.expanded) state.expanded - row.path else state.expanded + row.path
                }.padding(start = (minOf(row.depth, 6) * 10).dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (!row.container) "·" else if (row.path in state.expanded || state.search.isNotBlank()) "▾" else "▸",
                        Modifier.width(18.dp), color = MaterialTheme.colors.primary)
                    Column(Modifier.weight(1f)) {
                        Text(row.label, style = MaterialTheme.typography.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.summary, style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                            color = sem.subtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(row.path)); state.task.message = "已复制路径" }, contentPadding = PaddingValues(4.dp)) { Text("路径", style = MaterialTheme.typography.caption) }
                }
            }
        }
        Text(if (tree?.truncated == true) "结果较多，仅显示前 3000 项，请缩小搜索范围。" else "点击节点展开或收起；路径按钮可复制 JSONPath。",
            style = MaterialTheme.typography.caption, color = sem.subtle)
    }
}
