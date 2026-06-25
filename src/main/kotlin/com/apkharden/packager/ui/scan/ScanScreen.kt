package com.apkharden.packager.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkharden.packager.scanner.PrivacyScanner
import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.FindingTier
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import com.apkharden.packager.scanner.model.tier
import com.apkharden.packager.scanner.report.ReportExporter
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import com.apkharden.packager.ui.theme.SemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun sevLabel(s: Severity) = when (s) {
    Severity.HIGH -> "高"; Severity.MEDIUM -> "中"; Severity.LOW -> "低"; Severity.INFO -> "提示"
}
private fun sevColor(sem: SemanticColors, s: Severity) = when (s) {
    Severity.HIGH -> sem.sevHigh; Severity.MEDIUM -> sem.sevMedium
    Severity.LOW -> sem.sevLow; Severity.INFO -> sem.sevInfo
}
private fun tierColor(sem: SemanticColors, t: FindingTier) =
    if (t == FindingTier.ISSUE) sem.tierIssue else sem.tierReview
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
    val sem = LocalSemantic.current

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("隐私合规扫描", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onBackground)
        Text("纯静态自查 · 权限 / 第三方SDK / 敏感API调用点 / 合规配置",
            style = MaterialTheme.typography.caption, color = sem.subtle,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

        // 输入卡片
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                inputApk, { inputApk = it }, label = { Text("输入 APK") }, singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            )
            OutlinedButton(
                onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let { inputApk = it } },
                shape = RoundedCornerShape(10.dp),
            ) { Text("浏览") }
            Button(
                enabled = !running && inputApk.isNotBlank(),
                onClick = {
                    running = true; report = null; error = null; filter = null
                    scope.launch {
                        try {
                            report = withContext(Dispatchers.IO) { PrivacyScanner.scan(File(inputApk)) }
                        } catch (e: Throwable) {
                            error = e.message ?: e.toString()
                        } finally {
                            running = false
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
                elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
            ) { Text(if (running) "扫描中…" else "开始扫描") }
        }

        error?.let {
            Text("扫描失败：$it", color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.body2, modifier = Modifier.padding(top = 12.dp))
        }

        val r = report
        if (r != null) {
            val byTier = r.findings.groupBy { it.tier() }
            Spacer(Modifier.height(16.dp))

            // 概览：两层指标卡（点击筛选）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                for (t in FindingTier.entries) {
                    TierMetric(
                        label = t.label, count = byTier[t]?.size ?: 0,
                        color = tierColor(sem, t), selected = filter == t,
                        modifier = Modifier.weight(1f),
                    ) { filter = if (filter == t) null else t }
                }
            }
            Text("${r.packageName ?: "未知"}  ·  ${r.versionName ?: ""}",
                style = MaterialTheme.typography.caption, color = sem.subtle,
                modifier = Modifier.padding(top = 10.dp))

            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                val tiers = FindingTier.entries.filter { filter == null || it == filter }
                if (tiers.none { byTier[it]?.isNotEmpty() == true }) {
                    EmptyState(sem)
                } else {
                    for (t in tiers) {
                        val items = byTier[t] ?: continue
                        TierHeader(t.label, tierNote(t), tierColor(sem, t))
                        for ((category, group) in items.groupBy { it.category }) {
                            Text(category, style = MaterialTheme.typography.subtitle2,
                                color = MaterialTheme.colors.onBackground,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                            for (f in group) FindingCard(f, sem)
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    pickFile("Markdown", save = true, extensions = listOf("md"))?.let {
                        File(it).writeText(ReportExporter.toMarkdown(r))
                    }
                }, shape = RoundedCornerShape(10.dp)) { Text("导出 Markdown") }
                OutlinedButton(onClick = {
                    pickFile("HTML", save = true, extensions = listOf("html"))?.let {
                        File(it).writeText(ReportExporter.toHtml(r))
                    }
                }, shape = RoundedCornerShape(10.dp)) { Text("导出 HTML") }
            }
        }
    }
}

@Composable
private fun TierMetric(
    label: String, count: Int, color: Color, selected: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, if (selected) color else color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.caption, color = color)
        Text("$count", color = color,
            style = MaterialTheme.typography.h6.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun TierHeader(label: String, note: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp)) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium))
        }
        Text(note, style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle)
    }
}

@Composable
private fun FindingCard(f: Finding, sem: SemanticColors) {
    val c = sevColor(sem, f.severity)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.16f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) { Text(sevLabel(f.severity), color = c, style = MaterialTheme.typography.caption) }
        Column(Modifier.weight(1f)) {
            Row {
                Text(f.title, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface)
                f.location?.let {
                    Text("  @$it", style = MaterialTheme.typography.caption, color = sem.subtle,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            Text(f.detail, style = MaterialTheme.typography.caption, color = sem.subtle,
                modifier = Modifier.padding(top = 2.dp))
            Text("→ ${f.advice}", style = MaterialTheme.typography.caption, color = sem.advice,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun EmptyState(sem: SemanticColors) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("未发现明显风险项", style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.onBackground)
        Text("权限 / SDK / 敏感调用 / 合规配置均通过静态自查", style = MaterialTheme.typography.caption,
            color = sem.subtle, modifier = Modifier.padding(top = 4.dp))
    }
}
