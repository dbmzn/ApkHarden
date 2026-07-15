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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.ProductionHardenPipeline
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
    var showPasswords by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    val logScroll = rememberScrollState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("APK 加固", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onBackground)
        Text("静态守卫注入 · 签名校验 · 反调试 · 16KB 对齐 · V1+V2+V3 重签",
            style = MaterialTheme.typography.caption, color = sem.subtle,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Text(
            "无需修改业务 App，也无需接入 Gradle 插件；选择 APK 和正式签名后直接生成加固包",
            style = MaterialTheme.typography.body2,
            color = sem.advice,
            modifier = Modifier.fillMaxWidth()
                .background(sem.advice.copy(alpha = 0.08f))
                .border(1.dp, sem.advice.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(10.dp),
        )
        Spacer(Modifier.height(10.dp))

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
            passwordField("keystore 密码", storePass, showPasswords) { storePass = it }
            passwordField("key 密码", keyPass, showPasswords) { keyPass = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showPasswords, onCheckedChange = { showPasswords = it })
                Text("显示密码", style = MaterialTheme.typography.body2)
            }
        }

        Button(
            enabled = !running && inputApk.isNotBlank() && outputApk.isNotBlank() &&
                keystore.isNotBlank() && alias.isNotBlank() &&
                storePass.isNotEmpty() && keyPass.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(10.dp),
            elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
            onClick = {
                logs.clear(); running = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            ProductionHardenPipeline.harden(
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
        ) { Text(if (running) "加固中…" else "开始加固并签名") }

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

@Composable
private fun passwordField(
    label: String,
    value: String,
    visible: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    )
}
