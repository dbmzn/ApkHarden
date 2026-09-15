package com.apkharden.packager.device

import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val CLIPBOARD_MAX_BYTES = (1 shl 18) - 5

/** scrcpy 3.3.4 device message framing; keep in sync with the bundled server version. */
internal fun readClipboardMessage(input: DataInputStream): String {
    check(input.readUnsignedByte() == 0) { "不支持的剪贴板消息" }
    val length = input.readInt()
    check(length in 0..CLIPBOARD_MAX_BYTES) { "剪贴板消息长度异常" }
    return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
}

internal data class DeviceClipboardState(
    val connected: Boolean = false,
    val message: String = "正在连接手机剪贴板…",
    val text: String? = null,
    val receivedAt: Long? = null,
)

/** A read-only, control-only scrcpy connection. Clipboard text stays in memory. */
internal class DeviceClipboardSession(private val device: AndroidDevice) : AutoCloseable {
    private val lock = Any()
    @Volatile private var closed = false
    private var socket: Socket? = null
    private var server: Process? = null
    @Volatile var state = DeviceClipboardState()
        private set

    private val worker = Thread(::receive, "apkharden-device-clipboard").apply { isDaemon = true; start() }

    internal fun awaitStopped(timeoutMillis: Long): Boolean {
        worker.join(timeoutMillis)
        return !worker.isAlive
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { socket?.close() }
            server?.destroy()
            state = DeviceClipboardState(message = "接收已停止")
        }
    }

    private fun adb(vararg args: String): String {
        val process = ProcessBuilder(listOf(AdbDeviceService.adbExecutable(), "-s", device.serial) + args)
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "连接设备超时，请检查 USB 调试连接" }
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.exitValue() == 0) { output.ifBlank { "ADB 命令失败" } }
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun receive() {
        val id = UUID.randomUUID().toString().replace("-", "").take(7).padStart(8, '0')
        val remote = "/data/local/tmp/apkharden-clipboard-$id.jar"
        var port: Int? = null
        var launched: Process? = null
        var poller: Thread? = null
        try {
            val executable = AdbDeviceService.scrcpyExecutable()
                ?: error("未找到 scrcpy，请使用已打包的桌面版，或配置 APK_HARDEN_SCRCPY")
            val jar = File(executable.parentFile, "scrcpy-server")
            check(jar.isFile) { "未找到随应用部署的 scrcpy-server" }
            check(!closed)
            adb("push", jar.absolutePath, remote)
            check(!closed)
            port = adb("forward", "tcp:0", "localabstract:scrcpy_$id").toInt()
            synchronized(lock) {
                check(!closed)
                launched = ProcessBuilder(
                    AdbDeviceService.adbExecutable(), "-s", device.serial, "shell",
                    "CLASSPATH=$remote", "app_process", "/", "com.genymobile.scrcpy.Server", "3.3.4",
                    "scid=$id", "video=false", "audio=false", "control=true", "tunnel_forward=true",
                    "send_device_meta=false", "clipboard_autosync=false", "power_on=false", "cleanup=false",
                ).redirectErrorStream(true).start()
                server = launched
            }
            val diagnostics = StringBuilder()
            Thread({
                runCatching {
                    launched!!.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> synchronized(diagnostics) {
                            if (diagnostics.length < 4000) diagnostics.appendLine(line)
                        } }
                    }
                }
            }, "apkharden-clipboard-server-output").apply { isDaemon = true; start() }

            var connected: Socket? = null
            for (attempt in 0 until 50) {
                check(!closed)
                check(launched!!.isAlive) {
                    synchronized(diagnostics) { diagnostics.toString().ifBlank { "手机剪贴板服务启动失败" } }
                }
                val candidate = Socket()
                synchronized(lock) {
                    if (closed) { candidate.close(); error("连接已取消") }
                    socket = candidate
                }
                try {
                    candidate.connect(InetSocketAddress("127.0.0.1", port), 500)
                    candidate.soTimeout = 500
                    check(candidate.getInputStream().read() == 0) { "等待设备服务" }
                    candidate.soTimeout = 0
                    connected = candidate
                    break
                } catch (failure: Exception) {
                    candidate.close()
                    Thread.sleep(100)
                }
            }
            val channel = connected ?: error("连接手机剪贴板超时，请重新连接设备后重试")
            synchronized(lock) {
                check(!closed)
                state = DeviceClipboardState(connected = true, message = "已连接，等待手机返回文字…")
            }
            // With autosync=false, GET_CLIPBOARD replies even for text copied before connecting.
            // NONE (0) reads the existing clipboard without injecting Copy/Cut or writing to it.
            poller = Thread({
                try {
                    while (!closed && !channel.isClosed) {
                        channel.getOutputStream().apply { write(byteArrayOf(8, 0)); flush() }
                        Thread.sleep(1000)
                    }
                } catch (_: Exception) {
                    runCatching { channel.close() }
                }
            }, "apkharden-clipboard-poll").apply { isDaemon = true; start() }
            val input = DataInputStream(channel.getInputStream())
            while (!closed) {
                val text = readClipboardMessage(input)
                synchronized(lock) {
                    if (!closed && text != state.text) {
                        state = DeviceClipboardState(true, "正在自动接收", text, System.currentTimeMillis())
                    }
                }
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                if (!closed) state = state.copy(connected = false,
                    message = "接收已中断：${failure.message ?: "设备连接已断开"}。请重试。")
            }
        } finally {
            poller?.interrupt()
            synchronized(lock) { runCatching { socket?.close() }; launched?.destroy() }
            launched?.let { if (!it.waitFor(800, TimeUnit.MILLISECONDS)) it.destroyForcibly() }
            port?.let { runCatching { adb("forward", "--remove", "tcp:$it") } }
            runCatching { adb("shell", "rm", "-f", remote) }
        }
    }
}
