package com.apkharden.packager.ui.harden

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.HardenPipeline
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HardenScreen() {
    var inputApk by remember { mutableStateOf("") }
    var outputApk by remember { mutableStateOf("") }
    var keystore by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    val logScroll = rememberScrollState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("基础加固", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onBackground)
        Text("DEX 整体加壳 · 签名校验防二次打包 · V1+V2+V3 重签",
            style = MaterialTheme.typography.caption, color = sem.subtle,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            fileRow("输入 APK", inputApk, { inputApk = it }) {
                pickFile("APK 文件", extensions = listOf("apk"))?.let { p ->
                    inputApk = p
                    if (outputApk.isBlank()) outputApk = p.removeSuffix(".apk") + "-hardened.apk"
                }
            }
            fileRow("输出 APK", outputApk, { outputApk = it }) {
                pickFile("APK 文件", save = true, extensions = listOf("apk"))?.let { outputApk = it }
            }
            fileRow("Keystore", keystore, { keystore = it }) {
                pickFile("Keystore", extensions = listOf("jks", "keystore", "p12", "bks"))?.let { keystore = it }
            }

            field("别名 alias", alias) { alias = it }
            field("keystore 密码", storePass) { storePass = it }
            field("key 密码", keyPass) { keyPass = it }
        }

        Button(
            enabled = !running && inputApk.isNotBlank() && outputApk.isNotBlank() && keystore.isNotBlank() && alias.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(10.dp),
            elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
            onClick = {
                logs.clear(); running = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            HardenPipeline.harden(
                                input = File(inputApk), output = File(outputApk),
                                keystore = File(keystore), storePass = storePass, alias = alias, keyPass = keyPass,
                                log = { line -> scope.launch { logs.add(line) } },
                            )
                        }
                        logs.add("✅ 加固成功")
                    } catch (e: Throwable) {
                        logs.add("❌ 失败: ${e.message}")
                    } finally {
                        running = false
                    }
                }
            },
        ) { Text(if (running) "加固中…" else "开始加固") }

        Text("日志", style = MaterialTheme.typography.subtitle2, color = sem.subtle,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(sem.cardBg)
                .border(1.dp, sem.cardBorder, RoundedCornerShape(10.dp))
                .verticalScroll(logScroll)
                .padding(12.dp),
        ) {
            if (logs.isEmpty()) {
                Text("等待开始…", style = MaterialTheme.typography.caption, color = sem.subtle)
            } else {
                logs.forEach { line ->
                    val color = when {
                        line.startsWith("✅") -> sem.advice
                        line.startsWith("❌") -> MaterialTheme.colors.error
                        else -> MaterialTheme.colors.onSurface
                    }
                    Text(line, style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                        color = color)
                }
            }
        }
    }
}

@Composable
private fun fileRow(label: String, value: String, onChange: (String) -> Unit, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp))
        OutlinedButton(onClick = onPick, shape = RoundedCornerShape(10.dp)) { Text("浏览") }
    }
}

@Composable
private fun field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
}
