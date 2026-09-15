package com.apkharden.packager.ui.adb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.apkharden.packager.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WirelessAdbDialog(selected: AndroidDevice?, onDismiss: () -> Unit, onChanged: (String?) -> Unit) {
    var pairingAddress by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connectAddress by remember { mutableStateOf("") }
    var services by remember { mutableStateOf<List<WirelessService>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> String) {
        busy = true; failure = false; message = "正在执行…"
        scope.launch {
            try { message = block() }
            catch (e: Exception) { failure = true; message = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { WirelessAdb.version() } }
            .onSuccess { version = it }.onFailure { message = it.message.orEmpty(); failure = true }
    }
    DialogWindow(onCloseRequest = { if (!busy) onDismiss() }, title = "无线 ADB 连接",
        state = rememberDialogState(width = 720.dp, height = 780.dp)) {
        Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("同一 Wi-Fi 无线连接", style = MaterialTheme.typography.h6)
                Text("手机和电脑连接同一 Wi-Fi。Android 17 配合 ADB 37+ 使用 Wi-Fi 2.0；Android 11–16 支持普通无线配对。", style = MaterialTheme.typography.body2)
                Text(version, style = MaterialTheme.typography.caption)
                Text("Android 11+：开发者选项 → 无线调试 → 使用配对码配对")
                OutlinedTextField(pairingAddress, { pairingAddress = it }, label = { Text("配对地址 IP:端口（配对弹窗内）") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it }, label = { Text("6 位配对码") }, enabled = !busy, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                    Button(enabled = !busy, onClick = {
                        val pairingCode = code; code = ""
                        action {
                            val result = withContext(Dispatchers.IO) { WirelessAdb.pair(pairingAddress, pairingCode) }
                            onChanged(null)
                            result
                        }
                    }) { Text("配对") }
                }
                Text("配对端口和连接端口通常不同。配对后未自动连接时，填写无线调试首页的 IP 和端口。", style = MaterialTheme.typography.caption)
                OutlinedTextField(connectAddress, { connectAddress = it }, label = { Text("连接地址 IP:端口") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { action {
                        val serial = withContext(Dispatchers.IO) { WirelessAdb.connect(connectAddress) }
                        onChanged(serial); "已连接 $serial，可以关闭此窗口使用设备工具"
                    } }) { Text("连接") }
                    OutlinedButton(enabled = !busy, onClick = { action {
                        services = withContext(Dispatchers.IO) { WirelessAdb.discover() }
                        if (services.isEmpty()) "未发现设备，请开启无线调试。路由器禁用设备发现时可手动填写地址。" else "发现 ${services.size} 个服务，请选择配对或连接地址"
                    } }) { Text("发现设备") }
                    OutlinedButton(enabled = !busy && selected != null && isWirelessSerial(selected.serial), onClick = { action {
                        withContext(Dispatchers.IO) { WirelessAdb.disconnect(selected!!.serial) }
                        onChanged(null); "已断开所选设备的无线连接"
                    } }) { Text("断开所选设备") }
                }
                services.forEach { service ->
                    TextButton(enabled = !busy, onClick = {
                        if (service.type == "_adb-tls-pairing._tcp") pairingAddress = service.address else connectAddress = service.address
                    }) { Text("${if (service.type == "_adb-tls-pairing._tcp") "配对" else "连接"} · ${service.name} · ${service.address}") }
                }
                Divider()
                Text("旧手机：先通过 USB 开启无线连接", style = MaterialTheme.typography.subtitle1)
                Text("当前设备：${selected?.let { "${it.model} · ${it.serial}" } ?: "未选择"}", style = MaterialTheme.typography.caption)
                Text("适用于 Android 10 及更早版本。先连接 USB 并允许调试，连接成功后可拔线。旧版会在手机开启未加密的 TCP 5555 调试端口，请仅在可信 Wi-Fi 使用，用完可关闭。", style = MaterialTheme.typography.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && selected?.state == "device" && !isWirelessSerial(selected.serial), onClick = { action {
                        val serial = withContext(Dispatchers.IO) { WirelessAdb.enableLegacy(selected!!) }
                        connectAddress = serial; onChanged(serial); "已通过 Wi-Fi 连接 $serial，现在可以拔掉 USB 线"
                    } }) { Text("USB 转 Wi-Fi") }
                    OutlinedButton(enabled = !busy && selected?.state == "device", onClick = { action {
                        val result = withContext(Dispatchers.IO) { WirelessAdb.disableLegacy(selected!!) }
                        onChanged(null); result
                    } }) { Text("关闭旧版无线调试") }
                }
                if (message.isNotBlank()) Text(message, color = if (failure) MaterialTheme.colors.error else MaterialTheme.colors.primary)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
