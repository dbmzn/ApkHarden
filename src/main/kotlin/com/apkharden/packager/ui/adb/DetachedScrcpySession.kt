package com.apkharden.packager.ui.adb

import com.apkharden.packager.device.AdbDeviceService
import com.apkharden.packager.device.AndroidDevice
import com.apkharden.packager.device.buildScrcpyMirrorArgs
import com.apkharden.packager.device.fitMirrorWindow
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.GraphicsEnvironment
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val WINDOW_CHROME_PHYSICAL_ALLOWANCE = 56
private const val TABLET_MIRROR_MAX_VIDEO_SIZE = 1920

internal enum class MirrorPhase {
    IDLE,
    STARTING,
    RUNNING,
    ERROR,
}

internal data class MirrorStatus(val phase: MirrorPhase, val message: String)

/** Launches one controllable scrcpy window and keeps its lifecycle tied to ApkHarden. */
internal class DetachedScrcpySession {
    private val lock = Any()
    @Volatile private var requestedSerial: String? = null
    @Volatile private var process: Process? = null
    @Volatile private var generation: Long = 0
    @Volatile private var latestStatus = MirrorStatus(MirrorPhase.IDLE, "镜像未启动")

    fun start(device: AndroidDevice) {
        synchronized(lock) {
            if (requestedSerial == device.serial && process?.isAlive == true) return
            generation++
            requestedSerial = device.serial
            stopProcessLocked()
            val currentGeneration = generation
            status(MirrorPhase.STARTING, "正在读取 ${device.model} 的屏幕尺寸…")
            Thread(
                { launch(device, currentGeneration) },
                "apkharden-detached-device-mirror",
            ).apply { isDaemon = true; start() }
        }
    }

    fun stop(message: String = "镜像已停止") {
        synchronized(lock) {
            generation++
            requestedSerial = null
            stopProcessLocked()
            status(MirrorPhase.IDLE, message)
        }
    }

    fun close() = stop()

    fun statusSnapshot(): MirrorStatus = latestStatus

    private fun launch(device: AndroidDevice, currentGeneration: Long) {
        val captured = StringBuilder()
        try {
            check(System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                "当前独立镜像窗口仅支持 Windows"
            }
            val executable = AdbDeviceService.scrcpyExecutable()
                ?: throw IllegalStateException("未找到随应用部署的 scrcpy，请重新构建或部署桌面版")
            val deviceSize = AdbDeviceService.screenSize(device)
            val maxVideoSize = TABLET_MIRROR_MAX_VIDEO_SIZE.takeIf {
                maxOf(deviceSize.width, deviceSize.height) > it
            }
            val graphics = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val desktop = graphics.maximumWindowBounds
            val transform = graphics.defaultScreenDevice.defaultConfiguration.defaultTransform
            val windowSize = fitMirrorWindow(
                device = deviceSize,
                maxWidth = (desktop.width * transform.scaleX).roundToInt(),
                maxHeight = (
                    (desktop.height * transform.scaleY).roundToInt() -
                        WINDOW_CHROME_PHYSICAL_ALLOWANCE
                    ).coerceAtLeast(480),
            )
            if (!isCurrent(currentGeneration)) return
            val title = "ApkHarden · ${device.model} · ${UUID.randomUUID()}"
            val launched = ProcessBuilder(
                listOf(executable.absolutePath) + buildScrcpyMirrorArgs(
                    serial = device.serial,
                    windowTitle = title,
                    windowWidth = windowSize.width,
                    windowHeight = windowSize.height,
                    maxVideoSize = maxVideoSize,
                ),
            )
                .directory(executable.parentFile)
                .redirectErrorStream(true)
                .start()
            synchronized(lock) {
                if (!isCurrent(currentGeneration)) {
                    launched.destroyForcibly()
                    return
                }
                process = launched
            }
            Thread({
                launched.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(captured) {
                            if (captured.length < 12_000) captured.appendLine(line)
                        }
                    }
                }
            }, "apkharden-detached-device-mirror-output").apply { isDaemon = true; start() }

            waitForStableMirrorWindow(title, launched, currentGeneration)
            status(
                MirrorPhase.RUNNING,
                "独立镜像窗口已铺满一屏：${windowSize.width} × ${windowSize.height}" +
                    "（设备 ${deviceSize.width} × ${deviceSize.height}" +
                    if (maxVideoSize != null) "，视频最大边 $maxVideoSize）" else "）",
            )

            val exitCode = launched.waitFor()
            synchronized(lock) {
                if (process === launched) process = null
            }
            if (isCurrent(currentGeneration)) {
                requestedSerial = null
                if (exitCode == 0) {
                    status(MirrorPhase.IDLE, "镜像窗口已关闭")
                } else {
                    val detail = synchronized(captured) { captured.toString().trim() }
                    reportError(
                        if (detail.isBlank()) "scrcpy 已退出（代码 $exitCode）"
                        else detail.lineSequence().last(),
                    )
                }
            }
        } catch (failure: Throwable) {
            if (isCurrent(currentGeneration)) {
                requestedSerial = null
                reportError(failure.message ?: "镜像窗口启动失败")
            }
        }
    }

    private fun waitForStableMirrorWindow(title: String, launched: Process, currentGeneration: Long) {
        var previousWindow: HWND? = null
        var stableSamples = 0
        repeat(150) {
            if (!isCurrent(currentGeneration)) throw IllegalStateException("镜像启动已取消")
            if (!launched.isAlive) throw IllegalStateException("scrcpy 启动失败")
            val currentWindow = User32.INSTANCE.FindWindow(null, title)
            if (currentWindow != null && currentWindow == previousWindow) {
                stableSamples++
                if (stableSamples >= 5) return
            } else {
                previousWindow = currentWindow
                stableSamples = if (currentWindow == null) 0 else 1
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("等待 scrcpy 镜像窗口稳定超时")
    }

    private fun isCurrent(value: Long): Boolean = generation == value && requestedSerial != null

    private fun stopProcessLocked() {
        process?.let { running ->
            if (running.isAlive) {
                running.destroy()
                Thread({
                    if (!running.waitFor(800, TimeUnit.MILLISECONDS)) running.destroyForcibly()
                }, "apkharden-detached-device-mirror-stop").apply { isDaemon = true; start() }
            }
        }
        process = null
    }

    private fun reportError(message: String) = status(MirrorPhase.ERROR, message)

    private fun status(phase: MirrorPhase, message: String) {
        latestStatus = MirrorStatus(phase, message)
    }
}
