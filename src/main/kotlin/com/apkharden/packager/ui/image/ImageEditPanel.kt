package com.apkharden.packager.ui.image

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.image.*
import com.apkharden.packager.ui.common.*
import com.apkharden.packager.ui.developer.ChoiceMenu
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.*

internal class ImageEditDraft(settings: ImageEditSettings, val row: ImageRow) {
    var output by mutableStateOf(settings.output)
    var resize by mutableStateOf(settings.resize)
    var width by mutableStateOf(settings.width?.toString().orEmpty())
    var height by mutableStateOf(settings.height?.toString().orEmpty())
    var percent by mutableStateOf(settings.percent.toString())
    var enlarge by mutableStateOf(settings.enlarge)
    var background by mutableStateOf("#%06X".format(settings.background))
    var cropped by mutableStateOf(row.crop != null)
    var x by mutableStateOf((row.crop?.x ?: 0).toString())
    var y by mutableStateOf((row.crop?.y ?: 0).toString())
    var cropWidth by mutableStateOf((row.crop?.width ?: row.input.info.displayWidth).toString())
    var cropHeight by mutableStateOf((row.crop?.height ?: row.input.info.displayHeight).toString())
    fun region(): CropRegion? = if (!cropped) null else CropRegion(number(x, "裁剪 X"), number(y, "裁剪 Y"), number(cropWidth, "裁剪宽度"), number(cropHeight, "裁剪高度"))
    fun settings(): ImageEditSettings {
        val dimensions = resize in listOf(ResizeMode.FIT, ResizeMode.EXACT)
        require(background.matches(Regex("#?[0-9a-fA-F]{6}"))) { "背景色须为六位十六进制颜色，例如 #FFFFFF" }
        return ImageEditSettings(output, resize,
            if (dimensions && width.isNotBlank()) number(width, "目标宽度") else null,
            if (dimensions && height.isNotBlank()) number(height, "目标高度") else null,
            if (resize == ResizeMode.PERCENT) number(percent, "缩放比例") else 100, enlarge, background.removePrefix("#").toInt(16))
    }
    fun setCrop(region: CropRegion) { cropped = true; x = region.x.toString(); y = region.y.toString(); cropWidth = region.width.toString(); cropHeight = region.height.toString() }
    private fun number(value: String, label: String): Int = value.trim().toIntOrNull() ?: error("$label 请输入整数")
}

@Composable
internal fun ImageEditPanel(state: ImageWorkspaceState, row: ImageRow) {
    val sem = LocalSemantic.current
    val draft = remember(row) {
        state.editDraft?.takeIf { it.row === row } ?: ImageEditDraft(state.settings, row).also { state.editDraft = it }
    }
    val info = row.input.info
    var bitmap by remember(row) { mutableStateOf<ImageBitmap?>(null) }
    var previewError by remember(row) { mutableStateOf<String?>(null) }
    LaunchedEffect(row) {
        try { bitmap = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            imagePreview(row.input.snapshot, info.orientation, cancelled = { !context.isActive }).toComposeImageBitmap()
        } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { previewError = e.message ?: "无法预览图片" }
    }
    val validation = runCatching { planImageEdit(info, draft.settings(), draft.region()) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("格式、尺寸与裁剪", Modifier.weight(1f), style = MaterialTheme.typography.h6)
            ToolOutlinedButton(onClick = { state.editDraft = null; state.showEditor = false }) { Text("取消编辑") }
            ToolButton(enabled = validation.isSuccess, onClick = {
                state.settings = draft.settings(); row.crop = draft.region(); state.editDraft = null; state.showEditor = false
                state.status = "设置已应用；点击开始处理生成新结果"
            }) { Text("应用设置") }
        }
        Text("格式与尺寸应用于整批；裁剪只作用于当前图片。每次从原图处理，不累积损失。", style = MaterialTheme.typography.caption, color = sem.subtle)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(Modifier.width(270.dp).fillMaxHeight(), shape = RoundedCornerShape(24.dp), color = sem.formBg) {
                val scroll = rememberScrollState()
                Box {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChoiceMenu("输出格式", draft.output, ImageOutput.entries.toList(), { it.title }) { draft.output = it }
                        ChoiceMenu("尺寸方式", draft.resize, ResizeMode.entries.toList(), { it.title }) { draft.resize = it }
                        if (draft.resize in listOf(ResizeMode.FIT, ResizeMode.EXACT)) {
                            EditField("目标宽度", draft.width, { draft.width = it }, Modifier.fillMaxWidth())
                            EditField("目标高度", draft.height, { draft.height = it }, Modifier.fillMaxWidth())
                            if (draft.resize == ResizeMode.FIT) {
                                Text("至少填写一个边长；两者都填时保持比例缩放到范围内。", style = MaterialTheme.typography.caption, color = sem.subtle)
                                LabeledCheck("允许放大", draft.enlarge) { draft.enlarge = it }
                            } else Text("按指定宽高拉伸，可能改变图片比例。", style = MaterialTheme.typography.caption, color = sem.subtle)
                        }
                        if (draft.resize == ResizeMode.PERCENT) EditField("缩放百分比", draft.percent, { draft.percent = it }, Modifier.fillMaxWidth())
                        if (draft.output == ImageOutput.JPEG || draft.output == ImageOutput.KEEP && state.rows.any { it.input.info.kind == ImageKind.JPEG }) {
                            Text("JPEG 透明区域背景", style = MaterialTheme.typography.subtitle2)
                            Row { TextButton(onClick = { draft.background = "#FFFFFF" }) { Text("白底") }; TextButton(onClick = { draft.background = "#000000" }) { Text("黑底") } }
                            EditField("背景色 #RRGGBB", draft.background, { draft.background = it }, Modifier.fillMaxWidth())
                            Text("JPEG 不支持透明度，编辑后按所选质量重新编码。", style = MaterialTheme.typography.caption, color = sem.subtle)
                        }
                        TextButton(onClick = { draft.output = ImageOutput.KEEP; draft.resize = ResizeMode.ORIGINAL; draft.cropped = false }) { Text("恢复原格式和尺寸，取消裁剪") }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(24.dp), color = sem.cardBg) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.input.original.name, style = MaterialTheme.typography.subtitle2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabeledCheck("启用当前图片裁剪", draft.cropped) { draft.cropped = it }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { draft.setCrop(CropRegion(0, 0, info.displayWidth, info.displayHeight)) }) { Text("全选") }
                    }
                    val region = runCatching { draft.region() }.getOrNull()
                    CropCanvas(bitmap, info.displayWidth, info.displayHeight, region, draft.cropped,
                        Modifier.weight(1f).fillMaxWidth(), draft::setCrop)
                    if (draft.cropped) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EditField("裁剪 X", draft.x, { draft.x = it }, Modifier.weight(1f))
                            EditField("裁剪 Y", draft.y, { draft.y = it }, Modifier.weight(1f))
                            EditField("裁剪宽度", draft.cropWidth, { draft.cropWidth = it }, Modifier.weight(1f))
                            EditField("裁剪高度", draft.cropHeight, { draft.cropHeight = it }, Modifier.weight(1f))
                        }
                    }
                    Text(previewError ?: "拖动框选；框内移动，角点调节。原图：${info.displayWidth} × ${info.displayHeight}", style = MaterialTheme.typography.caption, color = sem.subtle, maxLines = 2)
                }
            }
        }
        val plan = validation.getOrNull()
        Text(if (plan != null) "当前图片输出：${plan.width} × ${plan.height} · ${plan.kind.name}。" +
                if (plan.changed) "编辑将转为 8 位 sRGB，并移除旧 EXIF。" else "无实际变更，将按原压缩流程处理。"
            else validation.exceptionOrNull()?.message ?: "请检查设置", color = if (plan != null) sem.subtle else MaterialTheme.colors.error,
            style = MaterialTheme.typography.caption, maxLines = 2)
    }
}

@Composable
private fun EditField(label: String, value: String, change: (String) -> Unit, modifier: Modifier) =
    ToolTextField(value, change, modifier.testTag(label), label = { Text(label) }, singleLine = true)

@Composable
private fun LabeledCheck(text: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.clickable { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = change)
        Text(text, style = MaterialTheme.typography.body2)
    }
}

/** Map normalized drag endpoints to an integer rectangle, allowing every drag direction. */
internal fun cropFromDrag(a: Offset, b: Offset, width: Int, height: Int): CropRegion? {
    val left = min(a.x, b.x).roundToInt().coerceIn(0, width)
    val top = min(a.y, b.y).roundToInt().coerceIn(0, height)
    val right = max(a.x, b.x).roundToInt().coerceIn(0, width)
    val bottom = max(a.y, b.y).roundToInt().coerceIn(0, height)
    return if (right > left && bottom > top) CropRegion(left, top, right - left, bottom - top) else null
}

@Composable
private fun CropCanvas(bitmap: ImageBitmap?, width: Int, height: Int, region: CropRegion?, enabled: Boolean,
    modifier: Modifier, onRegion: (CropRegion) -> Unit) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val change by rememberUpdatedState(onRegion)
    val currentRegion by rememberUpdatedState(region)
    val accent = MaterialTheme.colors.primary
    val scale = min(canvas.width.toFloat() / width, canvas.height.toFloat() / height)
    val left = (canvas.width - width * scale) / 2
    val top = (canvas.height - height * scale) / 2
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xffdddddd)).testTag("crop-canvas")
        .semantics { contentDescription = if (bitmap == null) "正在加载裁剪图片" else "裁剪图片已加载" }
        .onSizeChanged { canvas = it }
        .pointerInput(bitmap, enabled, width, height, canvas) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val p = down.position
                if (!enabled || bitmap == null || scale <= 0 || p.x !in left..(left + width * scale) || p.y !in top..(top + height * scale)) return@awaitEachGesture
                // Hit-test the initial press, before gesture slop can carry the pointer away from a corner.
                var anchor = Offset((p.x - left) / scale, (p.y - top) / scale)
                var move: CropRegion? = null
                val selected = currentRegion?.takeIf { it.x >= 0 && it.y >= 0 && it.width > 0 && it.height > 0 &&
                    it.x.toLong() + it.width <= width && it.y.toLong() + it.height <= height }
                if (selected != null) {
                    val corners = listOf(Offset(selected.x.toFloat(), selected.y.toFloat()), Offset((selected.x + selected.width).toFloat(), selected.y.toFloat()),
                        Offset(selected.x.toFloat(), (selected.y + selected.height).toFloat()), Offset((selected.x + selected.width).toFloat(), (selected.y + selected.height).toFloat()))
                    val near = corners.indexOfFirst { (it - anchor).getDistance() * scale <= 12.dp.toPx() }
                    if (near >= 0) anchor = corners[3 - near]
                    else if (selected != CropRegion(0, 0, width, height) && anchor.x in selected.x.toFloat()..(selected.x + selected.width).toFloat() &&
                        anchor.y in selected.y.toFloat()..(selected.y + selected.height).toFloat()) move = selected
                }
                drag(down.id) { event ->
                    val point = Offset((event.position.x - left) / scale, (event.position.y - top) / scale)
                    event.consume()
                    val origin = move
                    if (origin != null) change(origin.copy(
                        x = (origin.x + point.x - anchor.x).roundToInt().coerceIn(0, width - origin.width),
                        y = (origin.y + point.y - anchor.y).roundToInt().coerceIn(0, height - origin.height)))
                    else cropFromDrag(anchor, point, width, height)?.let(change)
                }
            }
        }) {
        val step = 12.dp.toPx()
        for (y in 0..(size.height / step).toInt()) for (x in 0..(size.width / step).toInt()) {
            if ((x + y) % 2 == 0) drawRect(Color.White, Offset(x * step, y * step), Size(step, step))
        }
        bitmap?.let { drawImage(it, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))) }
        if (enabled && region != null && region.x >= 0 && region.y >= 0 && region.width > 0 && region.height > 0 &&
            region.x.toLong() + region.width <= width && region.y.toLong() + region.height <= height) {
            val x = left + region.x * scale; val y = top + region.y * scale
            val w = region.width * scale; val h = region.height * scale
            val mask = Color.Black.copy(alpha = .45f)
            drawRect(mask, Offset.Zero, Size(size.width, y))
            drawRect(mask, Offset(0f, y + h), Size(size.width, (size.height - y - h).coerceAtLeast(0f)))
            drawRect(mask, Offset(0f, y), Size(x, h))
            drawRect(mask, Offset(x + w, y), Size((size.width - x - w).coerceAtLeast(0f), h))
            drawRect(accent, Offset(x, y), Size(w, h), style = Stroke(2.dp.toPx()))
            listOf(Offset(x, y), Offset(x + w, y), Offset(x, y + h), Offset(x + w, y + h)).forEach { drawCircle(accent, 4.dp.toPx(), it) }
        }
    }
}
