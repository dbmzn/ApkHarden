package com.apkharden.packager.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.ApkInspector
import com.apkharden.packager.device.AdbDeviceService
import com.apkharden.packager.device.AndroidDevice
import com.apkharden.packager.device.DeviceLaunchResult
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.inspect.PageTitle
import com.apkharden.packager.ui.inspect.ValueCard
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DeviceScreen() {
    var devices by remember { mutableStateOf<List<AndroidDevice>>(emptyList()) }
    var selectedSerial by remember { mutableStateOf("") }
    var apkPath by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var skipInstall by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<DeviceLaunchResult?>(null) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    fun refresh() {
        refreshing = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { AdbDeviceService.listDevices() } }
                .onSuccess { found ->
                    devices = found
                    if (found.none { it.serial == selectedSerial }) {
                        selectedSerial = found.firstOrNull { it.state == "device" }?.serial.orEmpty()
                    }
                }.onFailure { error = friendly(it) }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    val selected = devices.firstOrNull { it.serial == selectedSerial }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageTitle("设备安装验证", "连接真机或模拟器，一键完成覆盖安装、冷启动和闪退门禁")
            Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = !refreshing && !running, onClick = { refresh() }) {
                Text(if (refreshing) "刷新中…" else "刷新设备")
            }
        }

        if (devices.isEmpty() && !refreshing) {
            ValueCard {
                Text("未发现设备", style = MaterialTheme.typography.subtitle1)
                Spacer(Modifier.height(4.dp))
                Text("请确认已安装 ADB、设备已连接并允许 USB 调试。", color = sem.subtle,
                    style = MaterialTheme.typography.body2)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                devices.forEach { device ->
                    DeviceCard(device, selectedSerial == device.serial, Modifier.weight(1f)) {
                        if (!running && device.state == "device") selectedSerial = device.serial
                    }
                }
            }
        }

        ValueCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(apkPath, { apkPath = it; result = null }, label = { Text("待验证 APK") },
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !running, onClick = {
                    pickFile("APK 文件", extensions = listOf("apk"))?.let { path ->
                        apkPath = path; result = null; error = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { ApkInspector.inspect(File(path)).packageName } }
                                .onSuccess { packageName = it }
                                .onFailure { error = friendly(it) }
                        }
                    }
                }) { Text("浏览") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(packageName, { packageName = it; result = null }, label = { Text("应用包名") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(skipInstall, { skipInstall = it }, enabled = !running)
                Text("跳过安装，仅验证设备上已有版本", style = MaterialTheme.typography.body2)
            }
            Button(
                enabled = !running && selected != null && apkPath.isNotBlank() && packageName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = selected ?: return@Button
                    running = true; error = null; result = null; logs.clear()
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                AdbDeviceService.verifyLaunch(File(apkPath), packageName.trim(), target,
                                    skipInstall = skipInstall) { line -> scope.launch { logs.add(line) } }
                            }
                        }.onSuccess { result = it }
                            .onFailure { error = friendly(it) }
                        running = false
                    }
                },
            ) { Text(if (running) "验证中，请保持设备连接…" else "安装并验证冷启动") }
        }

        result?.let { value ->
            val color = if (value.passed) sem.advice else MaterialTheme.colors.error
            ValueCard {
                Text(if (value.passed) "验证通过" else "验证未通过", color = color,
                    style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("PID ${value.pid.ifBlank { "无" }} · 前台 Activity ${if (value.resumed) "正常" else "未检测到"} · 崩溃日志 ${if (value.crashLog.isBlank()) "无" else "有"}",
                    style = MaterialTheme.typography.body2, color = sem.subtle)
            }
        }
        error?.let {
            Text("验证失败：$it", color = MaterialTheme.colors.error,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colors.error.copy(alpha = .08f)).padding(12.dp))
        }
        if (logs.isNotEmpty()) {
            ValueCard {
                Text("执行日志", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(8.dp))
                logs.forEach { Text(it, style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                    color = sem.subtle, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: AndroidDevice, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sem = LocalSemantic.current
    val accent = if (device.state == "device") MaterialTheme.colors.primary else MaterialTheme.colors.error
    Column(
        modifier.widthIn(min = 210.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = .12f) else sem.cardBg)
            .border(1.dp, if (selected) accent else sem.cardBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(accent))
            Spacer(Modifier.width(7.dp))
            Text(device.model, style = MaterialTheme.typography.subtitle2, modifier = Modifier.weight(1f))
            Text(if (device.state == "device") "在线" else device.state, color = accent,
                style = MaterialTheme.typography.caption)
        }
        Spacer(Modifier.height(7.dp))
        Text("API ${device.api} · ${device.pageSize} bytes", style = MaterialTheme.typography.caption, color = sem.subtle)
        Text(device.abis, style = MaterialTheme.typography.caption, color = sem.subtle, maxLines = 1)
        Text(device.serial, style = MaterialTheme.typography.caption, color = sem.subtle, maxLines = 1)
    }
}

private fun friendly(error: Throwable): String = generateSequence(error) { it.cause }
    .mapNotNull { it.message?.takeIf(String::isNotBlank) }.firstOrNull() ?: error.javaClass.simpleName
