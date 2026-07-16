package com.apkharden.packager.device

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
            runAdb(listOf("-s", device.serial, "install", "-r", apk.absolutePath), 180)
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

    fun recordScreen(device: AndroidDevice, output: File, seconds: Int = 15) {
        require(seconds in 1..180) { "录屏时长必须为 1～180 秒" }
        val remote = "/sdcard/apkharden-${System.currentTimeMillis()}.mp4"
        try {
            runAdb(listOf("-s", device.serial, "shell", "screenrecord", "--time-limit", seconds.toString(), remote), seconds.toLong() + 20)
            output.parentFile?.mkdirs()
            runAdb(listOf("-s", device.serial, "pull", remote, output.absolutePath), 120)
            require(output.isFile && output.length() > 0) { "录屏文件拉取失败" }
        } finally {
            runCatching { shell(device.serial, "rm", "-f", remote) }
        }
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
        val bytes = process.inputStream.readBytes()
        val error = process.errorStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("ADB 执行超时：${args.joinToString(" ")}")
        }
        if (process.exitValue() != 0) throw IllegalStateException(error.trim().ifBlank { "ADB 执行失败" })
        return bytes
    }

    private fun adbExecutable(): String {
        val sdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(
            sdk?.let { File(it, "platform-tools/adb.exe") },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Android/Sdk/platform-tools/adb.exe") },
            File("C:/AndroidSdk/platform-tools/adb.exe"),
        )
        return candidates.firstOrNull(File::isFile)?.absolutePath ?: "adb"
    }
}
