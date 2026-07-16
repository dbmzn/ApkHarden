package com.apkharden.packager.ui.size

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.*
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.inspect.PageTitle
import com.apkharden.packager.ui.inspect.SectionTitle
import com.apkharden.packager.ui.inspect.ValueCard
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SizeAnalysisScreen() {
    var oldPath by remember { mutableStateOf("") }
    var newPath by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ApkSizeComparison?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("包体积分析", "定位新版本体积增量来源，按模块和具体文件生成排行榜")
        ValueCard {
            SizePicker("旧版本 APK", oldPath) { oldPath = it; result = null }
            Spacer(Modifier.height(9.dp))
            SizePicker("新版本 APK", newPath) { newPath = it; result = null }
            Spacer(Modifier.height(11.dp))
            Button(enabled = !running && oldPath.isNotBlank() && newPath.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                running = true; error = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { ApkArchiveExplorer.compare(File(oldPath), File(newPath)) } }
                        .onSuccess { result = it }.onFailure { error = it.message }
                    running = false
                }
            }) { Text(if (running) "分析中…" else "分析体积变化") }
        }
        error?.let { Text("分析失败：$it", color = MaterialTheme.colors.error) }
        result?.let { value ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("旧包", formatBytes(value.oldReport.file.length()), Modifier.weight(1f))
                MetricCard("新包", formatBytes(value.newReport.file.length()), Modifier.weight(1f))
                MetricCard("APK 增量", signed(value.totalDelta), Modifier.weight(1f), value.totalDelta > 0)
                MetricCard("变化文件", "${value.changes.size} 项", Modifier.weight(1f))
            }
            SectionTitle("模块增量", "按文件解压大小统计")
            ValueCard {
                value.categoryDelta.entries.sortedByDescending { kotlin.math.abs(it.value) }.forEach { (category, delta) ->
                    DeltaLine(category.label, delta)
                }
            }
            val increases = value.changes.filter { it.delta > 0 }
            val decreases = value.changes.filter { it.delta < 0 }
            SectionTitle("增量来源排行榜", "${increases.size} 项增加")
            ValueCard {
                if (increases.isEmpty()) Text("没有文件体积增加", color = sem.advice)
                increases.take(40).forEach { ChangeLine(it) }
            }
            SectionTitle("减量来源", "${decreases.size} 项减少")
            ValueCard {
                if (decreases.isEmpty()) Text("没有文件体积减少", color = sem.subtle)
                decreases.take(30).forEach { ChangeLine(it) }
            }
        }
    }
}

@Composable
private fun SizePicker(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedButton(onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let(onChange) }) { Text("浏览") }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier, warning: Boolean = false) {
    val sem = LocalSemantic.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(sem.cardBg)
        .border(1.dp, sem.cardBorder, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = sem.subtle)
        Spacer(Modifier.height(5.dp))
        Text(value, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold,
            color = if (warning) sem.sevMedium else MaterialTheme.colors.onSurface)
    }
}

@Composable
private fun DeltaLine(label: String, delta: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.body2)
        Text(signed(delta), color = if (delta > 0) LocalSemantic.current.sevMedium else LocalSemantic.current.advice,
            style = MaterialTheme.typography.subtitle2)
    }
}

@Composable
private fun ChangeLine(change: ApkSizeChange) {
    val sem = LocalSemantic.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(change.category.label, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption,
            modifier = Modifier.width(80.dp))
        Text(change.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.body2)
        Text(signed(change.delta), color = if (change.delta > 0) sem.sevMedium else sem.advice,
            style = MaterialTheme.typography.subtitle2)
    }
}

private fun signed(value: Long): String = when {
    value > 0 -> "+${formatBytes(value)}"
    value < 0 -> "−${formatBytes(-value)}"
    else -> "0 B"
}
