package com.apkharden.packager.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.apkharden.packager.scanner.PrivacyScanner
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import com.apkharden.packager.scanner.report.ReportExporter
import com.apkharden.packager.ui.common.pickFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun sevLabel(s: Severity) = when (s) {
    Severity.HIGH -> "高"; Severity.MEDIUM -> "中"; Severity.LOW -> "低"; Severity.INFO -> "提示"
}
private fun sevColor(s: Severity) = when (s) {
    Severity.HIGH -> Color(0xFFD32F2F); Severity.MEDIUM -> Color(0xFFF57C00)
    Severity.LOW -> Color(0xFFFBC02D); Severity.INFO -> Color(0xFF607D8B)
}

@Composable
fun ScanScreen() {
    var inputApk by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ScanReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<Severity?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("隐私合规扫描", style = MaterialTheme.typography.h6, modifier = Modifier.padding(bottom = 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(inputApk, { inputApk = it }, label = { Text("输入 APK") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let { inputApk = it } }) { Text("浏览") }
        }

        Button(
            enabled = !running && inputApk.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = {
                running = true; report = null; error = null; filter = null
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { PrivacyScanner.scan(File(inputApk)) }
                        report = r
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    } finally {
                        running = false
                    }
                }
            },
        ) { Text(if (running) "扫描中…" else "开始扫描") }

        error?.let {
            Text("❌ $it", color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 8.dp))
        }

        val r = report
        if (r != null) {
            Divider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (s in Severity.entries) {
                    val c = r.summary[s] ?: continue
                    val on = filter == s
                    Surface(color = if (on) sevColor(s) else sevColor(s).copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable { filter = if (on) null else s }) {
                        Text("${sevLabel(s)} $c", color = if (on) Color.White else sevColor(s),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Text("${r.packageName ?: "未知"}　${r.versionName ?: ""}",
                style = MaterialTheme.typography.caption, modifier = Modifier.padding(top = 4.dp))

            val shown = r.findings.filter { filter == null || it.severity == filter }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                if (shown.isEmpty()) {
                    Text("未发现明显风险项。")
                } else {
                    for ((category, items) in shown.groupBy { it.category }) {
                        Text(category, style = MaterialTheme.typography.subtitle2,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        for (f in items) {
                            Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(color = sevColor(f.severity), shape = MaterialTheme.shapes.small) {
                                    Text(sevLabel(f.severity), color = Color.White,
                                        style = MaterialTheme.typography.caption,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                Column {
                                    Text(f.title + (f.location?.let { "　@$it" } ?: ""),
                                        style = MaterialTheme.typography.body2)
                                    Text(f.detail, style = MaterialTheme.typography.caption)
                                    Text("→ ${f.advice}", style = MaterialTheme.typography.caption,
                                        color = Color(0xFF33691E))
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    pickFile("Markdown", save = true, extensions = listOf("md"))?.let {
                        File(it).writeText(ReportExporter.toMarkdown(r))
                    }
                }) { Text("导出 Markdown") }
                Button(onClick = {
                    pickFile("HTML", save = true, extensions = listOf("html"))?.let {
                        File(it).writeText(ReportExporter.toHtml(r))
                    }
                }) { Text("导出 HTML") }
            }
        }
    }
}
