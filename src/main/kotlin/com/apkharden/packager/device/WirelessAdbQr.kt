package com.apkharden.packager.device

import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

// Keep credentials in memory for one dialog attempt. Do not include them in toString/logs.
internal class WirelessQrSession private constructor(val serviceName: String, val password: String) {
    val payload: String get() = "WIFI:T:ADB;S:$serviceName;P:$password;;"

    companion object {
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        fun create(): WirelessQrSession {
            fun token(length: Int) = buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            return WirelessQrSession("studio-${token(16)}", token(24))
        }
    }
}

internal enum class WirelessQrStage { WAITING, PAIRING, CONNECTING }

private fun WirelessService.instanceName(): String = name.removeSuffix(".")
    .removeSuffix(".$type.local").removeSuffix(".$type").replace(Regex(" \\([0-9]+\\)$"), "")

private fun wirelessGuid(serial: String): String = serial.removeSuffix(".").removeSuffix(".local")
    .removeSuffix("._adb-tls-connect._tcp").replace(Regex(" \\([0-9]+\\)$"), "")

/** QR scan advertises the requested service on the phone; the desktop only discovers it.
 * After pairing, match the returned GUID, never just the phone's IP or a newly listed device.
 */
internal class WirelessQrPairing(
    private val command: suspend (List<String>, String?) -> String = { args, input ->
        runInterruptible(Dispatchers.IO) { WirelessAdb.run(args, input) }
    },
    private val scanTimeoutMillis: Long = 120_000,
    private val connectTimeoutMillis: Long = 30_000,
    private val pollMillis: Long = 1_000,
    private val onTarget: (String) -> Unit = {},
) {
    suspend fun connect(session: WirelessQrSession, onStage: (WirelessQrStage) -> Unit): String {
        onStage(WirelessQrStage.WAITING)
        val pairingService = withTimeoutOrNull(scanTimeoutMillis) {
            var found: WirelessService? = null
            while (found == null) {
                currentCoroutineContext().ensureActive()
                found = discover().firstOrNull {
                    it.type == "_adb-tls-pairing._tcp" && it.instanceName() == session.serviceName
                }
                if (found == null) delay(pollMillis)
            }
            found
        } ?: error("二维码已过期，请刷新后重新扫描。若一直未发现手机，请检查同一 Wi-Fi、无线调试及网络是否允许设备发现，或改用配对码。")

        currentCoroutineContext().ensureActive()
        onTarget(pairingService.address)
        onStage(WirelessQrStage.PAIRING)
        val output = try {
            normalizePairingOutput(command(listOf("pair", pairingService.address), session.password))
                .replace(session.password, "******")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(pairingFailureMessage(pairingService.address, (e.message ?: "ADB 配对失败").replace(session.password, "******")))
        }
        val success = output.lineSequence().map(String::trim).firstOrNull { it.startsWith("Successfully paired to ") }
        check(success != null) { pairingFailureMessage(pairingService.address, output) }
        val guid = Regex("\\[guid=([A-Za-z0-9_-]+)]").find(success)?.groupValues?.get(1)
            ?: error("配对已完成，但 ADB 未返回设备标识。请刷新设备列表或在“配对码 / 手动连接”中连接。")

        onStage(WirelessQrStage.CONNECTING)
        var lastFailure: String? = null
        var connectAttempt = 0
        val serial = withTimeoutOrNull(connectTimeoutMillis) {
            var connected: String? = null
            while (connected == null) {
                currentCoroutineContext().ensureActive()
                val services = discover().filter { it.type == "_adb-tls-connect._tcp" && it.instanceName() == guid }
                val addresses = services.map { it.address }.toSet()
                connected = command(listOf("devices", "-l"), null).lineSequence().map(::adbDeviceColumns)
                    .firstOrNull { parts ->
                        parts.getOrNull(1) == "device" && (parts[0] in addresses ||
                            wirelessGuid(parts[0]) == guid)
                    }?.first()
                if (connected == null) {
                    // ADB normally auto-connects. Explicitly connect its matching TLS service as a fallback.
                    services.takeIf { it.isNotEmpty() }?.let { candidates ->
                        val service = candidates[connectAttempt++ % candidates.size]
                        try {
                            // One stale discovery endpoint must not consume the whole connection window.
                            val result = withTimeoutOrNull(5_000) { command(listOf("connect", service.address), null) }
                                ?: "连接 ${service.address} 超时"
                            if (!result.lineSequence().any { it.startsWith("connected to ") || it.startsWith("already connected to ") }) {
                                lastFailure = result
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastFailure = e.message
                        }
                    }
                    delay(pollMillis)
                }
            }
            connected
        }
        return serial ?: error("配对成功，但设备尚未上线。请保持无线调试开启，或在“配对码 / 手动连接”中填写无线调试首页的连接地址。" +
            (lastFailure?.takeIf(String::isNotBlank)?.let { "\n${it.replace(session.password, "******")}" } ?: ""))
    }

    private suspend fun discover(): List<WirelessService> = parseWirelessServices(command(listOf("mdns", "services"), null))
}
