package com.apkharden.packager.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apkharden.device.AdbClient
import com.apkharden.device.CommandResult
import com.apkharden.device.DataPreservationTarget
import com.apkharden.device.DeviceProfile
import com.apkharden.device.ProcessCommandExecutor
import com.apkharden.device.RuntimeSmokeRunner
import com.apkharden.device.SmokeResult
import com.apkharden.device.SmokeTarget
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ConnectedDevice(val serial: String, val label: String)

@Composable
fun DeviceScreen() {
    var adbPath by remember { mutableStateOf("C:/AndroidSdk/platform-tools/adb.exe") }
    var devices by remember { mutableStateOf<List<ConnectedDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<ConnectedDevice?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var apk by remember { mutableStateOf("") }
    var oldApk by remember { mutableStateOf("") }
    var newApk by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("") }
    var mainProbe by remember { mutableStateOf("") }
    var dataProbe by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { listDevices(adbPath) }
                devices = found
                selected = found.firstOrNull { it.serial == selected?.serial } ?: found.firstOrNull()
                error = null
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun execute(action: suspend (AdbClient) -> String) {
        val device = selected
        if (device == null) {
            error = "请先刷新并选择一台已连接真机"
            return
        }
        running = true
        error = null
        output = null
        scope.launch {
            try {
                output = withContext(Dispatchers.IO) {
                    action(AdbClient(adbPath, device.serial))
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("真机验证", style = MaterialTheme.typography.h6)
                Text("选择 adb 设备并执行运行门禁", style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle)
            }
            if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(adbPath, { adbPath = it }, label = { Text("adb 路径") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = ::refresh, enabled = !running) {
                Icon(Icons.Default.Refresh, "刷新设备")
                Spacer(Modifier.width(6.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton(onClick = { menuOpen = true }, enabled = !running) {
                Text(selected?.let { "${it.serial}  ${it.label}" } ?: "选择设备")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                devices.forEach { device ->
                    DropdownMenuItem(onClick = { selected = device; menuOpen = false }) {
                        Text("${device.serial}  ${device.label}")
                    }
                }
                if (devices.isEmpty()) DropdownMenuItem(onClick = { menuOpen = false }) { Text("没有已连接设备") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                PathField("测试 APK", apk) { apk = it }
                PathField("旧 APK（数据保留）", oldApk) { oldApk = it }
                PathField("新 APK（数据保留）", newApk) { newApk = it }
                OutlinedTextField(packageName, { packageName = it }, label = { Text("包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(activity, { activity = it }, label = { Text("Activity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mainProbe, { mainProbe = it }, label = { Text("主进程 Probe URI（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dataProbe, { dataProbe = it }, label = { Text("数据 Probe URI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !running, onClick = {
                        execute { adb ->
                            val profile = adb.readProfile()
                            profile.toDisplay()
                        }
                    }) { Icon(Icons.Default.Info, "读取设备信息"); Spacer(Modifier.width(5.dp)); Text("Profile") }
                    Button(enabled = !running && apk.isNotBlank() && packageName.isNotBlank() && activity.isNotBlank(), onClick = {
                        execute { adb ->
                            val result = RuntimeSmokeRunner(adb).verifySurvives(
                                SmokeTarget(apk, packageName, activity, mainProbe.takeIf(String::isNotBlank), null),
                            )
                            result.toDisplay()
                        }
                    }) { Icon(Icons.Default.CheckCircle, "运行存活测试"); Spacer(Modifier.width(5.dp)); Text("存活") }
                }
                Button(
                    enabled = !running && oldApk.isNotBlank() && newApk.isNotBlank() && packageName.isNotBlank() && activity.isNotBlank() && dataProbe.isNotBlank(),
                    onClick = {
                        execute { adb ->
                            RuntimeSmokeRunner(adb).verifyDataPreserved(
                                DataPreservationTarget(oldApk, newApk, packageName, activity, dataProbe),
                            ).toDisplay()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Default.CheckCircle, "运行数据保留测试"); Spacer(Modifier.width(5.dp)); Text("清装 / 覆盖安装") }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(0.54f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text("执行结果", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(8.dp))
                error?.let { MessageBand(it, false) }
                output?.let { MessageBand(it, true) }
            }
        }
    }
}

@Composable
private fun PathField(label: String, value: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let(onChange) }) { Text("选择") }
    }
}

private fun listDevices(adbPath: String): List<ConnectedDevice> {
    val result = ProcessCommandExecutor().execute(listOf(adbPath, "devices", "-l"))
    result.requireSuccess("adb devices")
    return result.stdout.lineSequence().drop(1).mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[1] != "device") return@mapNotNull null
        val label = parts.drop(2).firstOrNull { it.startsWith("model:") }?.removePrefix("model:") ?: "device"
        ConnectedDevice(parts[0], label)
    }.toList()
}

private fun DeviceProfile.toDisplay(): String = "API $apiLevel\nABI: ${abis.joinToString()}\n页大小: $pageSize"

private fun SmokeResult.toDisplay(): String = "passed=$passed\nprocesses=${processIds.joinToString()}\ndataPreserved=$dataPreserved\nanr=$anrDetected\nrestartLoop=$restartLoopDetected"

@Composable
private fun MessageBand(message: String, success: Boolean) {
    val color = if (success) LocalSemantic.current.advice else MaterialTheme.colors.error
    Text(message, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f)).border(1.dp, color.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(12.dp), color = color)
}
