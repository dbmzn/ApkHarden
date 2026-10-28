package com.apkharden.packager.ui.developer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.apkharden.packager.ui.common.ToolOutlinedButton
import com.apkharden.packager.ui.common.ToolTextField
import com.apkharden.packager.ui.inspect.PageTitle
import com.apkharden.packager.ui.theme.LocalSemantic

@Composable
internal fun DeveloperToolsScreen(state: DeveloperToolsState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.focusSearchSignal) {
        if (state.focusSearchSignal > 0) focus.requestFocus()
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("通用开发工具", "JSON、文本、编码、时间与图片，在本机处理，工作内容保留至关闭应用")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTextField(state.search, { state.search = it }, Modifier.width(250.dp).focusRequester(focus),
                label = { Text("搜索工具 · ⌘ / Ctrl K") }, singleLine = true)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val matches = DeveloperTool.entries.filter { state.search.isBlank() || it.keywords.contains(state.search.trim(), true) }
                if (matches.isEmpty()) Text("没有匹配工具", color = LocalSemantic.current.subtle)
                matches.forEach { item ->
                    ToolOutlinedButton(onClick = { state.selected = item; state.search = "" },
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = if (state.selected == item) MaterialTheme.colors.primary else LocalSemantic.current.cardBg,
                            contentColor = if (state.selected == item) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                        )) { Text(item.title) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.selected) {
                DeveloperTool.JSON -> JsonWorkspace(state.json) {
                    state.diff.right = it; state.diff.jsonMode = true; state.diff.changed()
                    state.selected = DeveloperTool.DIFF; state.search = ""
                }
                DeveloperTool.DIFF -> DiffWorkspace(state.diff)
                DeveloperTool.CODEC -> CodecWorkspace(state.codec)
                DeveloperTool.TIME -> TimeWorkspace(state.time)
                DeveloperTool.IMAGE -> com.apkharden.packager.ui.image.ImageWorkspace(state.image)
            }
        }
    }
}
