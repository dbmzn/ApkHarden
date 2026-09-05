package com.apkharden.packager.device

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import org.jcodec.api.awt.AWTSequenceEncoder
import kotlin.math.roundToInt

internal data class AndroidDevice(
    val serial: String,
    val state: String,
    val model: String,
    val api: String = "-",
    val abis: String = "-",
    val pageSize: String = "-",
)

internal data class DeviceLaunchResult(
    val device: AndroidDevice,
    val packageName: String,
    val pid: String,
    val resumed: Boolean,
    val crashLog: String,
) {
    val passed: Boolean get() = pid.isNotBlank() && resumed && crashLog.isBlank()
}

internal data class IntentLaunchRequest(
    val action: String = "android.intent.action.VIEW",
    val dataUri: String = "",
    val packageName: String = "",
    val component: String = "",
    val categories: List<String> = listOf("android.intent.category.BROWSABLE"),
)

internal enum class ScreenRecordingMode {
    DEVICE,
    SCRCPY,
    SCREENSHOT_COMPATIBILITY,
}

internal data class DeviceScreenSize(val width: Int, val height: Int)

internal data class MirrorWindowSize(val width: Int, val height: Int)

private const val COMPATIBILITY_RECORDING_FPS = 4
private const val COMPATIBILITY_RECORDING_MAX_EDGE = 1280

internal fun isScreenRecordUnavailable(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.mapNotNull(Throwable::message).any { message ->
        val normalized = message.lowercase()
        normalized.contains("screenrecord: inaccessible or not found") ||
            normalized.contains("screenrecord: not found") ||
            normalized.contains("screenrecord: no such file or directory") ||
            normalized.contains("encoder failed") || normalized.contains("unable to configure video codec")
    }

internal fun buildScrcpyRecordingArgs(serial: String, output: File, seconds: Int): List<String> {
    require(serial.isNotBlank()) { "设备序列号不能为空" }
    require(seconds in 1..180) { "录屏时长必须为 1～180 秒" }
    return listOf(
        "--serial", serial,
        "--record", output.absolutePath,
        "--time-limit", seconds.toString(),
        "--no-window",
        "--no-audio",
        "--no-control",
        "--no-clipboard-autosync",
        "--max-fps", "30",
        "--max-size", "1920",
    )
}

internal fun buildScrcpyMirrorArgs(
    serial: String,
    windowTitle: String,
    windowWidth: Int,
    windowHeight: Int,
    maxVideoSize: Int? = null,
): List<String> {
    require(serial.isNotBlank()) { "设备序列号不能为空" }
    require(windowTitle.isNotBlank()) { "镜像窗口标题不能为空" }
    require(windowWidth > 0 && windowHeight > 0) { "镜像窗口尺寸必须大于 0" }
    if (maxVideoSize != null) require(maxVideoSize > 0) { "镜像视频最大尺寸必须大于 0" }
    return buildList {
        addAll(
            listOf(
                "--serial", serial,
                "--window-title", windowTitle,
                "--window-width", windowWidth.toString(),
                "--window-height", windowHeight.toString(),
                "--shortcut-mod", "lctrl",
                "--no-audio",
                "--no-clipboard-autosync",
                "--max-fps", "30",
            ),
        )
        if (maxVideoSize != null) addAll(listOf("--max-size", maxVideoSize.toString()))
    }
}

internal fun parseDeviceScreenSize(output: String): DeviceScreenSize {
    val match = Regex("(?:Physical|Override) size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
        .findAll(output)
        .lastOrNull()
        ?: Regex("(\\d+)x(\\d+)").find(output)
        ?: throw IllegalStateException("无法读取设备屏幕尺寸：${output.trim()}")
    return DeviceScreenSize(
        width = match.groupValues[1].toInt(),
        height = match.groupValues[2].toInt(),
    ).also {
        require(it.width > 0 && it.height > 0) { "设备屏幕尺寸无效" }
    }
}

internal fun parseCurrentDisplaySize(output: String): DeviceScreenSize? {
    val displayZero = Regex(
        "Display:\\s*mDisplayId=0\\b[\\s\\S]*?mBounds=Rect\\(0,\\s*0\\s*-\\s*(\\d+),\\s*(\\d+)\\)",
        RegexOption.IGNORE_CASE,
    ).find(output)
    val currentRect = displayZero ?: Regex(
        "mCurrentDisplayRect=Rect\\(0,\\s*0\\s*-\\s*(\\d+),\\s*(\\d+)\\)",
        RegexOption.IGNORE_CASE,
    ).find(output)
    return currentRect?.let {
        DeviceScreenSize(
            width = it.groupValues[1].toInt(),
            height = it.groupValues[2].toInt(),
        )
    }?.takeIf { it.width > 0 && it.height > 0 }
}

internal fun fitMirrorWindow(
    device: DeviceScreenSize,
    maxWidth: Int,
    maxHeight: Int,
): MirrorWindowSize {
    require(maxWidth > 0 && maxHeight > 0) { "桌面可用尺寸必须大于 0" }
    val scale = minOf(
        1.0,
        maxWidth.toDouble() / device.width,
        maxHeight.toDouble() / device.height,
    )
    return MirrorWindowSize(
        width = (device.width * scale).roundToInt().coerceAtLeast(1),
        height = (device.height * scale).roundToInt().coerceAtLeast(1),
    )
}

internal fun normalizeRecordingFrame(
    source: BufferedImage,
    targetWidth: Int? = null,
    targetHeight: Int? = null,
): BufferedImage {
    require(source.width > 0 && source.height > 0) { "录屏帧尺寸无效" }
    val scale = minOf(1.0, COMPATIBILITY_RECORDING_MAX_EDGE.toDouble() / maxOf(source.width, source.height))
    val width = targetWidth ?: ((source.width * scale).toInt().coerceAtLeast(2) / 2 * 2)
    val height = targetHeight ?: ((source.height * scale).toInt().coerceAtLeast(2) / 2 * 2)
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "录屏输出尺寸必须为正偶数" }

    val output = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    val graphics = output.createGraphics()
    try {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val fitScale = minOf(width.toDouble() / source.width, height.toDouble() / source.height)
        val drawWidth = (source.width * fitScale).toInt().coerceAtLeast(1)
        val drawHeight = (source.height * fitScale).toInt().coerceAtLeast(1)
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        graphics.drawImage(source, left, top, drawWidth, drawHeight, null)
    } finally {
        graphics.dispose()
    }
    return output
}

internal fun encodeRecordingFrames(frames: Iterable<BufferedImage>, output: File, fps: Int) {
    require(fps > 0) { "录屏帧率必须大于 0" }
    output.parentFile?.mkdirs()
    val iterator = frames.iterator()
    require(iterator.hasNext()) { "没有可编码的录屏帧" }
    val encoder = AWTSequenceEncoder.createSequenceEncoder(output, fps)
    do {
        encoder.encodeImage(iterator.next())
    } while (iterator.hasNext())
    encoder.finish()
    require(output.isFile && output.length() > 0) { "兼容录屏文件生成失败" }
}

internal fun buildStartIntentArgs(request: IntentLaunchRequest): List<String> {
    require(request.action.isNotBlank()) { "Intent Action 不能为空" }
    val args = mutableListOf("shell", "am", "start", "-W", "-a", request.action.trim())
    request.dataUri.trim().takeIf(String::isNotBlank)?.let { args += listOf("-d", it) }
    request.categories.map(String::trim).filter(String::isNotBlank).distinct().forEach {
        args += listOf("-c", it)
    }
    request.packageName.trim().takeIf(String::isNotBlank)?.let { args += listOf("-p", it) }
    request.component.trim().takeIf(String::isNotBlank)?.let { args += listOf("-n", it) }
    return args
}

internal object AdbDeviceService {
    fun listDevices(): List<AndroidDevice> {
        val output = runAdb(listOf("devices", "-l"), 15)
        return output.lineSequence().drop(1).mapNotNull { line ->
            val value = line.trim()
            if (value.isEmpty()) return@mapNotNull null
            val parts = value.split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val serial = parts[0]
            val state = parts[1]
            val model = parts.firstOrNull { it.startsWith("model:") }?.substringAfter(':') ?: "未知设备"
            if (state != "device") AndroidDevice(serial, state, model) else AndroidDevice(
                serial = serial,
                state = state,
                model = model.replace('_', ' '),
                api = shell(serial, "getprop", "ro.build.version.sdk").trim().ifBlank { "-" },
                abis = shell(serial, "getprop", "ro.product.cpu.abilist").trim().ifBlank { "-" },
                pageSize = runCatching { shell(serial, "getconf", "PAGESIZE").trim() }
                    .getOrDefault("").ifBlank { "未知" },
            )
        }.toList()
    }

    fun verifyLaunch(
        apk: File,
        packageName: String,
        device: AndroidDevice,
        waitSeconds: Int = 15,
        skipInstall: Boolean = false,
        log: (String) -> Unit = {},
    ): DeviceLaunchResult {
        require(apk.isFile) { "APK 文件不存在" }
        require(packageName.isNotBlank()) { "包名不能为空" }
        require(device.state == "device") { "设备当前状态：${device.state}" }
        if (!skipInstall) {
            log("安装 APK 到 ${device.model}…")
            runAdb(listOf("-s", device.serial, "install", "--no-incremental", "-r", apk.absolutePath), 180)
        }
        log("清理日志并执行冷启动…")
        runCatching { runAdb(listOf("-s", device.serial, "logcat", "-c"), 15) }
        shell(device.serial, "am", "force-stop", packageName)
        shell(device.serial, "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
        log("等待应用稳定 ${waitSeconds} 秒…")
        Thread.sleep(waitSeconds * 1000L)
        val pid = runCatching { shell(device.serial, "pidof", packageName).trim() }.getOrDefault("")
        val activities = runCatching { shell(device.serial, "dumpsys", "activity", "activities") }.getOrDefault("")
        val resumed = Regex("(?m)(mResumedActivity|topResumedActivity|ResumedActivity).*${Regex.escape(packageName)}")
            .containsMatchIn(activities)
        val rawLog = runCatching {
            runAdb(listOf("-s", device.serial, "logcat", "-d", "-v", "threadtime",
                "AndroidRuntime:E", "ActivityManager:E", "libc:F", "DEBUG:F", "linker:E", "ApkHarden:E", "*:S"), 30)
        }.getOrDefault("")
        val crash = if (Regex("FATAL EXCEPTION|Fatal signal|UnsatisfiedLinkError|APH-E\\d{3}")
                .containsMatchIn(rawLog)) rawLog.trim() else ""
        log("检查进程、前台 Activity 和崩溃日志…")
        return DeviceLaunchResult(device, packageName, pid, resumed, crash)
    }

    fun logcat(device: AndroidDevice, packageName: String = "", filter: String = ""): String {
        val args = mutableListOf("-s", device.serial, "logcat", "-d", "-v", "threadtime", "-t", "800")
        if (packageName.isNotBlank()) {
            val pid = runCatching { shell(device.serial, "pidof", packageName).trim().substringBefore(' ') }.getOrDefault("")
            if (pid.isNotBlank()) args += listOf("--pid", pid)
        }
        val output = runAdb(args, 30)
        return if (filter.isBlank()) output else output.lineSequence()
            .filter { it.contains(filter, ignoreCase = true) }.joinToString("\n")
    }

    fun processes(device: AndroidDevice, filter: String = ""): String {
        val output = shell(device.serial, "ps", "-A")
        return if (filter.isBlank()) output else output.lineSequence()
            .filter { it.contains(filter, ignoreCase = true) || it.startsWith("USER") }.joinToString("\n")
    }

    fun activityStack(device: AndroidDevice, packageName: String = ""): String {
        val output = shell(device.serial, "dumpsys", "activity", "activities")
        return output.lineSequence().filter { line ->
            packageName.isBlank() || line.contains(packageName, ignoreCase = true) ||
                line.contains("ResumedActivity") || line.contains("Task{")
        }.take(600).joinToString("\n")
    }

    fun performanceSnapshot(device: AndroidDevice, packageName: String): String {
        require(packageName.isNotBlank()) { "包名不能为空" }
        val target = packageName.trim()
        val pid = runCatching { shell(device.serial, "pidof", target).trim() }.getOrDefault("")
        val cpu = runCatching { shell(device.serial, "dumpsys", "cpuinfo") }.getOrElse { "采集失败：${it.message}" }
        val cpuLines = cpu.lineSequence().filter {
            it.contains(target, ignoreCase = true) || it.trimStart().startsWith("TOTAL")
        }.take(80).joinToString("\n").ifBlank { "未找到目标进程 CPU 数据" }
        val memory = runCatching { shell(device.serial, "dumpsys", "meminfo", target) }
            .getOrElse { "采集失败：${it.message}" }
        val graphics = runCatching { shell(device.serial, "dumpsys", "gfxinfo", target) }
            .getOrElse { "采集失败：${it.message}" }
        val gfxSummary = graphics.lineSequence().takeWhile { !it.startsWith("---PROFILEDATA---") }
            .take(160).joinToString("\n").ifBlank { "设备未返回渲染数据" }
        return buildString {
            appendLine("性能快照 · $target")
            appendLine("设备：${device.model} / API ${device.api}")
            appendLine("PID：${pid.ifBlank { "未运行" }}")
            appendLine()
            appendLine("===== CPU =====")
            appendLine(cpuLines)
            appendLine()
            appendLine("===== MEMORY =====")
            appendLine(memory.trim())
            appendLine()
            appendLine("===== RENDERING =====")
            append(gfxSummary.trim())
        }
    }

    fun launchIntent(device: AndroidDevice, request: IntentLaunchRequest): String =
        runAdb(listOf("-s", device.serial) + buildStartIntentArgs(request), 60).trim()

    fun collectDiagnostics(device: AndroidDevice, packageName: String, output: File): File {
        require(packageName.isNotBlank()) { "包名不能为空" }
        val target = packageName.trim()
        output.parentFile?.mkdirs()
        val entries = linkedMapOf<String, () -> String>(
            "summary.txt" to {
                "ApkHarden 崩溃与 ANR 采集包\n" +
                    "设备：${device.model}\n序列号：${device.serial}\nAPI：${device.api}\n" +
                    "ABI：${device.abis}\n包名：$target\n" +
                    "说明：普通非 root 设备无法直接读取 /data/anr/traces.txt，已改为采集 lastanr、DropBox、日志和系统服务状态。\n"
            },
            "device-properties.txt" to { shell(device.serial, "getprop") },
            "package.txt" to { shell(device.serial, "dumpsys", "package", target) },
            "processes.txt" to { processes(device, target) },
            "activity-stack.txt" to { activityStack(device, target) },
            "memory.txt" to { shell(device.serial, "dumpsys", "meminfo", target) },
            "cpu.txt" to { shell(device.serial, "dumpsys", "cpuinfo") },
            "last-anr.txt" to { shell(device.serial, "dumpsys", "activity", "lastanr") },
            "dropbox-app-crash.txt" to { shell(device.serial, "dumpsys", "dropbox", "--print", "data_app_crash") },
            "dropbox-app-anr.txt" to { shell(device.serial, "dumpsys", "dropbox", "--print", "data_app_anr") },
            "logcat-main.txt" to { runAdb(listOf("-s", device.serial, "logcat", "-d", "-v", "threadtime", "-t", "3000"), 60) },
            "logcat-crash.txt" to { runAdb(listOf("-s", device.serial, "logcat", "-b", "crash", "-d", "-v", "threadtime", "-t", "1000"), 60) },
        )
        ZipOutputStream(FileOutputStream(output).buffered()).use { zip ->
            entries.forEach { (name, collect) ->
                val content = runCatching(collect).getOrElse { "采集失败：${it.message ?: it.javaClass.simpleName}" }
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        require(output.isFile && output.length() > 0) { "采集包生成失败" }
        return output
    }

    fun clearData(device: AndroidDevice, packageName: String): String {
        require(packageName.isNotBlank()) { "包名不能为空" }
        return shell(device.serial, "pm", "clear", packageName).trim()
    }

    fun grantPermission(device: AndroidDevice, packageName: String, permission: String): String {
        require(packageName.isNotBlank() && permission.isNotBlank()) { "包名和权限不能为空" }
        return shell(device.serial, "pm", "grant", packageName, permission).trim().ifBlank { "授权成功" }
    }

    fun screenshot(device: AndroidDevice, output: File) {
        output.parentFile?.mkdirs()
        val bytes = captureScreenshot(device)
        FileOutputStream(output).use { it.write(bytes) }
    }

    fun captureScreenshot(device: AndroidDevice): ByteArray {
        val bytes = runAdbBytes(listOf("-s", device.serial, "exec-out", "screencap", "-p"), 30)
        require(bytes.isNotEmpty()) { "设备没有返回截图数据" }
        return bytes
    }

    fun screenSize(device: AndroidDevice): DeviceScreenSize =
        parseCurrentDisplaySize(shell(device.serial, "dumpsys", "window", "displays"))
            ?: parseDeviceScreenSize(shell(device.serial, "wm", "size"))

    fun recordScreen(device: AndroidDevice, output: File, seconds: Int = 10): ScreenRecordingMode {
        require(seconds in 1..180) { "录屏时长必须为 1～180 秒" }
        val remote = "/sdcard/apkharden-${System.currentTimeMillis()}.mp4"
        try {
            try {
                runAdb(
                    listOf("-s", device.serial, "shell", "screenrecord", "--time-limit", seconds.toString(), remote),
                    seconds.toLong() + 20,
                )
            } catch (error: IllegalStateException) {
                if (!isScreenRecordUnavailable(error)) throw error
                val scrcpy = scrcpyExecutable()
                if (scrcpy != null) {
                    val recorded = runCatching { recordScreenWithScrcpy(scrcpy, device, output, seconds) }
                    if (recorded.isSuccess) return ScreenRecordingMode.SCRCPY
                }
                recordScreenFromScreenshots(device, output, seconds)
                return ScreenRecordingMode.SCREENSHOT_COMPATIBILITY
            }
            output.parentFile?.mkdirs()
            runAdb(listOf("-s", device.serial, "pull", remote, output.absolutePath), 120)
            require(output.isFile && output.length() > 0) { "录屏文件拉取失败" }
            return ScreenRecordingMode.DEVICE
        } finally {
            runCatching { shell(device.serial, "rm", "-f", remote) }
        }
    }

    private fun recordScreenWithScrcpy(
        executable: File,
        device: AndroidDevice,
        output: File,
        seconds: Int,
    ) {
        output.parentFile?.mkdirs()
        val parent = output.parentFile ?: File(System.getProperty("java.io.tmpdir"))
        val temporary = File.createTempFile("apkharden-scrcpy-", ".mp4", parent).apply { delete() }
        try {
            val command = listOf(executable.absolutePath) +
                buildScrcpyRecordingArgs(device.serial, temporary, seconds)
            val process = ProcessBuilder(command)
                .directory(executable.parentFile)
                .apply { environment()["ADB"] = adbExecutable() }
                .redirectErrorStream(true)
                .start()
            val captured = AtomicReference("")
            val reader = Thread {
                captured.set(process.inputStream.bufferedReader().use { it.readText() })
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(seconds.toLong() + 30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("scrcpy 录屏超时")
            }
            reader.join(5_000)
            if (process.exitValue() != 0) {
                throw IllegalStateException(captured.get().trim().ifBlank { "scrcpy 录屏失败" })
            }
            require(temporary.isFile && temporary.length() > 0) { "scrcpy 没有生成录屏文件" }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        require(output.isFile && output.length() > 0) { "scrcpy 录屏文件保存失败" }
    }

    private fun recordScreenFromScreenshots(device: AndroidDevice, output: File, seconds: Int) {
        output.parentFile?.mkdirs()
        val parent = output.parentFile ?: File(System.getProperty("java.io.tmpdir"))
        val temporary = File.createTempFile("apkharden-screenrecord-", ".mp4", parent)
        try {
            val firstSource = ImageIO.read(ByteArrayInputStream(captureScreenshot(device)))
                ?: throw IllegalStateException("设备截图格式无效，无法启动兼容录屏")
            var latest = normalizeRecordingFrame(firstSource)
            val targetWidth = latest.width
            val targetHeight = latest.height
            val totalFrames = seconds * COMPATIBILITY_RECORDING_FPS
            val frameIntervalNanos = TimeUnit.SECONDS.toNanos(1) / COMPATIBILITY_RECORDING_FPS
            val startedAt = System.nanoTime()
            var encodedFrames = 0
            val encoder = AWTSequenceEncoder.createSequenceEncoder(temporary, COMPATIBILITY_RECORDING_FPS)
            try {
                encoder.encodeImage(latest)
                encodedFrames++
                while (encodedFrames < totalFrames) {
                    val targetTime = startedAt + encodedFrames * frameIntervalNanos
                    val remaining = targetTime - System.nanoTime()
                    if (remaining > 0) {
                        TimeUnit.NANOSECONDS.sleep(remaining)
                    }
                    if (System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(seconds.toLong())) {
                        val source = ImageIO.read(ByteArrayInputStream(captureScreenshot(device)))
                            ?: throw IllegalStateException("设备返回了无效的录屏帧")
                        latest = normalizeRecordingFrame(source, targetWidth, targetHeight)
                    }
                    encoder.encodeImage(latest)
                    encodedFrames++
                }
            } finally {
                encoder.finish()
            }
            require(temporary.isFile && temporary.length() > 0) { "兼容录屏文件生成失败" }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        require(output.isFile && output.length() > 0) { "兼容录屏文件保存失败" }
    }

    private fun shell(serial: String, vararg args: String): String =
        runAdb(listOf("-s", serial, "shell") + args, 60)

    private fun runAdb(args: List<String>, timeoutSeconds: Long): String {
        val command = listOf(adbExecutable()) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val captured = AtomicReference("")
        val reader = Thread {
            captured.set(process.inputStream.bufferedReader().use { it.readText() })
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("ADB 执行超时：${args.joinToString(" ")}")
        }
        reader.join(5_000)
        val output = captured.get()
        if (process.exitValue() != 0) {
            throw IllegalStateException(output.trim().ifBlank { "ADB 执行失败：${args.joinToString(" ")}" })
        }
        return output
    }

    private fun runAdbBytes(args: List<String>, timeoutSeconds: Long): ByteArray {
        val process = ProcessBuilder(listOf(adbExecutable()) + args).redirectErrorStream(false).start()
        val captured = AtomicReference(ByteArray(0))
        val errors = AtomicReference("")
        val reader = Thread { captured.set(process.inputStream.use { it.readBytes() }) }.apply { isDaemon = true; start() }
        val errorReader = Thread { errors.set(process.errorStream.bufferedReader().use { it.readText() }) }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("ADB 执行超时：${args.joinToString(" ")}")
        }
        reader.join(5_000)
        errorReader.join(5_000)
        if (process.exitValue() != 0) throw IllegalStateException(errors.get().trim().ifBlank { "ADB 执行失败" })
        return captured.get()
    }

    internal fun adbExecutable(): String {
        val configured = System.getProperty("apkharden.adb.path")?.takeIf(String::isNotBlank)
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val name = if (windows) "adb.exe" else "adb"
        val sdk = System.getenv("ANDROID_HOME")?.takeIf(String::isNotBlank)
            ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf(String::isNotBlank)
        val home = System.getProperty("user.home")
        return (listOfNotNull(
            configured?.let(::File),
            sdk?.let { File(it, "platform-tools/$name") },
            File(home, "Library/Android/sdk/platform-tools/$name"),
            File(home, "Android/Sdk/platform-tools/$name"),
            System.getenv("LOCALAPPDATA")?.let { File(it, "Android/Sdk/platform-tools/$name") },
            if (windows) File("C:/AndroidSdk/platform-tools/adb.exe") else null,
        ) + executableSearchPaths(name)).firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: name
    }

    internal fun scrcpyExecutable(): File? {
        val configured = System.getProperty("apkharden.scrcpy.path")?.takeIf(String::isNotBlank)
            ?: System.getenv("APK_HARDEN_SCRCPY")?.takeIf(String::isNotBlank)
        val resources = System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)
        val name = if (System.getProperty("os.name").startsWith("Windows", true)) "scrcpy.exe" else "scrcpy"
        return (listOfNotNull(
            configured?.let(::File),
            resources?.let { File(it, "scrcpy/$name") },
            File(System.getProperty("user.dir"), "scrcpy/$name"),
        ) + executableSearchPaths(name)).firstOrNull { it.isFile && it.canExecute() }
    }

    private fun executableSearchPaths(name: String): List<File> =
        (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin"))
            .filter(String::isNotBlank).map { File(it, name) }
}
