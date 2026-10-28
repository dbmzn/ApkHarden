package com.apkharden.packager.ui.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.developer.*
import com.apkharden.packager.ui.common.*
import com.apkharden.packager.ui.theme.LocalSemantic

@Composable
internal fun DiffWorkspace(state: DiffWorkspaceState) {
    val task = state.task
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val sem = LocalSemantic.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton(enabled = !task.busy, onClick = {
                val left = state.left; val right = state.right; val json = state.jsonMode; val ignore = state.ignoreWhitespace
                task.run(scope, { if (json) CompareTool.json(left, right) else CompareTool.text(left, right, ignore) }) { state.result = it }
            }) { Text("开始对比") }
            Checkbox(state.jsonMode, { state.jsonMode = it; state.changed() }, enabled = !task.busy)
            Text("JSON 按字段", style = MaterialTheme.typography.body2)
            Checkbox(state.ignoreWhitespace, { state.ignoreWhitespace = it; state.changed() }, enabled = !task.busy && !state.jsonMode)
            Text("忽略空白", style = MaterialTheme.typography.body2)
            TextButton(enabled = !task.busy, onClick = {
                val old = state.left; state.left = state.right; state.right = old; state.changed()
            }) { Text("交换左右") }
            TextButton(enabled = !task.busy, onClick = {
                state.left = "{\"name\":\"WorkKit\",\"version\":1,\"enabled\":true}"
                state.right = "{\"enabled\":true,\"name\":\"WorkKit\",\"version\":2,\"tools\":4}"
                state.jsonMode = true; state.changed()
            }) { Text("填入示例") }
        }
        Text(if (state.jsonMode) "对象字段顺序不影响比较；数组顺序、字符串内空白会保留；不存在与 null 分开显示。"
            else "逐行对比；换行格式统一为 LF，保留末尾换行差异。", style = MaterialTheme.typography.caption, color = sem.subtle)
        TaskStatus(task)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EditorPane("左侧 · 原始内容", state.left, task, Modifier.weight(1f), onChange = { state.left = it; state.changed() }, editorHeight = 150.dp)
            EditorPane("右侧 · 新内容", state.right, task, Modifier.weight(1f), onChange = { state.right = it; state.changed() }, editorHeight = 150.dp)
        }
        state.result?.let { result ->
            ToolPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (result.identical) "内容一致" else "${result.changes} 处差异", style = MaterialTheme.typography.h6,
                        color = if (result.identical) sem.advice else MaterialTheme.colors.onSurface)
                    Spacer(Modifier.weight(1f))
                    if (!state.jsonMode) { Checkbox(state.onlyChanges, { state.onlyChanges = it }); Text("只看差异", style = MaterialTheme.typography.caption) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(result.summary())); task.message = "已复制差异摘要" }) { Text("复制差异") }
                }
                if (!result.identical) {
                    Text("红色为左侧删除或旧值，绿色为右侧新增或新值。长行仅预览前 800 字符，复制保留完整值。", style = MaterialTheme.typography.caption, color = sem.subtle)
                    Spacer(Modifier.height(8.dp))
                    val visible = if (state.onlyChanges || state.jsonMode) result.rows.filter { it.kind != DifferenceKind.SAME } else result.rows
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        itemsIndexed(visible) { _, row ->
                            val changed = row.kind != DifferenceKind.SAME
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(row.label, style = MaterialTheme.typography.caption, color = sem.subtle)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(row.before to true, row.after to false).forEach { (content, old) ->
                                        val tint = if (!changed) sem.subtle else if (old) MaterialTheme.colors.error else sem.advice
                                        Box(Modifier.weight(1f).background(tint.copy(alpha = .08f)).padding(10.dp)) {
                                            SelectionContainer {
                                                Text(content?.let { if (it.length > 800) it.take(800) + "…" else it } ?: "（不存在）",
                                                    modifier = Modifier.fillMaxWidth(), color = tint,
                                                    style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
