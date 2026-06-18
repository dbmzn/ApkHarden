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
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NFDFilterItem
import org.lwjgl.util.nfd.NativeFileDialog.NFD_FreePath
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Init
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OpenDialog
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit
import org.lwjgl.util.nfd.NativeFileDialog.NFD_SaveDialog
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

    // Drive the OS-native file dialog through LWJGL's NFD binding. On Windows this is the modern
    // IFileOpenDialog — the full resizable Explorer dialog with the Quick Access sidebar — instead
    // of Swing's JFileChooser or AWT's tiny legacy GetOpenFileName window.
    fun pick(
        filterName: String? = null,
        save: Boolean = false,
        extensions: List<String> = emptyList(),
    ): String? {
        NFD_Init()
        try {
            MemoryStack.stackPush().use { stack ->
                val outPath = stack.mallocPointer(1)
                val filters = if (extensions.isNotEmpty()) {
                    val items = NFDFilterItem.malloc(1, stack)
                    // spec is a comma-separated extension list, e.g. "jks,keystore,p12,bks".
                    items[0].name(stack.UTF8(filterName ?: "支持的文件"))
                        .spec(stack.UTF8(extensions.joinToString(",")))
                    items
                } else null
                val result = if (save)
                    NFD_SaveDialog(outPath, filters, null as CharSequence?, null as CharSequence?)
                else
                    NFD_OpenDialog(outPath, filters, null as CharSequence?)
                if (result != NFD_OKAY) return null
                val path = outPath.getStringUTF8(0)
                NFD_FreePath(outPath.get(0))
                return path
            }
        } finally {
            NFD_Quit()
        }
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
                pick("APK 文件", extensions = listOf("apk"))?.let { p ->
                    inputApk = p
                    if (outputApk.isBlank()) outputApk = p.removeSuffix(".apk") + "-hardened.apk"
                }
            }
            fileRow("输出 APK", outputApk, { outputApk = it }) {
                pick("APK 文件", save = true, extensions = listOf("apk"))?.let { outputApk = it }
            }
            fileRow("Keystore", keystore, { keystore = it }) {
                pick("Keystore", extensions = listOf("jks", "keystore", "p12", "bks"))?.let { keystore = it }
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
