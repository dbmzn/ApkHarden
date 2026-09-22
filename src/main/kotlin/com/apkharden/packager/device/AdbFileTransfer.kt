package com.apkharden.packager.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class DeviceFile(val name: String, val directory: Boolean)
internal fun quoteDevicePath(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
internal fun devicePath(value: String): String {
    require(value.startsWith('/') && '\u0000' !in value) { "请输入手机上的绝对路径，例如 /sdcard/Download" }
    val parts = mutableListOf<String>()
    value.split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(it) } }
    return "/" + parts.joinToString("/")
}
internal fun parseDeviceFiles(bytes: ByteArray): List<DeviceFile> {
    if (bytes.isEmpty()) return emptyList()
    val fields = bytes.toString(Charsets.UTF_8).split('\u0000')
    check(fields.last().isEmpty() && fields.size % 2 == 1) { "手机目录返回格式异常" }
    return fields.dropLast(1).chunked(2).map { (type, name) ->
        check(type in listOf("d", "f") && name.isNotEmpty() && name !in listOf(".", "..") && '/' !in name) { "手机目录条目异常" }
        DeviceFile(name, type == "d")
    }.sortedWith(compareByDescending<DeviceFile> { it.directory }.thenBy { it.name.lowercase() })
}

internal class AdbFileTransfer(private val device: AndroidDevice) {
    private fun run(args: List<String>, timeout: Long = 30): ByteArray {
        val process = ProcessBuilder(listOf(AdbDeviceService.adbExecutable(), "-s", device.serial) + args)
            .redirectErrorStream(true).start()
        val captured = ByteArrayOutputStream()
        val failure = AtomicReference<Exception?>()
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(captured.size() + count <= 8 * 1024 * 1024) { "目录过大，请进入更具体的子目录" }
                        captured.write(buffer, 0, count)
                    }
                }
            } catch (e: Exception) { failure.set(e); process.destroyForcibly() }
        }, "apkharden-file-transfer-output").apply { isDaemon = true; start() }
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) { "文件操作超时，请检查设备连接" }
            reader.join(2000)
            check(!reader.isAlive) { "读取设备输出超时" }
            failure.get()?.let { throw it }
            val bytes = captured.toByteArray()
            check(process.exitValue() == 0) { bytes.toString(Charsets.UTF_8).trim().ifBlank { "文件操作失败，请检查路径和权限" } }
            return bytes
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
    }
    private fun shell(script: String, timeout: Long = 30) = run(listOf("shell", script), timeout)

    fun list(path: String): List<DeviceFile> {
        val quoted = quoteDevicePath(devicePath(path))
        val script = """
            dir=$quoted
            [ -d "${'$'}dir" ] && [ -r "${'$'}dir" ] && [ -x "${'$'}dir" ] || { echo '目录不存在或没有读取权限'; exit 1; }
            for entry in "${'$'}dir"/* "${'$'}dir"/.[!.]* "${'$'}dir"/..?*; do
                if [ -d "${'$'}entry" ]; then kind=d; elif [ -f "${'$'}entry" ]; then kind=f; else continue; fi
                printf '%s\000%s\000' "${'$'}kind" "${'$'}{entry##*/}"
            done
        """.trimIndent()
        return parseDeviceFiles(shell(script))
    }

    fun upload(local: File, directory: String): String {
        require(local.isFile && local.canRead()) { "请选择可读取的电脑文件" }
        val dir = devicePath(directory)
        val target = devicePath("$dir/${local.name}")
        val quoted = quoteDevicePath(target)
        shell("[ -d ${quoteDevicePath(dir)} ] && [ -w ${quoteDevicePath(dir)} ] || { echo '手机目录不存在或不可写'; exit 1; }; [ ! -e $quoted ] && [ ! -L $quoted ] || { echo '手机已有同名文件，请先重命名电脑文件'; exit 1; }")
        val staging = "$dir/.apkharden-transfer-${UUID.randomUUID()}.part"
        try {
            run(listOf("push", local.absolutePath, staging), 1800)
            // Never expose a partly transferred file or overwrite an existing destination.
            shell("[ ! -e $quoted ] && [ ! -L $quoted ] && mv -n ${quoteDevicePath(staging)} $quoted && [ ! -e ${quoteDevicePath(staging)} ] || { echo '保存失败，目标可能已有同名文件'; exit 1; }")
            return target
        } finally {
            val interrupted = Thread.interrupted()
            try { runCatching { shell("rm -f ${quoteDevicePath(staging)}", 5) } }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    fun download(remote: String, destination: File): File {
        val path = devicePath(remote)
        val target = destination.absoluteFile
        require(target.parentFile.isDirectory) { "请选择有效的电脑保存目录" }
        require(!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "电脑已有同名文件，请选择其他文件名或目录" }
        shell("[ -f ${quoteDevicePath(path)} ] || { echo '请选择可读取的手机文件'; exit 1; }")
        val staging = File.createTempFile(".apkharden-transfer-", ".part", target.parentFile)
        try {
            run(listOf("pull", path, staging.absolutePath), 1800)
            Files.move(staging.toPath(), target.toPath())
            return target
        } finally { staging.delete() }
    }
}
