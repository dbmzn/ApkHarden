package com.apkharden.packager.ui.adb

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.apkharden.packager.device.AndroidDevice
import com.apkharden.packager.device.DeviceClipboardSession
import com.apkharden.packager.device.DeviceClipboardState
import com.apkharden.packager.ui.inspect.ValueCard
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DeviceClipboardCard(device: AndroidDevice?) {
    key(device?.serial, device?.state) {
        var session by remember { mutableStateOf<DeviceClipboardSession?>(null) }
        var state by remember { mutableStateOf(DeviceClipboardState(message = "选择在线设备后查看手机复制的文字")) }
        var copyMessage by remember { mutableStateOf<String?>(null) }
        val clipboard = LocalClipboardManager.current
        DisposableEffect(Unit) {
            if (device?.state == "device") session = DeviceClipboardSession(device)
            onDispose { session?.close() }
        }
        LaunchedEffect(session) {
            val current = session ?: return@LaunchedEffect
            while (true) {
                state = current.state
                delay(200)
            }
        }
        LaunchedEffect(state.text) { copyMessage = null }
        ValueCard {
            Text("手机剪贴板", style = MaterialTheme.typography.h6)
            Spacer(Modifier.height(8.dp))
            Text("打开本页后每秒读取手机复制的文字或链接，无需启动镜像。离开本页即停止接收。",
                style = MaterialTheme.typography.body2)
            Spacer(Modifier.height(8.dp))
            Text(state.message, color = if (state.connected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
            state.receivedAt?.let {
                Text("最近收到 · ${DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))}",
                    style = MaterialTheme.typography.caption)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.text.orEmpty(), onValueChange = {}, readOnly = true,
                label = { Text("最近收到的文字") },
                placeholder = { Text("请在手机上复制一段文字") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.text.isNullOrEmpty(), onClick = {
                    runCatching { clipboard.setText(AnnotatedString(state.text.orEmpty())) }
                        .onSuccess { copyMessage = "已复制到电脑" }
                        .onFailure { copyMessage = "复制失败：${it.message}" }
                }) { Text("复制到电脑") }
                OutlinedButton(enabled = device?.state == "device", onClick = {
                    session?.close()
                    session = DeviceClipboardSession(device!!)
                    state = DeviceClipboardState()
                    copyMessage = null
                }) { Text("重新连接") }
            }
            copyMessage?.let { Text(it, style = MaterialTheme.typography.body2) }
            Spacer(Modifier.height(8.dp))
            Text("仅在本页临时显示最近收到的文字，不保存历史。图片和文件暂不支持；手机清空剪贴板或限制读取时可能不返回内容，旧文字会保留至离开本页。长文本最多接收约 256 KB。",
                style = MaterialTheme.typography.caption)
        }
    }
}
