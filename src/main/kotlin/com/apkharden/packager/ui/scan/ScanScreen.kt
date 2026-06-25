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
import com.apkharden.packager.scanner.model.FindingTier
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import com.apkharden.packager.scanner.model.tier
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
private fun tierColor(t: FindingTier) = when (t) {
    FindingTier.ISSUE -> Color(0xFFD32F2F); FindingTier.REVIEW -> Color(0xFF3F6FB0)
}
private fun tierNote(t: FindingTier) = when (t) {
    FindingTier.ISSUE -> "工具静态判定的配置问题，建议修正。"
    FindingTier.REVIEW -> "工具无法判定是否合规，请核对：是否已声明用途 / 场景化申请 / 同意后调用。"
}

@Composable
fun ScanScreen() {
    var inputApk by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ScanReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<FindingTier?>(null) }
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
            val byTier = r.findings.groupBy { it.tier() }
            Divider(Modifier.padding(vertical = 8.dp))
            // 概览：两层计数（需整改 / 需自查），点击按层筛选。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in FindingTier.entries) {
                    val c = byTier[t]?.size ?: 0
                    val on = filter == t
                    Surface(color = if (on) tierColor(t) else tierColor(t).copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable { filter = if (on) null else t }) {
                        Text("${t.label} $c", color = if (on) Color.White else tierColor(t),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Text("${r.packageName ?: "未知"}　${r.versionName ?: ""}",
                style = MaterialTheme.typography.caption, modifier = Modifier.padding(top = 4.dp))

            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                val tiers = FindingTier.entries.filter { filter == null || it == filter }
                val anyShown = tiers.any { byTier[it]?.isNotEmpty() == true }
                if (!anyShown) {
                    Text("未发现明显风险项。")
                } else {
                    for (t in tiers) {
                        val items = byTier[t] ?: continue
                        // 层标题 + 该层说明
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 12.dp)) {
                            Surface(color = tierColor(t), shape = MaterialTheme.shapes.small) {
                                Text(t.label, color = Color.White, style = MaterialTheme.typography.caption,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            Text(tierNote(t), style = MaterialTheme.typography.caption, color = Color(0xFF777777))
                        }
                        for ((category, group) in items.groupBy { it.category }) {
                            Text(category, style = MaterialTheme.typography.subtitle2,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                            for (f in group) {
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
