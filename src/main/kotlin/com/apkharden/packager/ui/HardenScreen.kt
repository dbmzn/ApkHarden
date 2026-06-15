package com.apkharden.packager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.HardenPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
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

    fun pick(title: String, save: Boolean = false): String? {
        val d = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
        d.isVisible = true
        return d.file?.let { File(d.directory, it).absolutePath }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ApkHarden — 基础加固", style = MaterialTheme.typography.h6)

        fileRow("输入 APK", inputApk, { inputApk = it }) {
            pick("选择 APK")?.let { p ->
                inputApk = p
                if (outputApk.isBlank()) outputApk = p.removeSuffix(".apk") + "-hardened.apk"
            }
        }
        fileRow("输出 APK", outputApk, { outputApk = it }) { pick("输出 APK", save = true)?.let { outputApk = it } }
        fileRow("Keystore", keystore, { keystore = it }) { pick("选择 keystore")?.let { keystore = it } }

        OutlinedTextField(alias, { alias = it }, label = { Text("别名 alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(storePass, { storePass = it }, label = { Text("keystore 密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(keyPass, { keyPass = it }, label = { Text("key 密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Button(
            enabled = !running && inputApk.isNotBlank() && outputApk.isNotBlank() && keystore.isNotBlank() && alias.isNotBlank(),
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

        Divider()
        Text("日志", style = MaterialTheme.typography.subtitle2)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
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
