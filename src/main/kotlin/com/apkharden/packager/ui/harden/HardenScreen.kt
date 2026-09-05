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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.ProductionHardenPipeline
import com.apkharden.packager.signing.SigningProfile
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HardenScreen(
    signingProfile: SigningProfile?,
    onConfigureSigning: () -> Unit,
) {
    var inputApk by remember { mutableStateOf("") }
    var outputApk by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    val logScroll = rememberScrollState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colors.primary.copy(alpha = .20f),
                            MaterialTheme.colors.secondary.copy(alpha = .08f))
                    )
                )
                .border(1.dp, MaterialTheme.colors.primary.copy(alpha = .28f), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("APK 加固", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("核心能力", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colors.primary.copy(alpha = .12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Text("无需修改业务工程，一次完成 DEX 加密、Native 壳保护、16KB 对齐与正式签名",
                    style = MaterialTheme.typography.body2, color = sem.subtle)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("AES-256-GCM", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
                Text("V1 + V2 + V3", style = MaterialTheme.typography.caption, color = sem.subtle)
            }
        }
        Spacer(Modifier.height(16.dp))

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
            SigningProfileCard(profile = signingProfile, onConfigure = onConfigureSigning)
            Text(
                "加固后会在输出目录同时生成 JSON 发布报告；原始 APK 不会被修改。",
                style = MaterialTheme.typography.caption,
                color = sem.advice,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(sem.advice.copy(alpha = .07f)).padding(11.dp),
            )
        }

        Button(
            enabled = !running && inputApk.isNotBlank() && outputApk.isNotBlank() &&
                signingProfile != null && File(signingProfile.keystorePath).isFile,
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
                                keystore = File(signingProfile!!.keystorePath),
                                storePass = signingProfile.storePassword,
                                alias = signingProfile.alias,
                                keyPass = signingProfile.keyPassword,
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
private fun SigningProfileCard(profile: SigningProfile?, onConfigure: () -> Unit) {
    val sem = LocalSemantic.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("签名配置", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onSurface)
            if (profile == null) {
                Text("尚未配置，请先在签名工具中保存正式签名", style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.error)
            } else {
                Text(
                    "${File(profile.keystorePath).name}  ·  alias: ${profile.alias}",
                    style = MaterialTheme.typography.body2,
                    color = sem.subtle,
                )
                if (!File(profile.keystorePath).isFile) {
                    Text("签名文件不存在，请重新配置", style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.error)
                }
            }
        }
        OutlinedButton(onClick = onConfigure, shape = RoundedCornerShape(10.dp)) {
            Text(if (profile == null) "去配置" else "修改")
        }
    }
}

@Composable
private fun fileRow(label: String, value: String, onChange: (String) -> Unit, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = hardenFieldColors())
        OutlinedButton(onClick = onPick, shape = RoundedCornerShape(10.dp)) { Text("浏览") }
    }
}

@Composable
private fun hardenFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = MaterialTheme.colors.onSurface,
    cursorColor = MaterialTheme.colors.primary,
    focusedBorderColor = MaterialTheme.colors.primary,
    unfocusedBorderColor = LocalSemantic.current.subtle,
    focusedLabelColor = MaterialTheme.colors.primary,
    unfocusedLabelColor = LocalSemantic.current.subtle,
)
