package com.apkharden.packager.device

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class AdbFileTransferTest {
    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `failed and cancelled downloads remove partial files without exposing destination`() = runBlocking {
        val root = Files.createTempDirectory("apkharden-transfer-cancel-").toFile()
        val previous = System.getProperty("apkharden.adb.path")
        val marker = root.resolve("started")
        val fake = root.resolve("adb")
        val destination = root.resolve("received.bin")
        val service = AdbFileTransfer(AndroidDevice("fixture", "device", "fixture"))
        fun prepare(ending: String) {
            fake.writeText("""
                #!/bin/sh
                if [ "${'$'}3" = pull ]; then
                    printf partial > "${'$'}5"
                    touch ${quoteDevicePath(marker.absolutePath)}
                    $ending
                fi
                exit 0
            """.trimIndent())
            check(fake.setExecutable(true))
        }
        try {
            System.setProperty("apkharden.adb.path", fake.absolutePath)
            prepare("exit 1")
            assertThrows(IllegalStateException::class.java) { service.download("/sdcard/file.bin", destination) }
            marker.delete()
            prepare("exec sleep 30")
            coroutineScope {
                val job = launch { runInterruptible(Dispatchers.IO) { service.download("/sdcard/file.bin", destination) } }
                withTimeout(5000) { while (!marker.exists()) delay(20) }
                withTimeout(5000) { job.cancelAndJoin() }
            }
            assertFalse(destination.exists())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".apkharden-transfer-") })
        } finally {
            if (previous == null) System.clearProperty("apkharden.adb.path") else System.setProperty("apkharden.adb.path", previous)
            root.deleteRecursively()
        }
    }
    @Test fun `directory names retain whitespace quotes and newlines`() {
        val entries = parseDeviceFiles("f\u0000中文 ' file\n.txt\u0000d\u0000a folder\u0000f\u0000.hidden\u0000".toByteArray())
        assertEquals(DeviceFile("a folder", true), entries.first())
        assertTrue(entries.contains(DeviceFile("中文 ' file\n.txt", false)))
        assertEquals(emptyList<DeviceFile>(), parseDeviceFiles(byteArrayOf()))
        assertThrows(IllegalStateException::class.java) { parseDeviceFiles("f\u0000../escape\u0000".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { parseDeviceFiles("permission denied".toByteArray()) }
    }
    @Test fun `paths normalize navigation without escaping root and shell quoting preserves literals`() {
        assertEquals("/sdcard/Download", devicePath("/sdcard/a/../Download//"))
        assertEquals("/", devicePath("/../../"))
        assertThrows(IllegalArgumentException::class.java) { devicePath("relative") }
        assertThrows(IllegalArgumentException::class.java) { devicePath("/bad\u0000name") }
        assertEquals("'a'\"'\"'b;\$(echo x)'", quoteDevicePath("a'b;\$(echo x)"))
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "APK_HARDEN_FILE_TEST", matches = ".+")
    fun `real device roundtrip preserves bytes unusual names and existing files`() {
        val device = AdbDeviceService.listDevices().single { it.serial == System.getenv("APK_HARDEN_FILE_TEST") }
        val service = AdbFileTransfer(device)
        val root = Files.createTempDirectory("apkharden-file-test-").toFile()
        val remote = "/sdcard/Download/apkharden-file-test-${UUID.randomUUID()}"
        fun shell(script: String) {
            val p = ProcessBuilder(AdbDeviceService.adbExecutable(), "-s", device.serial, "shell", script).redirectErrorStream(true).start()
            val result = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { result }
        }
        try {
            shell("mkdir ${quoteDevicePath(remote)}")
            assertTrue(service.list(remote).isEmpty())
            val original = root.resolve("中文 ' ; dollar\$ test.bin").apply { writeBytes(ByteArray(1024 * 1024) { (it % 251).toByte() }) }
            val target = service.upload(original, remote)
            assertEquals(listOf(DeviceFile(original.name, false)), service.list(remote))
            val downloaded = service.download(target, root.resolve("download.bin"))
            assertArrayEquals(original.readBytes(), downloaded.readBytes())
            assertThrows(IllegalStateException::class.java) { service.upload(original, remote) }
            assertThrows(IllegalArgumentException::class.java) { service.download(target, downloaded) }
            assertArrayEquals(original.readBytes(), downloaded.readBytes())
            assertThrows(IllegalStateException::class.java) { service.download("$remote/missing", root.resolve("missing")) }
            assertFalse(root.resolve("missing").exists())
            val empty = root.resolve("empty.txt").apply { writeBytes(byteArrayOf()) }
            service.upload(empty, remote)
            assertEquals(0L, service.download("$remote/empty.txt", root.resolve("empty-copy.txt")).length())
            assertFalse(service.list(remote).any { it.name.startsWith(".apkharden-transfer-") })
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".apkharden-transfer-") })
        } finally {
            shell("rm -rf ${quoteDevicePath(remote)}")
            root.deleteRecursively()
        }
    }
}
