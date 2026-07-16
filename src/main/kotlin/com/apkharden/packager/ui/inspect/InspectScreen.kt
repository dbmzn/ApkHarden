package com.apkharden.packager.ui.inspect

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
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun InspectScreen() {
    var path by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ApkToolboxReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageTitle("APK 体检", "发布前快速检查签名、版本、ABI、DEX 与关键构建开关")
        ToolInputCard {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it; report = null; error = null },
                label = { Text("选择待检查的 APK") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(onClick = {
                pickFile("APK 文件", extensions = listOf("apk"))?.let {
                    path = it; report = null; error = null
                }
            }) { Text("浏览") }
            Button(
                enabled = !running && path.isNotBlank(),
                onClick = {
                    running = true; error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { ApkToolbox.analyze(File(path)) } }
                            .onSuccess { report = it }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        running = false
                    }
                },
            ) { Text(if (running) "检查中…" else "开始体检") }
        }

        error?.let {
            Text("检查失败：$it", color = MaterialTheme.colors.error,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.error.copy(alpha = .08f))
                    .padding(12.dp))
        }

        report?.let { value ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("体检分", "${value.score}", if (value.blockerCount == 0) sem.advice else MaterialTheme.colors.error, Modifier.weight(1f))
                SummaryCard("APK 大小", formatBytes(value.fileSize), MaterialTheme.colors.onSurface, Modifier.weight(1f))
                SummaryCard("DEX", "${value.dexCount} 个", MaterialTheme.colors.onSurface, Modifier.weight(1f))
                SummaryCard("Native", "${value.nativeCount} 个", MaterialTheme.colors.onSurface, Modifier.weight(1f))
            }
            PackageOverview(value)
            SectionTitle("检查结果", "${value.blockerCount} 个阻塞 · ${value.warningCount} 个提醒")
            value.findings.forEach { FindingRow(it) }
            SectionTitle("包体积构成", "解压后统计，展示最大的 12 个文件")
            ValueCard {
                value.largestEntries.forEachIndexed { index, entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text("${index + 1}", color = sem.subtle, modifier = Modifier.width(28.dp))
                        Text(entry.name, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
                        Text(formatBytes(entry.size), style = MaterialTheme.typography.body2, color = sem.subtle)
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageOverview(report: ApkToolboxReport) {
    val info = report.info
    ValueCard {
        Text(info.packageName, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LabelValue("版本号", info.versionCode.toString())
            LabelValue("SDK", "min ${info.minSdk} / target ${info.targetSdk}")
            LabelValue("ABI", info.abis.ifEmpty { setOf("无") }.joinToString())
            LabelValue("签名", if (info.signature.verified) info.signature.schemes.joinToString("+") else "未通过")
            LabelValue("权限/导出", "${info.permissions.size} / ${info.exportedComponents.size}")
        }
    }
}

@Composable
private fun FindingRow(finding: ApkFinding) {
    val sem = LocalSemantic.current
    val color = when (finding.level) {
        FindingLevel.BLOCKER -> MaterialTheme.colors.error
        FindingLevel.WARNING -> sem.sevMedium
        FindingLevel.PASSED -> sem.advice
        FindingLevel.INFO -> MaterialTheme.colors.secondary
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(12.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 4.dp).size(9.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(finding.title, style = MaterialTheme.typography.subtitle2)
            Text(finding.detail, style = MaterialTheme.typography.body2, color = sem.subtle)
        }
        Text(finding.level.label(), style = MaterialTheme.typography.caption, color = color)
    }
}

private fun FindingLevel.label() = when (this) {
    FindingLevel.BLOCKER -> "需处理"
    FindingLevel.WARNING -> "需确认"
    FindingLevel.PASSED -> "通过"
    FindingLevel.INFO -> "信息"
}

@Composable
internal fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.body2, color = LocalSemantic.current.subtle)
    }
}

@Composable
internal fun ToolInputCard(content: @Composable RowScope.() -> Unit) {
    val sem = LocalSemantic.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun ValueCard(content: @Composable ColumnScope.() -> Unit) {
    val sem = LocalSemantic.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(14.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun SummaryCard(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val sem = LocalSemantic.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(sem.cardBg)
        .border(1.dp, sem.cardBorder, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = sem.subtle)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.h6, color = color)
    }
}

@Composable
internal fun SectionTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle)
    }
}

@Composable
internal fun LabelValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle)
        Text(value, style = MaterialTheme.typography.body2)
    }
}
