package com.apkharden.packager.ui.developer

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.developer.ToolText
import com.apkharden.packager.ui.common.ToolPanel
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.*
import java.io.File
import java.nio.charset.CharacterCodingException

internal fun <T> ToolTask.run(scope: CoroutineScope, work: () -> T, success: (T) -> Unit) {
    if (busy) return
    busy = true; clearStatus()
    scope.launch {
        try { success(withContext(Dispatchers.Default) { work() }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = if (failure is CharacterCodingException) "内容不是有效 UTF-8 文本，或包含不完整的 Unicode 字符。"
                else failure.message?.lineSequence()?.firstOrNull()?.take(300) ?: "处理失败，请检查输入。"
        } finally { busy = false }
    }
}

@Composable
internal fun TaskStatus(task: ToolTask) {
    if (task.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    task.error?.let { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2) }
    task.message?.let { Text(it, color = LocalSemantic.current.advice, style = MaterialTheme.typography.caption) }
}

@Composable
internal fun EditorPane(
    title: String, value: String, task: ToolTask, modifier: Modifier = Modifier,
    onChange: ((String) -> Unit)? = null, defaultName: String = "result.txt", editorHeight: Dp = 220.dp,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current
    ToolPanel(modifier, if (onChange == null) sem.cardBg else sem.formBg) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
            Text("${value.length} 字符", style = MaterialTheme.typography.caption, color = sem.subtle)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onChange != null) {
                TextButton(enabled = !task.busy, onClick = {
                    val content = clipboard.getText()?.text ?: return@TextButton
                    runCatching { ToolText.checkSize(content); onChange(content) }
                        .onFailure { task.error = it.message }
                }) { Text("粘贴") }
                TextButton(enabled = !task.busy, onClick = {
                    val path = pickFile("UTF-8 文本", extensions = listOf("json", "txt", "log", "csv", "xml", "html")) ?: return@TextButton
                    task.run(scope, { ToolText.read(File(path)) }) { onChange(it) }
                }) { Text("打开文件") }
                TextButton(enabled = !task.busy && value.isNotEmpty(), onClick = { onChange("") }) { Text("清空") }
            } else {
                TextButton(enabled = !task.busy && value.isNotEmpty(), onClick = {
                    clipboard.setText(AnnotatedString(value)); task.message = "已复制结果"
                }) { Text("复制结果") }
                TextButton(enabled = !task.busy && value.isNotEmpty(), onClick = {
                    val path = pickFile("保存文本", save = true, extensions = listOf(defaultName.substringAfterLast('.')), defaultName = defaultName) ?: return@TextButton
                    task.run(scope, { ToolText.write(File(path), value) }) { task.message = "已保存结果" }
                }) { Text("保存文件") }
            }
        }
        OutlinedTextField(
            value, { onChange?.invoke(it) }, enabled = !task.busy || onChange == null,
            readOnly = onChange == null, modifier = Modifier.fillMaxWidth().height(editorHeight),
            placeholder = { Text(if (onChange == null) "处理结果显示在这里" else "输入或粘贴内容…") },
            textStyle = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.outlinedTextFieldColors(unfocusedBorderColor = sem.cardBorder),
        )
    }
}

@Composable
internal fun <T> ChoiceMenu(label: String, value: T, values: List<T>, title: (T) -> String,
    enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        com.apkharden.packager.ui.common.ToolOutlinedButton(enabled = enabled, onClick = { open = true }) {
            Text("$label：${title(value)} ▾")
        }
        DropdownMenu(open, { open = false }) {
            values.forEach { item -> DropdownMenuItem(onClick = { open = false; onSelect(item) }) { Text(title(item)) } }
        }
    }
}
