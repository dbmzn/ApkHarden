package com.apkharden.packager.ui.compare

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
import com.apkharden.packager.core.ApkComparison
import com.apkharden.packager.core.ApkToolbox
import com.apkharden.packager.core.formatBytes
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.inspect.*
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CompareScreen() {
    var oldPath by remember { mutableStateOf("") }
    var newPath by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ApkComparison?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageTitle("APK 对比", "对照新旧版本的签名、版本、SDK、ABI 与包内文件变化")
        ValueCard {
            ApkPicker("旧版本 APK", oldPath) { oldPath = it; result = null; error = null }
            Spacer(Modifier.height(10.dp))
            ApkPicker("新版本 APK", newPath) { newPath = it; result = null; error = null }
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = !running && oldPath.isNotBlank() && newPath.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    running = true; error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { ApkToolbox.compare(File(oldPath), File(newPath)) }
                        }.onSuccess { result = it }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        running = false
                    }
                },
            ) { Text(if (running) "正在对比…" else "开始对比") }
        }
        error?.let { Text("对比失败：$it", color = MaterialTheme.colors.error) }

        result?.let { value ->
            val oldInfo = value.oldReport.info
            val newInfo = value.newReport.info
            val samePackage = oldInfo.packageName == newInfo.packageName
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VerdictCard("包名", if (samePackage) "一致" else "不一致", samePackage, Modifier.weight(1f))
                VerdictCard("签名", if (value.signaturesMatch) "一致" else "不一致", value.signaturesMatch, Modifier.weight(1f))
                DeltaCard("版本号", "${oldInfo.versionCode}  →  ${newInfo.versionCode}", Modifier.weight(1f))
                DeltaCard("包体积", signedBytes(value.newReport.fileSize - value.oldReport.fileSize), Modifier.weight(1f))
            }

            SectionTitle("发布关键信息", "先确认包名与签名，再关注内容变化")
            ValueCard {
                CompareLine("文件", value.oldReport.file.name, value.newReport.file.name)
                CompareLine("SDK", "min ${oldInfo.minSdk} / target ${oldInfo.targetSdk}", "min ${newInfo.minSdk} / target ${newInfo.targetSdk}")
                CompareLine("ABI", oldInfo.abis.joinToString().ifBlank { "无" }, newInfo.abis.joinToString().ifBlank { "无" })
                CompareLine("DEX", "${value.oldReport.dexCount} 个 · ${formatBytes(value.oldReport.dexBytes)}", "${value.newReport.dexCount} 个 · ${formatBytes(value.newReport.dexBytes)}")
                CompareLine("Native", "${value.oldReport.nativeCount} 个 · ${formatBytes(value.oldReport.nativeBytes)}", "${value.newReport.nativeCount} 个 · ${formatBytes(value.newReport.nativeBytes)}")
                CompareLine("权限", "${oldInfo.permissions.size} 项", "${newInfo.permissions.size} 项")
                CompareLine("导出组件", "${oldInfo.exportedComponents.size} 个", "${newInfo.exportedComponents.size} 个")
            }

            SectionTitle("文件变化", "新增 ${value.addedCount} · 删除 ${value.removedCount} · 修改 ${value.modifiedCount}")
            ValueCard {
                if (value.changes.isEmpty()) {
                    Text("两个 APK 的文件清单和解压大小一致", color = sem.advice)
                } else {
                    value.changes.take(30).forEach { change ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            val marker = when {
                                change.oldSize == null -> "+"
                                change.newSize == null -> "−"
                                else -> "~"
                            }
                            Text(marker, color = when (marker) {
                                "+" -> sem.advice
                                "−" -> MaterialTheme.colors.error
                                else -> MaterialTheme.colors.secondary
                            }, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                            Text(change.name, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
                            Text(signedBytes(change.delta), style = MaterialTheme.typography.caption, color = sem.subtle)
                        }
                    }
                    if (value.changes.size > 30) {
                        Text("另有 ${value.changes.size - 30} 项变化未展开", style = MaterialTheme.typography.caption,
                            color = sem.subtle, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkPicker(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            pickFile("APK 文件", extensions = listOf("apk"))?.let(onChange)
        }) { Text("浏览") }
    }
}

@Composable
private fun VerdictCard(label: String, value: String, good: Boolean, modifier: Modifier) {
    val sem = LocalSemantic.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(sem.cardBg)
        .border(1.dp, sem.cardBorder, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = sem.subtle)
        Spacer(Modifier.height(5.dp))
        Text(value, style = MaterialTheme.typography.subtitle1,
            color = if (good) sem.advice else MaterialTheme.colors.error)
    }
}

@Composable
private fun DeltaCard(label: String, value: String, modifier: Modifier) {
    val sem = LocalSemantic.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(sem.cardBg)
        .border(1.dp, sem.cardBorder, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = sem.subtle)
        Spacer(Modifier.height(5.dp))
        Text(value, style = MaterialTheme.typography.subtitle1)
    }
}

@Composable
private fun CompareLine(label: String, oldValue: String, newValue: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle,
            modifier = Modifier.width(80.dp))
        Text(oldValue, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
        Text("→", color = LocalSemantic.current.subtle, modifier = Modifier.padding(horizontal = 14.dp))
        Text(newValue, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
    }
}

private fun signedBytes(bytes: Long): String = when {
    bytes > 0 -> "+${formatBytes(bytes)}"
    bytes < 0 -> "−${formatBytes(-bytes)}"
    else -> "0 B"
}
