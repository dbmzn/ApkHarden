package com.apkharden.packager.device

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class WirelessService(val name: String, val type: String, val address: String)

internal fun wirelessAddress(value: String): String {
    val address = value.trim()
    val match = Regex("(?:[A-Za-z0-9][A-Za-z0-9._-]*|\\[[0-9a-fA-F:]+(?:%[A-Za-z0-9_.-]+)?]):([0-9]{1,5})").matchEntire(address)
    require(match != null && match.groupValues[1].toInt() in 1..65535) { "请输入有效的 IP 或主机名及端口，例如 192.168.1.10:37123" }
    return address
}

internal fun parseWirelessServices(output: String): List<WirelessService> = output.lineSequence().mapNotNull { line ->
    val parts = line.trim().split(Regex("\\s+"))
    if (parts.size < 3 || parts[1] !in listOf("_adb._tcp", "_adb-tls-pairing._tcp", "_adb-tls-connect._tcp")) null
    else runCatching { WirelessService(parts[0], parts[1], wirelessAddress(parts[2])) }.getOrNull()
}.distinct().toList()

internal fun wifiIpv4(output: String): String {
    val addresses = Regex("\\binet ([0-9.]+)/[0-9]+").findAll(output).map { it.groupValues[1] }.distinct().toList()
    require(addresses.size == 1) { "未能确定手机 Wi-Fi 地址，请连接 Wi-Fi 后重试，或使用手动连接" }
    return addresses.single()
}

internal fun isWirelessSerial(serial: String): Boolean = ':' in serial || "_adb-tls-connect._tcp" in serial

internal object WirelessAdb {
    private fun run(args: List<String>, input: String? = null): String {
        val process = ProcessBuilder(listOf(AdbDeviceService.adbExecutable()) + args).redirectErrorStream(true).start()
        val captured = AtomicReference("")
        val reader = Thread({ captured.set(process.inputStream.bufferedReader().use { it.readText() }) }, "apkharden-wireless-adb-output")
            .apply { isDaemon = true; start() }
        try {
            process.outputStream.bufferedWriter().use { if (input != null) { it.write(input); it.newLine() } }
            check(process.waitFor(30, TimeUnit.SECONDS)) { "ADB 连接超时，请检查手机无线调试和 Wi-Fi 网络" }
            reader.join(2000)
            val output = captured.get().trim().let { if (input == null) it else it.replace(input, "******") }
            check(process.exitValue() == 0) { output.ifBlank { "ADB 命令失败" } }
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun version(): String = run(listOf("version"))
    fun discover(): List<WirelessService> = parseWirelessServices(run(listOf("mdns", "services")))

    fun pair(address: String, code: String): String {
        require(Regex("[0-9]{6}").matches(code)) { "请输入手机显示的 6 位配对码" }
        val output = run(listOf("pair", wirelessAddress(address)), code)
        check(output.contains("Successfully paired", ignoreCase = true)) { output.ifBlank { "配对失败，请重新获取配对码" } }
        return "配对成功。设备可能自动连接；若未出现，请使用无线调试首页的连接端口连接。"
    }

    fun connect(address: String): String {
        val target = wirelessAddress(address)
        val output = run(listOf("connect", target))
        check(output.lineSequence().any { it.startsWith("connected to ") || it.startsWith("already connected to ") }) {
            output.ifBlank { "连接失败" }
        }
        check(run(listOf("-s", target, "get-state")).trim() == "device") { "设备尚未授权或离线，请在手机确认调试授权后重试" }
        return target
    }

    fun disconnect(serial: String) {
        require(isWirelessSerial(serial)) { "请选择无线连接的设备" }
        run(listOf("disconnect", serial))
    }

    fun enableLegacy(device: AndroidDevice): String {
        val usb = run(listOf("devices", "-l")).lineSequence().any {
            val parts = it.trim().split(Regex("\\s+"))
            parts.firstOrNull() == device.serial && parts.getOrNull(1) == "device" && parts.any { part -> part.startsWith("usb:") }
        }
        require(usb) { "请先通过 USB 连接并授权手机，再选择该 USB 设备" }
        val ip = wifiIpv4(run(listOf("-s", device.serial, "shell", "ip", "-4", "addr", "show", "wlan0")))
        val output = run(listOf("-s", device.serial, "tcpip", "5555"))
        check(output.contains("restarting in TCP mode", ignoreCase = true)) { output.ifBlank { "开启无线调试失败" } }
        // adbd restarts when switching transports. Retry only the explicitly selected device.
        var failure: Exception? = null
        repeat(5) {
            try { return connect("$ip:5555") } catch (e: Exception) { failure = e; Thread.sleep(700) }
        }
        error("手机已开启 TCP 5555，但连接失败：${failure?.message}。可手动连接 $ip:5555；关闭时保持 USB 连接并点击“关闭旧版无线调试”。")
    }

    fun disableLegacy(device: AndroidDevice): String {
        val output = run(listOf("-s", device.serial, "usb"))
        check(output.contains("restarting in USB mode", ignoreCase = true)) { output.ifBlank { "关闭失败" } }
        if (isWirelessSerial(device.serial)) runCatching { disconnect(device.serial) }
        return "已关闭旧版无线调试，请使用 USB 连接"
    }
}
