package com.apkharden.packager.ui.harden

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.HardenPipeline
import com.apkharden.packager.ui.common.pickFile
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

    val logScroll = rememberScrollState()
    // Keep the newest log line in view as the pipeline appends output.
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("ApkHarden — 基础加固", style = MaterialTheme.typography.h6, modifier = Modifier.padding(bottom = 8.dp))

        // Form section: takes the leftover space and scrolls internally on short windows.
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

            OutlinedTextField(alias, { alias = it }, label = { Text("别名 alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(storePass, { storePass = it }, label = { Text("keystore 密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(keyPass, { keyPass = it }, label = { Text("key 密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        // Pinned action bar: always visible below the (scrollable) form, so the primary action
        // never sits below the fold.
        Button(
            enabled = !running && inputApk.isNotBlank() && outputApk.isNotBlank() && keystore.isNotBlank() && alias.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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

        Divider(Modifier.padding(vertical = 8.dp))
        Text("日志", style = MaterialTheme.typography.subtitle2, modifier = Modifier.padding(bottom = 4.dp))
        // Log pane: a bounded console — grows with output up to a cap, then scrolls. Never eats
        // the whole window.
        Column(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp).verticalScroll(logScroll)) {
            logs.forEach { Text(it, style = MaterialTheme.typography.body2) }
        }
    }
}

@Composable
private fun fileRow(label: String, value: String, onChange: (String) -> Unit, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = onPick) { Text("浏览") }
    }
}
