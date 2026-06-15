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
import java.awt.Dimension
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

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

    fun pick(
        title: String,
        save: Boolean = false,
        filterDesc: String? = null,
        extensions: List<String> = emptyList(),
    ): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            preferredSize = Dimension(900, 600) // larger than the default ~500×330
            isFileHidingEnabled = false
            if (filterDesc != null && extensions.isNotEmpty()) {
                fileFilter = FileNameExtensionFilter(filterDesc, *extensions.toTypedArray())
            }
        }
        val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }

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
                pick("选择 APK", filterDesc = "APK 文件 (*.apk)", extensions = listOf("apk"))?.let { p ->
                    inputApk = p
                    if (outputApk.isBlank()) outputApk = p.removeSuffix(".apk") + "-hardened.apk"
                }
            }
            fileRow("输出 APK", outputApk, { outputApk = it }) {
                pick("输出 APK", save = true, filterDesc = "APK 文件 (*.apk)", extensions = listOf("apk"))?.let { outputApk = it }
            }
            fileRow("Keystore", keystore, { keystore = it }) {
                pick("选择 keystore", filterDesc = "Keystore (*.jks, *.keystore, *.p12, *.bks)",
                    extensions = listOf("jks", "keystore", "p12", "bks"))?.let { keystore = it }
            }

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
        }

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
