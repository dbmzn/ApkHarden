package com.apkharden.packager.ui.adb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.apkharden.packager.device.AdbDeviceService
import com.apkharden.packager.device.AndroidDevice
import com.apkharden.packager.device.IntentLaunchRequest
import com.apkharden.packager.ui.inspect.PageTitle
import com.apkharden.packager.ui.inspect.ValueCard
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import javax.imageio.ImageIO
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class AdbTab(val label: String) {
    CAPTURE("截图与录屏"), LOG("日志过滤"), PERFORMANCE("性能快照"), INTENT("Intent / Deep Link"),
    DIAGNOSTICS("崩溃与 ANR"), APP("应用操作"), PROCESS("进程与页面栈")
}

internal val DEFAULT_ADB_TAB = AdbTab.CAPTURE

@Composable
fun AdbToolboxScreen() {
    var devices by remember { mutableStateOf<List<AndroidDevice>>(emptyList()) }
    var serial by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(DEFAULT_ADB_TAB) }
    var packageName by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf("android.permission.CAMERA") }
    var intentAction by remember { mutableStateOf("android.intent.action.VIEW") }
    var intentUri by remember { mutableStateOf("") }
    var intentComponent by remember { mutableStateOf("") }
    var intentCategories by remember { mutableStateOf("android.intent.category.BROWSABLE") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var screenshotPreview by remember { mutableStateOf<ByteArray?>(null) }
    var previewStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    fun refresh() {
        refreshing = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { AdbDeviceService.listDevices() } }
                .onSuccess { found -> devices = found; if (found.none { it.serial == serial }) serial = found.firstOrNull { it.state == "device" }?.serial.orEmpty() }
                .onFailure { error = friendly(it) }
            refreshing = false
        }
    }
    fun runAction(message: String, action: (AndroidDevice) -> String) {
        val device = devices.firstOrNull { it.serial == serial } ?: return
        running = true; error = null; output = message
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action(device) } }
                .onSuccess { output = it }.onFailure { error = friendly(it) }
            running = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除应用数据？") },
            text = { Text("将删除 $packageName 在设备上的账号、数据库、缓存和全部应用数据，此操作不可恢复。") },
            confirmButton = { Button(colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error), onClick = {
                confirmClear = false; runAction("正在清除应用数据…") { AdbDeviceService.clearData(it, packageName.trim()) }
            }) { Text("确认清除") } },
            dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    screenshotPreview?.let { bytes ->
        ScreenshotPreviewDialog(
            bytes = bytes,
            status = previewStatus,
            onDismiss = { screenshotPreview = null; previewStatus = null },
            onEdit = { previewStatus = null },
            onCopy = { renderedBytes ->
                runCatching { copyImageToClipboard(renderedBytes) }
                    .onSuccess {
                        output = "图片已复制到剪贴板"
                        screenshotPreview = null
                        previewStatus = null
                    }
                    .onFailure { previewStatus = "复制失败：${friendly(it)}" }
            },
            onSave = { renderedBytes ->
                runCatching {
                    val file = captureFile("screenshot", "png")
                    file.writeBytes(renderedBytes)
                    file
                }.onSuccess {
                    output = "截图已保存：${it.absolutePath}"
                    screenshotPreview = null
                    previewStatus = null
                }
                    .onFailure { previewStatus = "保存失败：${friendly(it)}" }
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageTitle("ADB 工具箱", "设备调试、性能诊断、Intent 验证与故障采集")
            Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = !refreshing && !running, onClick = { refresh() }) { Text(if (refreshing) "刷新中…" else "刷新设备") }
        }
        if (devices.isEmpty()) {
            ValueCard { Text(if (refreshing) "正在发现设备…" else "未发现在线设备", color = sem.subtle) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { device -> DeviceChoice(device, device.serial == serial, Modifier.weight(1f)) { if (!running) serial = device.serial } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdbTab.entries.forEach { item ->
                OutlinedButton(onClick = { tab = item; output = ""; error = null },
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (tab == item) MaterialTheme.colors.primary.copy(alpha = .14f) else MaterialTheme.colors.surface)) {
                    Text(item.label)
                }
            }
        }
        when (tab) {
            AdbTab.LOG -> ValueCard {
                CommonPackageField(packageName) { packageName = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(filter, { filter = it }, label = { Text("关键字过滤（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Button(enabled = !running && serial.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    runAction("正在读取日志…") { AdbDeviceService.logcat(it, packageName.trim(), filter.trim()) }
                }) { Text("读取最近 800 行日志") }
            }
            AdbTab.CAPTURE -> ValueCard {
                Text("截图保存为 PNG；录屏默认 15 秒并保存为 MP4。", color = sem.subtle, style = MaterialTheme.typography.body2)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !running && serial.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                        val device = devices.firstOrNull { it.serial == serial } ?: return@Button
                        running = true; error = null; output = "正在截取设备屏幕…"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { AdbDeviceService.captureScreenshot(device) } }
                                .onSuccess { bytes ->
                                    screenshotPreview = bytes
                                    previewStatus = null
                                    output = "截图完成，正在预览"
                                }.onFailure { error = friendly(it) }
                            running = false
                        }
                    }) { Text("设备截图") }
                    Button(enabled = !running && serial.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                        val file = captureFile("screenrecord", "mp4")
                        runAction("正在录屏 15 秒，请勿断开设备…") { device ->
                            val mode = AdbDeviceService.recordScreen(device, file, 15)
                            val modeLabel = when (mode) {
                                com.apkharden.packager.device.ScreenRecordingMode.DEVICE -> "设备原生模式"
                                com.apkharden.packager.device.ScreenRecordingMode.SCRCPY -> "高帧率兼容模式（最高 30 FPS）"
                                com.apkharden.packager.device.ScreenRecordingMode.SCREENSHOT_COMPATIBILITY ->
                                    "低帧率保底模式（4 FPS）"
                            }
                            "录屏已保存（$modeLabel）：${file.absolutePath}"
                        }
                    }) { Text("录屏 15 秒") }
                }
            }
            AdbTab.PERFORMANCE -> ValueCard {
                Text("一次采集目标应用的 CPU、内存和界面渲染数据，适合卡顿、内存异常和发版前快速留档。",
                    color = sem.subtle, style = MaterialTheme.typography.body2)
                Spacer(Modifier.height(10.dp))
                CommonPackageField(packageName) { packageName = it }
                Spacer(Modifier.height(10.dp))
                Button(enabled = !running && serial.isNotBlank() && packageName.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    runAction("正在采集性能快照…") { AdbDeviceService.performanceSnapshot(it, packageName.trim()) }
                }) { Text("采集性能快照") }
            }
            AdbTab.INTENT -> ValueCard {
                Text("通过 am start -W 调试 Activity、Scheme、Universal Link / App Link，并返回启动状态和耗时。",
                    color = sem.subtle, style = MaterialTheme.typography.body2)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(intentAction, { intentAction = it }, label = { Text("Intent Action") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(intentUri, { intentUri = it }, label = { Text("URI / Deep Link（可选）") },
                    placeholder = { Text("myapp://page/detail?id=1") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                CommonPackageField(packageName) { packageName = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(intentComponent, { intentComponent = it }, label = { Text("组件（可选，例如 com.demo/.MainActivity）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(intentCategories, { intentCategories = it }, label = { Text("Category（多个用逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Button(enabled = !running && serial.isNotBlank() && intentAction.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    val request = IntentLaunchRequest(
                        action = intentAction,
                        dataUri = intentUri,
                        packageName = packageName,
                        component = intentComponent,
                        categories = intentCategories.split(',').map(String::trim).filter(String::isNotBlank),
                    )
                    runAction("正在发送 Intent…") { AdbDeviceService.launchIntent(it, request) }
                }) { Text("发送并等待启动结果") }
            }
            AdbTab.DIAGNOSTICS -> ValueCard {
                Text("生成可直接发给研发的 ZIP：设备信息、包信息、进程、页面栈、CPU、内存、最近日志、Crash buffer、last ANR 和 DropBox 记录。",
                    color = sem.subtle, style = MaterialTheme.typography.body2)
                Spacer(Modifier.height(10.dp))
                CommonPackageField(packageName) { packageName = it }
                Spacer(Modifier.height(10.dp))
                Button(enabled = !running && serial.isNotBlank() && packageName.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    val file = captureFile("diagnostics", "zip")
                    runAction("正在采集崩溃与 ANR 诊断信息…") { device ->
                        AdbDeviceService.collectDiagnostics(device, packageName.trim(), file)
                        "采集包已保存：${file.absolutePath}"
                    }
                }) { Text("生成崩溃与 ANR 采集包") }
            }
            AdbTab.APP -> ValueCard {
                CommonPackageField(packageName) { packageName = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(permission, { permission = it }, label = { Text("Android 权限") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !running && serial.isNotBlank() && packageName.isNotBlank() && permission.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                        runAction("正在授予权限…") { AdbDeviceService.grantPermission(it, packageName.trim(), permission.trim()) }
                    }) { Text("授予权限") }
                    OutlinedButton(enabled = !running && serial.isNotBlank() && packageName.isNotBlank(), modifier = Modifier.weight(1f), onClick = { confirmClear = true }) {
                        Text("清除应用数据", color = MaterialTheme.colors.error)
                    }
                }
            }
            AdbTab.PROCESS -> ValueCard {
                CommonPackageField(packageName) { packageName = it }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !running && serial.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                        runAction("正在读取进程…") { AdbDeviceService.processes(it, packageName.trim()) }
                    }) { Text("查看进程") }
                    Button(enabled = !running && serial.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                        runAction("正在读取页面栈…") { AdbDeviceService.activityStack(it, packageName.trim()) }
                    }) { Text("查看页面栈") }
                }
            }
        }
        error?.let { Text("操作失败：$it", color = MaterialTheme.colors.error) }
        if (output.isNotBlank()) {
            ValueCard {
                Text("输出", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(8.dp))
                Text(output, style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace), color = sem.subtle)
            }
        }
    }
}

@Composable
private fun DeviceChoice(device: AndroidDevice, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sem = LocalSemantic.current
    Row(modifier.clip(RoundedCornerShape(11.dp)).background(if (selected) MaterialTheme.colors.primary.copy(alpha = .12f) else sem.cardBg)
        .border(1.dp, if (selected) MaterialTheme.colors.primary else sem.cardBorder, RoundedCornerShape(11.dp))
        .clickable(onClick = onClick).padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(device.model, style = MaterialTheme.typography.subtitle2); Text("API ${device.api} · ${device.serial}", style = MaterialTheme.typography.caption, color = sem.subtle) }
        Text(if (device.state == "device") "在线" else device.state, color = if (device.state == "device") sem.advice else MaterialTheme.colors.error,
            style = MaterialTheme.typography.caption)
    }
}

@Composable
private fun CommonPackageField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text("应用包名（可选操作除外）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

private fun friendly(error: Throwable): String = generateSequence(error) { it.cause }
    .mapNotNull { it.message?.takeIf(String::isNotBlank) }.firstOrNull() ?: error.javaClass.simpleName

private val CAPTURE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

internal fun captureFile(kind: String, extension: String, now: LocalDateTime = LocalDateTime.now()): File {
    val downloads = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
    return File(downloads, "ApkHarden-$kind-${now.format(CAPTURE_TIME)}.$extension")
}

@Composable
private fun ScreenshotPreviewDialog(
    bytes: ByteArray,
    status: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopy: (ByteArray) -> Unit,
    onSave: (ByteArray) -> Unit,
) {
    val sem = LocalSemantic.current
    val bitmap = remember(bytes) { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }
    var tool by remember(bytes) { mutableStateOf(AnnotationTool.ARROW) }
    var annotations by remember(bytes) { mutableStateOf(emptyList<ScreenshotAnnotation>()) }
    var draft by remember(bytes) { mutableStateOf<ScreenshotAnnotation?>(null) }

    fun updateAnnotations(updated: List<ScreenshotAnnotation>) {
        annotations = updated
        onEdit()
    }

    fun renderedBytes(): ByteArray = renderAnnotatedScreenshot(bytes, annotations)

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "截图预览",
        state = rememberDialogState(width = 1100.dp, height = 800.dp),
        resizable = true,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colors.surface,
            elevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("截图预览", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.weight(1f))
                    Text("${bitmap.width} × ${bitmap.height}", style = MaterialTheme.typography.caption, color = sem.subtle)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnnotationTool.entries.forEach { item ->
                        OutlinedButton(
                            onClick = { tool = item },
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = if (tool == item) MaterialTheme.colors.primary.copy(alpha = .14f)
                                else MaterialTheme.colors.surface,
                            ),
                        ) { Text(item.label) }
                    }
                    Text("在图片上拖动绘制", style = MaterialTheme.typography.caption, color = sem.subtle)
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val imageAspectRatio = bitmap.width.toFloat() / bitmap.height
                    val availableAspectRatio = maxWidth.value / maxHeight.value
                    val canvasModifier = if (imageAspectRatio >= availableAspectRatio) {
                        Modifier.width(maxWidth).height(maxWidth / imageAspectRatio)
                    } else {
                        Modifier.width(maxHeight * imageAspectRatio).height(maxHeight)
                    }
                    androidx.compose.foundation.Canvas(
                        canvasModifier.clip(RoundedCornerShape(12.dp)).pointerInput(bitmap.width, bitmap.height, tool) {
                            var start: AnnotationPoint? = null
                            detectDragGestures(
                                onDragStart = { offset ->
                                    start = imagePointAt(offset, size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                                    draft = start?.let { point -> tool.annotation(point, point) }
                                    if (draft != null) onEdit()
                                },
                                onDrag = { change, _ ->
                                    val first = start ?: return@detectDragGestures
                                    val current = imagePointAt(
                                        change.position,
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                        bitmap.width,
                                        bitmap.height,
                                        clampToImage = true,
                                    ) ?: return@detectDragGestures
                                    draft = tool.annotation(first, current)
                                },
                                onDragEnd = {
                                    draft?.takeIf { it.length() >= MIN_ANNOTATION_LENGTH }?.let {
                                        updateAnnotations(annotations + it)
                                    }
                                    draft = null
                                    start = null
                                },
                                onDragCancel = { draft = null; start = null },
                            )
                        },
                    ) {
                        val placement = imagePlacement(size.width, size.height, bitmap.width, bitmap.height)
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset(placement.left.roundToInt(), placement.top.roundToInt()),
                            dstSize = IntSize(placement.width.roundToInt(), placement.height.roundToInt()),
                        )
                        clipRect(
                            placement.left,
                            placement.top,
                            placement.left + placement.width,
                            placement.top + placement.height,
                        ) {
                            (annotations + listOfNotNull(draft)).forEach { annotation ->
                                drawAnnotation(annotation, placement, bitmap.width, bitmap.height)
                            }
                        }
                    }
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.caption,
                        color = if (it.contains("失败")) MaterialTheme.colors.error else sem.advice,
                        modifier = Modifier.padding(top = 9.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                    OutlinedButton(enabled = annotations.isNotEmpty(), onClick = {
                        updateAnnotations(annotations.dropLast(1))
                    }) { Text("撤销") }
                    OutlinedButton(enabled = annotations.isNotEmpty(), onClick = {
                        updateAnnotations(emptyList())
                    }) { Text("清空") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onSave(renderedBytes()) }) { Text("保存本地") }
                    Button(onClick = { onCopy(renderedBytes()) }) { Text("复制图片") }
                }
            }
        }
    }
}

private enum class AnnotationTool(val label: String) {
    ARROW("↗ 红色箭头"), RECTANGLE("□ 红色框");

    fun annotation(start: AnnotationPoint, end: AnnotationPoint): ScreenshotAnnotation = when (this) {
        ARROW -> ScreenshotAnnotation.Arrow(start, end)
        RECTANGLE -> ScreenshotAnnotation.Rectangle(start, end)
    }
}

internal data class AnnotationPoint(val x: Float, val y: Float)

internal sealed interface ScreenshotAnnotation {
    val start: AnnotationPoint
    val end: AnnotationPoint

    data class Arrow(
        override val start: AnnotationPoint,
        override val end: AnnotationPoint,
    ) : ScreenshotAnnotation

    data class Rectangle(
        override val start: AnnotationPoint,
        override val end: AnnotationPoint,
    ) : ScreenshotAnnotation
}

private data class ImagePlacement(val left: Float, val top: Float, val width: Float, val height: Float)

private const val MIN_ANNOTATION_LENGTH = 3f

private fun imagePlacement(canvasWidth: Float, canvasHeight: Float, imageWidth: Int, imageHeight: Int): ImagePlacement {
    val scale = min(canvasWidth / imageWidth, canvasHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return ImagePlacement((canvasWidth - width) / 2f, (canvasHeight - height) / 2f, width, height)
}

private fun imagePointAt(
    offset: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    clampToImage: Boolean = false,
): AnnotationPoint? {
    val placement = imagePlacement(canvasWidth, canvasHeight, imageWidth, imageHeight)
    val imageX = (offset.x - placement.left) * imageWidth / placement.width
    val imageY = (offset.y - placement.top) * imageHeight / placement.height
    if (!clampToImage && (imageX < 0f || imageY < 0f || imageX > imageWidth || imageY > imageHeight)) return null
    return AnnotationPoint(imageX.coerceIn(0f, imageWidth.toFloat()), imageY.coerceIn(0f, imageHeight.toFloat()))
}

private fun ScreenshotAnnotation.length(): Float = hypot(end.x - start.x, end.y - start.y)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotation(
    annotation: ScreenshotAnnotation,
    placement: ImagePlacement,
    imageWidth: Int,
    imageHeight: Int,
) {
    fun AnnotationPoint.onCanvas() = Offset(
        placement.left + x * placement.width / imageWidth,
        placement.top + y * placement.height / imageHeight,
    )

    val start = annotation.start.onCanvas()
    val end = annotation.end.onCanvas()
    val strokeWidth = annotationStrokeWidth(imageWidth, imageHeight) * placement.width / imageWidth
    when (annotation) {
        is ScreenshotAnnotation.Rectangle -> drawRect(
            color = Color.Red,
            topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
            size = Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
            style = Stroke(strokeWidth),
        )
        is ScreenshotAnnotation.Arrow -> {
            drawLine(Color.Red, start, end, strokeWidth, StrokeCap.Round)
            val angle = atan2(end.y - start.y, end.x - start.x)
            val headLength = arrowHeadLength(imageWidth, imageHeight) * placement.width / imageWidth
            val spread = Math.toRadians(28.0).toFloat()
            drawLine(
                Color.Red,
                end,
                Offset(end.x - headLength * cos(angle - spread), end.y - headLength * sin(angle - spread)),
                strokeWidth,
                StrokeCap.Round,
            )
            drawLine(
                Color.Red,
                end,
                Offset(end.x - headLength * cos(angle + spread), end.y - headLength * sin(angle + spread)),
                strokeWidth,
                StrokeCap.Round,
            )
        }
    }
}

internal fun renderAnnotatedScreenshot(bytes: ByteArray, annotations: List<ScreenshotAnnotation>): ByteArray {
    if (annotations.isEmpty()) return bytes
    val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("截图格式无效")
    val output = java.awt.image.BufferedImage(source.width, source.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(source, 0, 0, null)
        graphics.color = AwtColor.RED
        val strokeWidth = annotationStrokeWidth(source.width, source.height)
        graphics.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        annotations.forEach { annotation ->
            when (annotation) {
                is ScreenshotAnnotation.Rectangle -> graphics.draw(
                    Rectangle2D.Float(
                        minOf(annotation.start.x, annotation.end.x),
                        minOf(annotation.start.y, annotation.end.y),
                        kotlin.math.abs(annotation.end.x - annotation.start.x),
                        kotlin.math.abs(annotation.end.y - annotation.start.y),
                    ),
                )
                is ScreenshotAnnotation.Arrow -> {
                    graphics.draw(Line2D.Float(annotation.start.x, annotation.start.y, annotation.end.x, annotation.end.y))
                    val angle = atan2(annotation.end.y - annotation.start.y, annotation.end.x - annotation.start.x)
                    val headLength = arrowHeadLength(source.width, source.height)
                    val spread = Math.toRadians(28.0).toFloat()
                    graphics.draw(Line2D.Float(
                        annotation.end.x,
                        annotation.end.y,
                        annotation.end.x - headLength * cos(angle - spread),
                        annotation.end.y - headLength * sin(angle - spread),
                    ))
                    graphics.draw(Line2D.Float(
                        annotation.end.x,
                        annotation.end.y,
                        annotation.end.x - headLength * cos(angle + spread),
                        annotation.end.y - headLength * sin(angle + spread),
                    ))
                }
            }
        }
    } finally {
        graphics.dispose()
    }
    return ByteArrayOutputStream().use { stream ->
        check(ImageIO.write(output, "png", stream)) { "无法编码标注截图" }
        stream.toByteArray()
    }
}

private fun annotationStrokeWidth(imageWidth: Int, imageHeight: Int): Float =
    (min(imageWidth, imageHeight) * 0.005f).coerceIn(4f, 12f)

private fun arrowHeadLength(imageWidth: Int, imageHeight: Int): Float =
    (min(imageWidth, imageHeight) * 0.035f).coerceIn(18f, 56f)

private fun copyImageToClipboard(bytes: ByteArray) {
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("截图格式无效")
    val transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
}
