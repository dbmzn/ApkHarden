package com.apkharden.packager.ui.image

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.image.*
import com.apkharden.packager.ui.common.*
import com.apkharden.packager.ui.developer.ChoiceMenu
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal fun imageBytes(size: Long): String = if (size < 1024 * 1024) "%.1f KB".format(size / 1024.0) else "%.2f MB".format(size / 1048576.0)

internal fun imageChange(saved: Long, percent: Double): String = if (saved >= 0) "减少 ${"%.1f".format(percent)}%" else "增加 ${"%.1f".format(-percent)}%"

@Composable
internal fun ImageWorkspace(state: ImageWorkspaceState) {
    val sem = LocalSemantic.current
    if (state.showEditor && state.selected != null) { ImageEditPanel(state, state.selected!!); return }
    val missing = remember { ImageEngines().missing() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(enabled = !state.busy, onClick = { state.add(pickImages()) }) { Text("添加图片") }
            ChoiceMenu("模式", state.mode, ImageMode.entries.toList(), { it.title }, !state.busy) { state.mode = it }
            if (state.mode == ImageMode.SMART || state.hasEdits) ChoiceMenu("质量", state.quality, ImageQuality.entries.toList(), { it.title }, !state.busy) { state.quality = it }
            ToolOutlinedButton(enabled = !state.busy && state.selected != null, onClick = { state.showEditor = true }) { Text("转换 / 尺寸 / 裁剪") }
            ToolButton(enabled = !state.busy && state.rows.isNotEmpty() && missing.isEmpty(), onClick = state::compress) { Text(if (state.hasEdits) "开始处理" else "开始压缩") }
            if (state.busy) ToolOutlinedButton(enabled = !state.cancelling, onClick = state::cancel) { Text("取消") }
        }
        val editsJpeg = state.hasEdits && (state.settings.output == ImageOutput.JPEG || state.settings.output == ImageOutput.KEEP && state.rows.any { it.input.info.kind == ImageKind.JPEG })
        Text(if (state.hasEdits) "${state.settings.output.title} · ${state.settings.resize.title} · ${state.rows.count { it.crop != null }} 张设有裁剪 · " +
            if (editsJpeg) "JPEG 编辑输出为有损" else "原图不覆盖"
            else if (state.mode == ImageMode.SMART) "智能有损压缩 · 保留尺寸、格式与透明度 · 原图不覆盖" else "无损优化编码 · 保留像素、尺寸及元数据 · 原图不覆盖",
            style = MaterialTheme.typography.caption, color = sem.subtle)
        if (missing.isNotEmpty()) Text("本机缺少图片引擎：${missing.joinToString()}。请安装完整应用，或按图片工具文档配置引擎目录。", color = MaterialTheme.colors.error)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.status, style = MaterialTheme.typography.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        state.error?.let { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption, maxLines = 4) }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(Modifier.width(270.dp).fillMaxHeight(), shape = RoundedCornerShape(24.dp), color = sem.formBg) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("处理队列 · ${state.rows.size}", Modifier.weight(1f), style = MaterialTheme.typography.subtitle1)
                        TextButton(enabled = !state.busy && state.rows.isNotEmpty(), onClick = state::clear) { Text("清空") }
                    }
                    if (state.rows.isEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        Text("批量选择 PNG / JPEG / WebP\n\n单张 ≤ 32 MB / 2400 万像素\n每批 ≤ 100 张 / 256 MB\n\n不联网，不覆盖原图。", color = sem.subtle, style = MaterialTheme.typography.body2)
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.rows, key = { it.input.snapshot.path }) { row ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (state.selected == row) sem.selectionBg else sem.cardBg)
                                .clickable { state.selected = row }.padding(12.dp)) {
                                Text(row.input.original.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.subtitle2)
                                Text("${row.input.info.displayWidth} × ${row.input.info.displayHeight} · ${imageBytes(row.input.size)}", style = MaterialTheme.typography.caption, color = sem.subtle)
                                when {
                                    row.processing -> Text("处理中…", color = MaterialTheme.colors.primary)
                                    row.error != null -> Text(row.error!!, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption, maxLines = 2)
                                    row.result != null -> {
                                        val result = row.result!!
                                        Text("${imageBytes(result.size)} · ${imageChange(result.saved, result.percent)}", color = sem.advice, style = MaterialTheme.typography.caption)
                                        Text("${result.info.displayWidth} × ${result.info.displayHeight} · ${result.info.kind.name}", style = MaterialTheme.typography.caption)
                                        if (!state.isCurrent(row)) Text("设置已变更，请重新处理", color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                                        val encoding = if (result.transformed && result.info.kind == ImageKind.JPEG) "JPEG 有损重编码 · ${result.quality.title}"
                                            else "${result.mode.title}${if (result.mode == ImageMode.SMART) " · ${result.quality.title}" else ""}"
                                        Text("$encoding${if (row.exported != null) " · 已另存" else ""}", style = MaterialTheme.typography.caption)
                                    }
                                    else -> Text("待处理", style = MaterialTheme.typography.caption, color = sem.subtle)
                                }
                            }
                        }
                    }
                    TextButton(enabled = !state.busy && state.selected != null, onClick = { state.selected?.let(state::remove) }) { Text("移除选中") }
                }
            }
            val selected = state.selected
            if (selected == null) ToolPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("本地图片处理，看得见", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(18.dp))
                Text("左侧保留原图，右侧预览结果。支持同步放大检查文字、渐变和透明边缘。", color = sem.subtle)
                Spacer(Modifier.height(18.dp))
                Text("支持格式转换、尺寸调整与裁剪。纯压缩时较大结果回退原图；编辑结果按所选格式另存。", color = sem.subtle)
            } else ImageComparison(selected, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val results = state.rows.filter(state::isCurrent).mapNotNull { it.result }
            Text("${results.size} 张结果可另存 · ${if (results.sumOf { it.saved } >= 0) "共减少" else "共增加"} ${imageBytes(kotlin.math.abs(results.sumOf { it.saved }))}", Modifier.weight(1f), style = MaterialTheme.typography.body2)
            ToolButton(enabled = !state.busy && results.isNotEmpty(), onClick = { pickDirectory()?.let { state.export(File(it)) } }) { Text("批量另存到文件夹") }
        }
    }
}

@Composable
private fun ImageComparison(row: ImageRow, modifier: Modifier) {
    val sem = LocalSemantic.current
    var zoom by remember { mutableStateOf(1f) }
    var darkBackground by remember { mutableStateOf(false) }
    val horizontal = rememberScrollState(); val vertical = rememberScrollState()
    var original by remember(row.input.snapshot.path) { mutableStateOf<ImageBitmap?>(null) }
    var compressed by remember(row.result?.file?.path) { mutableStateOf<ImageBitmap?>(null) }
    var previewError by remember(row.input.snapshot.path, row.result?.file?.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(row.input.snapshot.path, row.result?.file?.path) {
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                imagePreview(row.input.snapshot, row.input.info.orientation, cancelled = { !context.isActive }).toComposeImageBitmap() to
                    row.result?.let { imagePreview(it.file, it.info.orientation, cancelled = { !context.isActive }).toComposeImageBitmap() }
            }.let { (a, b) -> original = a; compressed = b }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { previewError = "此图片无法预览，请检查文件是否损坏" }
    }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = sem.cardBg) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("前后对比", Modifier.weight(1f), style = MaterialTheme.typography.subtitle1)
                TextButton(onClick = { zoom = if (zoom >= 4f) 1f else zoom * 2 }) { Text("放大 ${zoom.toInt()}×") }
                TextButton(onClick = { darkBackground = !darkBackground }) { Text(if (darkBackground) "浅底" else "深底") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("原图 · ${imageBytes(row.input.size)}", Modifier.weight(1f), style = MaterialTheme.typography.caption)
                Text(row.result?.let { "${it.info.displayWidth} × ${it.info.displayHeight} · ${imageBytes(it.size)}" } ?: "等待压缩", Modifier.weight(1f), style = MaterialTheme.typography.caption)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(original, compressed).forEach { bitmap ->
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))) {
                        val paneWidth = maxWidth; val paneHeight = maxHeight
                        Canvas(Modifier.fillMaxSize()) {
                            val step = 12.dp.toPx()
                            val base = if (darkBackground) Color(0xff303438) else Color(0xffeeeeee)
                            val tile = if (darkBackground) Color(0xff45494d) else Color.White
                            drawRect(base)
                            for (y in 0..(size.height / step).toInt()) for (x in 0..(size.width / step).toInt()) {
                                if ((x + y) % 2 == 0) drawRect(tile, Offset(x * step, y * step), Size(step, step))
                            }
                        }
                        if (bitmap != null) {
                            // Both panes share scrolling and zoom for inspecting the same region.
                            Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical)) {
                                Image(bitmap, "${if (bitmap === original) "原图" else "压缩结果"}预览", Modifier.size(paneWidth * zoom, paneHeight * zoom), contentScale = ContentScale.Fit)
                            }
                        } else Text(if (previewError != null) "预览不可用" else "等待图片", Modifier.align(Alignment.Center), color = Color.Gray)
                    }
                }
            }
            Text(previewError ?: row.result?.note ?: "选择模式后点击开始压缩", style = MaterialTheme.typography.caption, color = sem.subtle, maxLines = 2)
            Text("预览最长边 1800 像素；可同步放大。编辑后的尺寸与格式以结果为准。", style = MaterialTheme.typography.caption, color = sem.subtle)
        }
    }
}

private fun pickImages(): List<File> {
    var files = emptyList<File>()
    val show = Runnable {
        if (System.getProperty("os.name").startsWith("Mac")) {
            val dialog = FileDialog(null as Frame?, "选择 PNG / JPEG / WebP 图片（可多选）", FileDialog.LOAD)
            try {
                dialog.isMultipleMode = true
                dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in listOf("png", "jpg", "jpeg", "webp") }
                dialog.isVisible = true; files = dialog.files.toList()
            } finally { dialog.dispose() }
        } else {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true
                fileFilter = FileNameExtensionFilter("PNG / JPEG / WebP", "png", "jpg", "jpeg", "webp")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) files = chooser.selectedFiles.toList()
        }
    }
    if (EventQueue.isDispatchThread()) show.run() else EventQueue.invokeAndWait(show)
    return files
}
