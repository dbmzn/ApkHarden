package com.apkharden.packager.ui.theme

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.apkharden.packager.ui.common.ToolButton
import com.apkharden.packager.ui.common.ToolIcons
import com.apkharden.packager.ui.common.ToolOutlinedButton

@Composable
internal fun AppearanceDialog(
    settings: AppearanceSettings,
    dark: Boolean,
    saving: Boolean,
    saveError: String?,
    onChange: (AppearanceSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sem = LocalSemantic.current
    val scroll = rememberScrollState()
    DialogWindow(
        title = "皮肤与外观", onCloseRequest = onDismiss,
        state = rememberDialogState(width = 660.dp, height = 760.dp),
    ) {
        window.minimumSize = java.awt.Dimension(560, 560)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("皮肤与外观", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(6.dp))
                Text("选中即生效，重启后保持选择", style = MaterialTheme.typography.body2, color = sem.subtle)
                Spacer(Modifier.height(20.dp))
                Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("外观模式", style = MaterialTheme.typography.subtitle1)
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppearanceMode.entries.forEach { mode ->
                                val active = settings.mode == mode
                                Surface(
                                    Modifier.weight(1f).clip(RoundedCornerShape(50))
                                        .selectable(active, role = Role.RadioButton, onClick = { onChange(settings.copy(mode = mode)) }),
                                    shape = RoundedCornerShape(50),
                                    color = if (active) MaterialTheme.colors.primary else sem.cardBg,
                                    contentColor = if (active) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                                ) {
                                    Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        Text(mode.title, style = MaterialTheme.typography.button)
                                    }
                                }
                            }
                        }
                        Text("皮肤库", style = MaterialTheme.typography.subtitle1)
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ThemeSkin.entries.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { skin ->
                                        SkinChoice(skin, dark, skin == settings.skin, Modifier.weight(1f)) {
                                            onChange(settings.copy(skin = skin))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                Spacer(Modifier.height(14.dp))
                Divider(color = sem.cardBorder)
                Spacer(Modifier.height(12.dp))
                Text(
                    saveError ?: if (saving) "正在保存…" else "当前：${settings.skin.title} · ${settings.mode.title}",
                    color = if (saveError == null) sem.subtle else MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolOutlinedButton(onClick = { onChange(AppearanceSettings()) }) { Text("恢复默认") }
                    if (saveError != null) TextButton(onClick = { onChange(settings) }) { Text("重试保存") }
                    Spacer(Modifier.weight(1f))
                    ToolButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun SkinChoice(skin: ThemeSkin, dark: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = skin.palette(dark)
    val sem = palette.semantic
    Surface(
        modifier.clip(RoundedCornerShape(22.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${skin.title}${if (skin == ThemeSkin.ORIGINAL) "，默认皮肤" else ""}" },
        shape = RoundedCornerShape(22.dp), color = palette.colors.background,
        contentColor = palette.colors.onBackground,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colors.primary else LocalSemantic.current.cardBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skin.title, style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
                Text(if (selected) "使用中" else if (skin == ThemeSkin.ORIGINAL) "默认" else "",
                    style = MaterialTheme.typography.caption, color = palette.colors.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(sem.accentBg to sem.onAccent, sem.secondaryBg to sem.onSecondaryPanel).forEachIndexed { index, (bg, fg) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (index == 0) ToolIcons.Capture else ToolIcons.Video, null, Modifier.size(22.dp), tint = fg)
                        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(palette.colors.primary))
                    }
                }
            }
            Text(skin.description, style = MaterialTheme.typography.caption, color = sem.subtle)
        }
    }
}
