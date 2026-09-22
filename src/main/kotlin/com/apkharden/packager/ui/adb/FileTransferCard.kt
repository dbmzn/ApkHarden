package com.apkharden.packager.ui.adb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.apkharden.packager.device.*
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.*
import java.io.File
import java.awt.Desktop
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal enum class TransferPhase { ACTIVE, DONE, FAILED, CANCELLED }
internal data class TransferMessage(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val outgoing: Boolean,
    val time: String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
    val phase: TransferPhase = TransferPhase.ACTIVE,
    val detail: String,
    val localFile: File? = null,
)

@Composable
internal fun FileTransferCard(device: AndroidDevice?, history: SnapshotStateList<TransferMessage>, modifier: Modifier = Modifier) {
    key(device?.serial, device?.state) {
        val scope = rememberCoroutineScope()
        val service = remember { device?.let(::AdbFileTransfer) }
        var targetDirectory by remember { mutableStateOf("/sdcard/Download") }
        var browserOpen by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var job by remember { mutableStateOf<Job?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        val online = device?.state == "device"
        val sem = LocalSemantic.current
        val listState = rememberLazyListState()
        fun transfer(name: String, outgoing: Boolean, action: () -> Pair<String, File?>) {
            val message = TransferMessage(name = name, outgoing = outgoing, detail = if (outgoing) "正在发送到手机…" else "正在接收到电脑…")
            history.add(message); busy = true; notice = null
            fun update(phase: TransferPhase, detail: String, localFile: File? = null) {
                val index = history.indexOfFirst { it.id == message.id }
                if (index >= 0) history[index] = message.copy(phase = phase, detail = detail, localFile = localFile)
            }
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = runInterruptible(Dispatchers.IO) { action() }
                    update(TransferPhase.DONE, result.first, result.second)
                } catch (e: CancellationException) {
                    update(TransferPhase.CANCELLED, "已取消传输"); throw e
                } catch (e: Exception) {
                    update(TransferPhase.FAILED, e.message ?: "传输失败，请检查设备连接")
                } finally { busy = false }
            }
        }
        LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.lastIndex) }
        if (browserOpen && online) DeviceFileBrowser(
            service = service!!,
            initialPath = targetDirectory,
            onDismiss = { browserOpen = false },
            onSelectDirectory = { targetDirectory = it; browserOpen = false },
            onDownload = { remote, name ->
                val destination = pickFile("保存 $name 到电脑", save = true, defaultName = name)
                if (destination != null) {
                    browserOpen = false
                    transfer(name, false) {
                        val saved = service.download(remote, File(destination))
                        "已保存到 ${saved.parent}" to saved
                    }
                }
            },
        )
        Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(16.dp))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                FileBadge("↔", true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("文件传输助手", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
                    Text(device?.model ?: "等待连接手机", style = MaterialTheme.typography.caption, color = sem.subtle)
                }
                Text(if (online) "● 已连接" else "○ 未连接", color = if (online) MaterialTheme.colors.primary else sem.subtle,
                    style = MaterialTheme.typography.caption)
            }
            Divider(color = sem.cardBorder)
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colors.background.copy(alpha = .55f))) {
                if (history.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FileBadge("↔", true)
                    Text("电脑与手机，文件随手传", style = MaterialTheme.typography.subtitle1)
                    Text("发送的文件在右侧，接收的文件在左侧", color = sem.subtle, style = MaterialTheme.typography.body2)
                    Text("选择电脑文件发送，或浏览手机文件接收", color = sem.subtle, style = MaterialTheme.typography.caption)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                    contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(history, key = { it.id }) { message ->
                        TransferBubble(message, onOpen = { file ->
                            runCatching { Desktop.getDesktop().open(file.parentFile) }.onFailure { notice = "无法打开目录：${it.message}" }
                        })
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 3.dp))
            }
            Divider(color = sem.cardBorder)
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(targetDirectory, { targetDirectory = it }, enabled = online && !busy,
                        label = { Text("手机接收目录") }, singleLine = true, modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.body2)
                    OutlinedButton(enabled = online && !busy, onClick = { browserOpen = true }, modifier = Modifier.height(40.dp)) { Text("浏览手机文件") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(notice ?: if (busy) "文件传输中，请保持设备连接" else "单文件传输 · 同名不覆盖 · 记录仅保留在本次工具会话",
                            color = sem.subtle, style = MaterialTheme.typography.caption)
                        if (busy) LinearProgressIndicator(Modifier.padding(top = 6.dp, end = 20.dp).fillMaxWidth())
                    }
                    if (busy) TextButton(onClick = { job?.cancel() }) { Text("取消传输") }
                    Button(enabled = online && !busy, modifier = Modifier.height(40.dp), onClick = {
                        val selected = pickFile("选择要发送到手机的文件") ?: return@Button
                        val directory = targetDirectory
                        transfer(File(selected).name, true) {
                            val target = service!!.upload(File(selected), directory)
                            "已发送到 $target" to File(selected)
                        }
                    }) { Text("选择文件发送") }
                }
            }
        }
    }
}

@Composable
private fun FileBadge(label: String, folder: Boolean = false) {
    val color = if (folder) MaterialTheme.colors.primary else MaterialTheme.colors.secondary
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        Text(label.take(3).uppercase(), color = color, maxLines = 1, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TransferBubble(message: TransferMessage, onOpen: (File) -> Unit) {
    val sem = LocalSemantic.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        Text("${if (message.outgoing) "电脑 → 手机" else "手机 → 电脑"}  ·  ${message.time}", color = sem.subtle,
            style = MaterialTheme.typography.caption, modifier = Modifier.padding(bottom = 6.dp))
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(.78f).clip(RoundedCornerShape(12.dp))
            .background(if (message.outgoing) MaterialTheme.colors.primary.copy(alpha = .10f) else sem.cardBg)
            .border(1.dp, if (message.outgoing) MaterialTheme.colors.primary.copy(alpha = .22f) else sem.cardBorder, RoundedCornerShape(12.dp))
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileBadge(message.name.substringAfterLast('.', "FILE"))
                Spacer(Modifier.width(12.dp))
                Text(message.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body2, fontWeight = FontWeight.Medium)
            }
            Text(message.detail, style = MaterialTheme.typography.caption,
                color = if (message.phase == TransferPhase.FAILED) MaterialTheme.colors.error else sem.subtle)
            if (message.phase == TransferPhase.ACTIVE) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.phase == TransferPhase.DONE && message.localFile != null) {
                TextButton(onClick = { onOpen(message.localFile) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(28.dp)) {
                    Text(if (message.outgoing) "打开源文件目录" else "打开保存目录", style = MaterialTheme.typography.caption)
                }
            }
        }
    }
}

@Composable
private fun DeviceFileBrowser(service: AdbFileTransfer, initialPath: String, onDismiss: () -> Unit,
    onSelectDirectory: (String) -> Unit, onDownload: (String, String) -> Unit) {
    var path by remember { mutableStateOf(initialPath) }
    var loaded by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<DeviceFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current
    val listState = rememberLazyListState()
    fun browse(target: String) {
        busy = true; error = null
        scope.launch {
            try {
                val normalized = devicePath(target)
                val result = runInterruptible(Dispatchers.IO) { service.list(normalized) }
                files = result; path = normalized; loaded = normalized
                listState.scrollToItem(0)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "目录读取失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { browse(initialPath) }
    DialogWindow(onCloseRequest = onDismiss, title = "手机文件", state = rememberDialogState(width = 780.dp, height = 620.dp)) {
        Surface(color = sem.cardBg) {
            Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("手机文件", style = MaterialTheme.typography.h6)
                Text("选择文件接收到电脑，或将当前目录设为手机接收目录", style = MaterialTheme.typography.body2, color = sem.subtle)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(path, { path = it }, enabled = !busy, singleLine = true, label = { Text("目录路径") }, modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = !busy, onClick = { browse(path) }) { Text("前往") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { browse("${loaded ?: path}/..") }) { Text("↑ 上一级") }
                    TextButton(enabled = !busy, onClick = { browse("/sdcard/Download") }) { Text("下载目录") }
                    TextButton(enabled = !busy, onClick = { browse("/sdcard/DCIM") }) { Text("相机目录") }
                    Spacer(Modifier.weight(1f))
                    Text("${files.size} 个条目", style = MaterialTheme.typography.caption, color = sem.subtle)
                }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("名称", Modifier.weight(1f), style = MaterialTheme.typography.caption, color = sem.subtle)
                    Text("类型", Modifier.width(72.dp), style = MaterialTheme.typography.caption, color = sem.subtle)
                    Text("操作", Modifier.width(88.dp), style = MaterialTheme.typography.caption, color = sem.subtle)
                }
                Divider(color = sem.cardBorder)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                        items(files, key = { it.name }) { entry ->
                            Column {
                                Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FileBadge(if (entry.directory) "DIR" else entry.name.substringAfterLast('.', "FILE"), entry.directory)
                                    Spacer(Modifier.width(12.dp))
                                    Text(entry.name, Modifier.weight(1f).padding(end = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.body2)
                                    Text(if (entry.directory) "文件夹" else "文件", Modifier.width(72.dp), style = MaterialTheme.typography.caption, color = sem.subtle)
                                    OutlinedButton(enabled = !busy, modifier = Modifier.width(88.dp).height(34.dp), onClick = {
                                        val remote = "${loaded!!}/${entry.name}"
                                        if (entry.directory) browse(remote) else onDownload(remote, entry.name)
                                    }) { Text(if (entry.directory) "打开" else "接收") }
                                }
                                Divider(color = sem.cardBorder.copy(alpha = .5f))
                            }
                        }
                    }
                    if (files.isEmpty() && !busy) Text(if (error == null) "此目录为空" else "无法加载目录", Modifier.align(Alignment.Center), color = sem.subtle)
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仅显示有读取权限的文件和目录", Modifier.weight(1f), color = sem.subtle, style = MaterialTheme.typography.caption)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Button(enabled = !busy && loaded != null && path == loaded, onClick = { onSelectDirectory(loaded!!) }) { Text("设为手机接收目录") }
                }
            }
        }
    }
}
