package com.apkharden.packager.device

import com.apkharden.packager.core.BarcodeTool
import com.apkharden.packager.core.CodeKind
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files

class WirelessAdbQrTest {
    private val guid = "adb-phone-ABC123"
    private val pairingAddress = "192.168.1.2:37111"
    private val connectAddress = "192.168.1.2:37222"
    private fun pairing(session: WirelessQrSession) = "${session.serviceName} _adb-tls-pairing._tcp $pairingAddress"
    private val connection get() = "$guid _adb-tls-connect._tcp $connectAddress"
    private val success get() = "Enter pairing code: Successfully paired to $pairingAddress [guid=$guid]"
    private val mdnsSerial get() = "$guid._adb-tls-connect._tcp"

    @Test fun `fresh credentials produce an Android ADB QR that can be decoded`() {
        val first = WirelessQrSession.create()
        val second = WirelessQrSession.create()
        assertNotEquals(first.serviceName, second.serviceName)
        assertNotEquals(first.password, second.password)
        assertTrue(first.serviceName.matches(Regex("studio-[A-Za-z0-9]{16}")))
        assertTrue(first.password.matches(Regex("[A-Za-z0-9]{24}")))
        val code = BarcodeTool.generate(CodeKind.QR, first.payload)
        assertEquals("WIFI:T:ADB;S:${first.serviceName};P:${first.password};;", BarcodeTool.decode(code.image).single().text)
        assertFalse(first.toString().contains(first.password))
    }

    @Test fun `only pairs the scanned service and selects its already connected GUID`() = runBlocking {
        val session = WirelessQrSession.create()
        var scans = 0
        var paired = false
        val stages = mutableListOf<WirelessQrStage>()
        val flow = WirelessQrPairing(command = { args, input ->
            when (args.first()) {
                "mdns" -> {
                    scans++
                    "other _adb-tls-pairing._tcp 192.168.1.9:37000\n" +
                        if (scans >= 2) "${session.serviceName}._adb-tls-pairing._tcp.local. _adb-tls-pairing._tcp. $pairingAddress" else ""
                }
                "pair" -> {
                    assertEquals(listOf("pair", pairingAddress), args)
                    assertEquals(session.password, input)
                    paired = true
                    success
                }
                "devices" -> {
                    assertTrue(paired)
                    "other._adb-tls-connect._tcp device\n$mdnsSerial device product:phone\n"
                }
                else -> error("Unexpected command $args")
            }
        }, pollMillis = 1)
        assertEquals(mdnsSerial, flow.connect(session, stages::add))
        assertEquals(listOf(WirelessQrStage.WAITING, WirelessQrStage.PAIRING, WirelessQrStage.CONNECTING), stages)
    }

    @Test fun `uses TLS connection port retries failure and waits until actual device state`() = runBlocking {
        val session = WirelessQrSession.create()
        var paired = false
        var attempts = 0
        var deviceReads = 0
        val flow = WirelessQrPairing(command = { args, _ ->
            when (args.first()) {
                "mdns" -> if (paired) "$connection\nother _adb-tls-connect._tcp 192.168.1.9:38000" else pairing(session)
                "pair" -> { paired = true; success }
                "devices" -> {
                    deviceReads++
                    "other._adb-tls-connect._tcp device\n$connectAddress ${if (attempts >= 2) "device" else "offline"}"
                }
                "connect" -> {
                    assertEquals(listOf("connect", connectAddress), args)
                    attempts++
                    if (attempts == 1) error("connection refused")
                    "connected to $connectAddress"
                }
                else -> error("Unexpected command $args")
            }
        }, pollMillis = 1)
        assertEquals(connectAddress, flow.connect(session) {})
        assertEquals(2, attempts)
        assertEquals(3, deviceReads)
    }

    @Test fun `never connects other GUIDs even at the same IP and reports paired timeout`() = runBlocking {
        val session = WirelessQrSession.create()
        val flow = WirelessQrPairing(command = { args, _ ->
            when (args.first()) {
                "mdns" -> "${pairing(session)}\nother _adb-tls-connect._tcp $connectAddress"
                "pair" -> success
                "devices" -> "$connectAddress device\nother._adb-tls-connect._tcp device"
                else -> error("Must not connect an unrelated service")
            }
        }, connectTimeoutMillis = 60, pollMillis = 1)
        val error = runCatching { flow.connect(session) {} }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("配对成功，但设备尚未上线"))
    }

    @Test fun `scan expires without pairing other sessions`() = runBlocking {
        val session = WirelessQrSession.create()
        val oldSession = WirelessQrSession.create()
        val flow = WirelessQrPairing(command = { args, _ ->
            assertEquals(listOf("mdns", "services"), args)
            pairing(oldSession)
        }, scanTimeoutMillis = 60, pollMillis = 1)
        val error = runCatching { flow.connect(session) {} }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("二维码已过期"))
    }

    @Test fun `pair failure hides secret and does not proceed to connect`() = runBlocking {
        val session = WirelessQrSession.create()
        val flow = WirelessQrPairing(command = { args, _ ->
            when (args.first()) {
                "mdns" -> pairing(session)
                "pair" -> "Failed: ${session.password}"
                else -> error("Must not connect after failed pairing")
            }
        })
        val error = runCatching { flow.connect(session) {} }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("Failed"))
        assertFalse(error.message!!.contains(session.password))
    }

    @Test fun `missing GUID is reported rather than choosing arbitrary devices`() = runBlocking {
        val session = WirelessQrSession.create()
        val flow = WirelessQrPairing(command = { args, _ ->
            when (args.first()) {
                "mdns" -> pairing(session)
                "pair" -> "Successfully paired to $pairingAddress"
                else -> error("Must not guess a device without its GUID")
            }
        })
        val error = runCatching { flow.connect(session) {} }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("未返回设备标识"))
    }

    @Test fun `closing during pairing cancels and cannot select a device`() = runBlocking {
        val session = WirelessQrSession.create()
        val pairingStarted = CompletableDeferred<Unit>()
        var selected = false
        val flow = WirelessQrPairing(command = { args, _ ->
            when (args.first()) {
                "mdns" -> pairing(session)
                "pair" -> { pairingStarted.complete(Unit); awaitCancellation() }
                else -> error("Must not continue a cancelled attempt")
            }
        })
        val job = launch { flow.connect(session) {}; selected = true }
        withTimeout(2_000) { pairingStarted.await(); job.cancelAndJoin() }
        assertFalse(selected)
    }

    @Test fun `Bonjour conflict aliases use other connection ports without selecting USB`() = runBlocking {
        val session = WirelessQrSession.create()
        val staleAddress = "192.168.1.2:37000"
        var connected = false
        val attempts = mutableListOf<String>()
        val flow = WirelessQrPairing(command = { args, _ ->
            when (args.first()) {
                "mdns" -> "${pairing(session)}\n$guid _adb-tls-connect._tcp $staleAddress\n$guid (2) _adb-tls-connect._tcp $connectAddress"
                "pair" -> success
                "devices" -> "USB-PHONE device model:other\n" + if (connected) "$connectAddress device" else ""
                "connect" -> {
                    attempts += args[1]
                    if (args[1] == staleAddress) "failed to connect" else {
                        connected = true
                        "connected to $connectAddress"
                    }
                }
                else -> error("Unexpected command $args")
            }
        }, pollMillis = 1)
        assertEquals(connectAddress, flow.connect(session) {})
        assertEquals(listOf(staleAddress, connectAddress), attempts)
    }

    @Test fun `protocol fault identifies scanned endpoint with actionable diagnostics`() = runBlocking {
        val session = WirelessQrSession.create()
        var target = ""
        val flow = WirelessQrPairing(command = { args, _ ->
            if (args.first() == "mdns") pairing(session)
            else error("Enter pairing code: error: protocol fault (couldn't read status message): Undefined error: 0")
        }, onTarget = { target = it })
        val error = runCatching { flow.connect(session) {} }.exceptionOrNull()
        assertEquals(pairingAddress, target)
        assertTrue(error!!.message!!.contains("未能完成"))
        assertTrue(error.message!!.contains(pairingAddress))
        assertTrue(error.message!!.contains("网络是否允许设备互访"))
        assertFalse(error.message!!.contains("Enter pairing code:"))
    }

    @Test fun `online Bonjour serials containing spaces remain selectable in the device list`() = runBlocking {
        val session = WirelessQrSession.create()
        val serial = "$guid (2)._adb-tls-connect._tcp"
        val line = "$serial device product:phone model:Scanned_Phone"
        assertEquals(listOf(serial, "device", "product:phone", "model:Scanned_Phone"), adbDeviceColumns(line))
        val flow = WirelessQrPairing(command = { args, _ ->
            when (args.first()) {
                "mdns" -> pairing(session)
                "pair" -> success
                "devices" -> "USB-PHONE device model:other\n$line"
                else -> error("Already online")
            }
        })
        assertEquals(serial, flow.connect(session) {})
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `real process stdin and prompt output select scanned phone while another USB phone is online`() = runBlocking {
        val root = Files.createTempDirectory("apkharden-qr-prompt-").toFile()
        val previous = System.getProperty("apkharden.adb.path")
        val session = WirelessQrSession.create()
        val fake = root.resolve("adb")
        fake.writeText("""
            #!/bin/sh
            case "${'$'}1" in
              mdns) printf '%s\n' '${session.serviceName} _adb-tls-pairing._tcp $pairingAddress' ;;
              pair)
                test "${'$'}2" = '$pairingAddress' || exit 1
                printf 'Enter pairing code: '
                IFS= read -r password
                test "${'$'}{#password}" -eq 24 || exit 1
                printf '%s\n' 'Successfully paired to $pairingAddress [guid=$guid]'
                ;;
              devices) printf '%s\n' 'List of devices attached' 'USB-PHONE device model:other' '$mdnsSerial device model:scanned' ;;
              *) exit 1 ;;
            esac
        """.trimIndent())
        check(fake.setExecutable(true))
        try {
            System.setProperty("apkharden.adb.path", fake.absolutePath)
            assertEquals(mdnsSerial, WirelessQrPairing().connect(session) {})
        } finally {
            if (previous == null) System.clearProperty("apkharden.adb.path") else System.setProperty("apkharden.adb.path", previous)
            root.deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `cancelling default discovery kills the running adb process`() = runBlocking {
        val root = Files.createTempDirectory("apkharden-qr-cancel-").toFile()
        val previous = System.getProperty("apkharden.adb.path")
        val marker = root.resolve("pid")
        val fake = root.resolve("adb")
        fake.writeText("#!/bin/sh\nprintf '%s' \"${'$'}${'$'}\" > '${marker.absolutePath}'\nexec sleep 30\n")
        check(fake.setExecutable(true))
        try {
            System.setProperty("apkharden.adb.path", fake.absolutePath)
            val job = launch { WirelessQrPairing().connect(WirelessQrSession.create()) {} }
            withTimeout(5_000) { while (!marker.exists() || marker.readText().isBlank()) delay(10) }
            val process = ProcessHandle.of(marker.readText().toLong()).orElseThrow()
            withTimeout(5_000) { job.cancelAndJoin(); while (process.isAlive) delay(10) }
            assertFalse(process.isAlive)
        } finally {
            if (previous == null) System.clearProperty("apkharden.adb.path") else System.setProperty("apkharden.adb.path", previous)
            root.deleteRecursively()
        }
    }
}
