package com.apkharden.packager.ui.manifest

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
fun ManifestScreen() {
    var apkPath by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ManifestReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("Manifest 查看器", "图形化查看权限、四大组件、进程、Deep Link、Provider 与启动 Activity")
        ValueCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(apkPath, { apkPath = it; report = null }, label = { Text("APK 文件") },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedButton(onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let { apkPath = it; report = null } }) { Text("浏览") }
                Button(enabled = !running && apkPath.isNotBlank(), onClick = {
                    running = true; error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { ManifestExplorer.analyze(File(apkPath)) } }
                            .onSuccess { report = it }.onFailure { error = friendly(it) }
                        running = false
                    }
                }) { Text(if (running) "解析中…" else "解析 Manifest") }
            }
        }
        error?.let { Text("解析失败：$it", color = MaterialTheme.colors.error) }
        report?.let { value ->
            ValueCard {
                Text(value.packageName, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(9.dp))
                InfoLine("Application", value.application.ifBlank { "默认 Application" })
                InfoLine("启动 Activity", value.mainActivity.ifBlank { "未识别" })
                InfoLine("进程", value.processes.joinToString())
                InfoLine("权限 / 组件 / Deep Link", "${value.permissions.size} / ${value.components.size} / ${value.deepLinks.size}")
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索权限、组件、进程、Deep Link") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            val normalized = query.trim().lowercase()
            val permissions = value.permissions.filter { normalized.isBlank() || it.lowercase().contains(normalized) }
            val components = value.components.filter { component ->
                normalized.isBlank() || listOf(component.type, component.name, component.process, component.permission,
                    component.authorities, component.deepLinks.joinToString()).any { it.lowercase().contains(normalized) }
            }
            SectionTitle("权限", "${permissions.size} 项")
            ValueCard {
                if (permissions.isEmpty()) Text("没有匹配的权限", color = sem.subtle)
                permissions.forEach { permission ->
                    Text(permission, style = MaterialTheme.typography.body2, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            SectionTitle("组件", "${components.size} 项 · ${components.count { it.risk != ManifestRisk.NORMAL }} 项需关注")
            components.forEach { ComponentCard(it) }
            if (value.deepLinks.isNotEmpty()) {
                SectionTitle("Deep Link", "${value.deepLinks.size} 项")
                ValueCard { value.deepLinks.filter { normalized.isBlank() || it.lowercase().contains(normalized) }
                    .forEach { Text(it, style = MaterialTheme.typography.body2, modifier = Modifier.padding(vertical = 4.dp)) } }
            }
        }
    }
}

@Composable
private fun ComponentCard(component: ManifestComponent) {
    val sem = LocalSemantic.current
    val riskColor = when (component.risk) {
        ManifestRisk.HIGH -> MaterialTheme.colors.error
        ManifestRisk.REVIEW -> sem.sevMedium
        ManifestRisk.NORMAL -> sem.advice
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(sem.cardBg)
        .border(1.dp, if (component.risk == ManifestRisk.NORMAL) sem.cardBorder else riskColor.copy(alpha = .6f), RoundedCornerShape(12.dp))
        .padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(component.type, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption,
                modifier = Modifier.width(92.dp))
            Text(component.name, style = MaterialTheme.typography.subtitle2, modifier = Modifier.weight(1f))
            Text(if (component.exported) "exported" else "内部", color = if (component.exported) riskColor else sem.subtle,
                style = MaterialTheme.typography.caption)
        }
        if (component.process.isNotBlank()) InfoLine("进程", component.process)
        if (component.permission.isNotBlank()) InfoLine("权限", component.permission)
        if (component.authorities.isNotBlank()) InfoLine("Authorities", component.authorities)
        component.deepLinks.forEach { InfoLine("Deep Link", it) }
        if (component.riskReason.isNotBlank()) {
            Text(component.riskReason, color = riskColor, style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Text(label, color = LocalSemantic.current.subtle, style = MaterialTheme.typography.caption, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
    }
}

private fun friendly(error: Throwable): String = generateSequence(error) { it.cause }
    .mapNotNull { it.message?.takeIf(String::isNotBlank) }.firstOrNull() ?: error.javaClass.simpleName
