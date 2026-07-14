package com.apkharden.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture

class RuntimeSmokeRunnerTest {
    @Test
    fun `jdwp client handshakes and resumes the virtual machine`() {
        val server = ServerSocket(0)
        val command = CompletableFuture.supplyAsync {
            server.use { listener ->
                listener.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val handshake = input.readNBytes("JDWP-Handshake".length)
                    output.write(handshake)
                    output.flush()
                    val packet = input.readNBytes(11)
                    val packetId = ByteBuffer.wrap(packet, 4, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    output.write(
                        ByteBuffer.allocate(11)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putInt(11)
                            .putInt(packetId)
                            .put(0x80.toByte())
                            .putShort(0)
                            .array(),
                    )
                    output.flush()
                    packet
                }
            }
        }

        JdwpClient().resume(server.localPort)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 11, 0, 0, 0, 1, 0, 1, 9),
            command.get(),
        )
    }

    @Test
    fun `process executor drains output while the command is running`() {
        val javaExecutable = File(
            System.getProperty("java.home"),
            if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java",
        ).absolutePath

        val result = ProcessCommandExecutor().execute(
            listOf(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                LargeOutputCommand::class.java.name,
            ),
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.length > 1_000_000)
        assertTrue(result.stderr.isEmpty())
    }

    @Test
    fun `reads the selected device profile`() {
        val executor = RecordingExecutor(
            mapOf(
                listOf("-s", "device-29", "shell", "getprop", "ro.build.version.sdk") to "29\n",
                listOf("-s", "device-29", "shell", "getprop", "ro.product.cpu.abilist") to
                    "arm64-v8a,armeabi-v7a\n",
                listOf("-s", "device-29", "shell", "getconf", "PAGE_SIZE") to "4096\n",
            ),
        )

        val profile = AdbClient("adb", "device-29", executor).readProfile()

        assertEquals(DeviceProfile(29, listOf("arm64-v8a", "armeabi-v7a"), 4096), profile)
        assertTrue(executor.commands.all { it.first() == "adb" && it.drop(1).take(2) == listOf("-s", "device-29") })
    }

    @Test
    fun `survival smoke installs launches and reaches both processes`() {
        val executor = ScenarioExecutor(
            pidResponses = ArrayDeque(listOf("321\n", "654\n")),
        )
        val runner = RuntimeSmokeRunner(
            AdbClient("adb", "device-29", executor),
            wait = {},
        )

        val result = runner.verifySurvives(
            SmokeTarget(
                apk = "fixture.apk",
                packageName = "com.example.fixture",
                activity = ".SmokeActivity",
                mainProbeUri = "content://com.example.fixture.main",
                workerProbeUri = "content://com.example.fixture.worker",
            ),
        )

        assertTrue(result.passed)
        assertEquals(setOf("321", "654"), result.processIds)
        assertTrue(executor.commands.any { "install -r fixture.apk" in it.joinToString(" ") })
        assertTrue(executor.commands.any { "am start -W" in it.joinToString(" ") })
        assertEquals(2, executor.commands.count { "content call" in it.joinToString(" ") })
    }

    @Test
    fun `termination smoke rejects a dead process without ANR or restart loop`() {
        val executor = ScenarioExecutor(
            pidResponses = ArrayDeque(listOf("123\n", "", "", "")),
            logcat = "I ActivityTaskManager: Force stopping com.example.fixture\n",
        )
        var resumedPort: Int? = null
        val runner = RuntimeSmokeRunner(
            AdbClient("adb", "device-29", executor) { port -> resumedPort = port },
            wait = {},
        )

        val result = runner.verifyTerminates(
            SmokeTarget(
                apk = "bad.apk",
                packageName = "com.example.fixture",
                activity = ".SmokeActivity",
            ),
            debug = true,
        )

        assertTrue(result.passed)
        assertFalse(result.anrDetected)
        assertFalse(result.restartLoopDetected)
        assertEquals(8700, resumedPort)
        assertTrue(executor.commands.any { "am start -W -D" in it.joinToString(" ") })
        assertTrue(executor.commands.any { "forward tcp:0 jdwp:123" in it.joinToString(" ") })
        assertTrue(executor.commands.any { "forward --remove tcp:8700" in it.joinToString(" ") })
    }

    private class RecordingExecutor(
        private val responses: Map<List<String>, String>,
    ) : CommandExecutor {
        val commands = mutableListOf<List<String>>()

        override fun execute(command: List<String>): CommandResult {
            commands += command
            val arguments = command.drop(1)
            return CommandResult(0, responses.getValue(arguments), "")
        }
    }

    private class ScenarioExecutor(
        private val pidResponses: ArrayDeque<String>,
        private val logcat: String = "",
    ) : CommandExecutor {
        val commands = mutableListOf<List<String>>()

        override fun execute(command: List<String>): CommandResult {
            commands += command
            val text = command.joinToString(" ")
            val output = when {
                "pidof" in text -> pidResponses.removeFirstOrNull().orEmpty()
                "forward tcp:0" in text -> "8700\n"
                "content call" in text -> "Result: Bundle[{process=ok}]\n"
                "logcat -d" in text -> logcat
                else -> "Success\n"
            }
            return CommandResult(0, output, "")
        }
    }
}

object LargeOutputCommand {
    @JvmStatic
    fun main(arguments: Array<String>) {
        repeat(12_000) {
            println("x".repeat(100))
        }
    }
}
