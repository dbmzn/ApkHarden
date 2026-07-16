package com.apkharden.packager.ui.archive

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
fun ArchiveScreen() {
    var path by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ApkEntryCategory?>(null) }
    var report by remember { mutableStateOf<ApkArchiveReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("APK 文件浏览器", "无需反编译，查看 DEX、SO、资源、Assets 的目录与大小占比")
        ValueCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(path, { path = it; report = null }, label = { Text("APK 文件") },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedButton(onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let { path = it; report = null } }) { Text("浏览") }
                Button(enabled = !running && path.isNotBlank(), onClick = {
                    running = true; error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { ApkArchiveExplorer.analyze(File(path)) } }
                            .onSuccess { report = it }.onFailure { error = it.message }
                        running = false
                    }
                }) { Text(if (running) "读取中…" else "打开 APK") }
            }
        }
        error?.let { Text("读取失败：$it", color = MaterialTheme.colors.error) }
        report?.let { value ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                value.categorySizes.forEach { (category, size) ->
                    CategoryChip(category, size, selectedCategory == category, Modifier.weight(1f)) {
                        selectedCategory = if (selectedCategory == category) null else category
                    }
                }
            }
            SectionTitle("目录占比", "解压后共 ${formatBytes(value.totalSize)}")
            ValueCard {
                value.directorySizes.entries.take(14).forEach { (directory, size) ->
                    SizeBar(directory, size, value.totalSize)
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索文件路径") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            val visible = value.entries.filter {
                (selectedCategory == null || it.category == selectedCategory) &&
                    (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
            }
            SectionTitle("文件清单", "${visible.size} 项 · 按解压大小排序")
            ValueCard {
                if (visible.isEmpty()) Text("没有匹配文件", color = sem.subtle)
                visible.take(100).forEach { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.category.label, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary,
                            modifier = Modifier.width(82.dp))
                        Text(entry.name, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
                        Text(formatBytes(entry.size), style = MaterialTheme.typography.caption, color = sem.subtle)
                    }
                }
                if (visible.size > 100) Text("另有 ${visible.size - 100} 项，请使用搜索缩小范围", color = sem.subtle,
                    style = MaterialTheme.typography.caption, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun CategoryChip(category: ApkEntryCategory, size: Long, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sem = LocalSemantic.current
    OutlinedButton(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (selected) MaterialTheme.colors.primary.copy(alpha = .14f) else sem.cardBg)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(category.label, style = MaterialTheme.typography.caption)
            Text(formatBytes(size), style = MaterialTheme.typography.subtitle2)
        }
    }
}

@Composable
private fun SizeBar(label: String, size: Long, total: Long) {
    val sem = LocalSemantic.current
    val ratio = if (total <= 0) 0f else (size.toFloat() / total).coerceIn(.01f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row { Text(label, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f)); Text(formatBytes(size),
            style = MaterialTheme.typography.caption, color = sem.subtle) }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(sem.cardBorder)) {
            Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(MaterialTheme.colors.primary))
        }
    }
}
