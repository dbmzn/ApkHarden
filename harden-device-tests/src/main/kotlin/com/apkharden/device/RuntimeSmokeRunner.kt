package com.apkharden.device

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    fun requireSuccess(operation: String): CommandResult {
        check(exitCode == 0) {
            "$operation failed with exit code $exitCode: ${(stdout + stderr).trim()}"
        }
        return this
    }
}

fun interface CommandExecutor {
    fun execute(command: List<String>): CommandResult
}

class ProcessCommandExecutor : CommandExecutor {
    override fun execute(command: List<String>): CommandResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        var stdout = ""
        var stderr = ""
        val stdoutReader = thread(isDaemon = true, name = "command-stdout") {
            stdout = process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrReader = thread(isDaemon = true, name = "command-stderr") {
            stderr = process.errorStream.bufferedReader().use { it.readText() }
        }

        val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(PROCESS_DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        stdoutReader.join(STREAM_DRAIN_TIMEOUT_MILLIS)
        stderrReader.join(STREAM_DRAIN_TIMEOUT_MILLIS)

        check(completed) { "Command timed out: ${command.joinToString(" ")}" }
        check(!stdoutReader.isAlive && !stderrReader.isAlive) {
            "Command output did not finish draining: ${command.joinToString(" ")}"
        }
        return CommandResult(
            exitCode = process.exitValue(),
            stdout = stdout,
            stderr = stderr,
        )
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 60L
        const val PROCESS_DESTROY_TIMEOUT_SECONDS = 5L
        const val STREAM_DRAIN_TIMEOUT_MILLIS = 5_000L
    }
}

class JdwpClient {
    fun resume(port: Int) {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(HANDSHAKE)
            output.flush()
            check(input.readFully(HANDSHAKE.size).contentEquals(HANDSHAKE)) {
                "JDWP handshake mismatch"
            }

            output.write(
                ByteBuffer.allocate(COMMAND_HEADER_SIZE)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(COMMAND_HEADER_SIZE)
                    .putInt(PACKET_ID)
                    .put(COMMAND_PACKET_FLAGS)
                    .put(VIRTUAL_MACHINE_COMMAND_SET)
                    .put(RESUME_COMMAND)
                    .array(),
            )
            output.flush()

            val reply = ByteBuffer.wrap(input.readFully(REPLY_HEADER_SIZE))
                .order(ByteOrder.BIG_ENDIAN)
            val length = reply.int
            check(length >= REPLY_HEADER_SIZE) { "Invalid JDWP reply length: $length" }
            check(reply.int == PACKET_ID) { "JDWP reply packet id mismatch" }
            check(reply.get() == REPLY_PACKET_FLAGS) { "JDWP reply flags mismatch" }
            check(reply.short.toInt() == 0) { "JDWP resume command failed" }
            if (length > REPLY_HEADER_SIZE) input.readFully(length - REPLY_HEADER_SIZE)
        }
    }

    private fun java.io.InputStream.readFully(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            check(read >= 0) { "JDWP connection closed after $offset of $size bytes" }
            offset += read
        }
        return bytes
    }

    private companion object {
        val HANDSHAKE = "JDWP-Handshake".toByteArray(Charsets.US_ASCII)
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val COMMAND_HEADER_SIZE = 11
        const val REPLY_HEADER_SIZE = 11
        const val PACKET_ID = 1
        const val COMMAND_PACKET_FLAGS: Byte = 0
        const val REPLY_PACKET_FLAGS: Byte = 0x80.toByte()
        const val VIRTUAL_MACHINE_COMMAND_SET: Byte = 1
        const val RESUME_COMMAND: Byte = 9
    }
}

data class DeviceProfile(
    val apiLevel: Int,
    val abis: List<String>,
    val pageSize: Int,
)

data class SmokeTarget(
    val apk: String,
    val packageName: String,
    val activity: String,
    val mainProbeUri: String? = null,
    val workerProbeUri: String? = null,
)

data class SmokeResult(
    val passed: Boolean,
    val processIds: Set<String> = emptySet(),
    val anrDetected: Boolean = false,
    val restartLoopDetected: Boolean = false,
    val diagnostics: String = "",
)

class AdbClient(
    private val executable: String,
    private val serial: String,
    private val executor: CommandExecutor = ProcessCommandExecutor(),
    private val jdwpResumer: (Int) -> Unit = JdwpClient()::resume,
) {
    fun readProfile(): DeviceProfile = DeviceProfile(
        apiLevel = shell("getprop", "ro.build.version.sdk").stdout.trim().toInt(),
        abis = shell("getprop", "ro.product.cpu.abilist").stdout
            .trim()
            .split(',')
            .filter(String::isNotBlank),
        pageSize = shell("getconf", "PAGE_SIZE").stdout.trim().toInt(),
    )

    fun install(apk: String) {
        val result = execute("install", "-r", apk).requireSuccess("adb install")
        check("Success" in result.stdout) { "adb install did not report success: ${result.stdout}" }
    }

    fun clearLogcat() {
        execute("logcat", "-c").requireSuccess("adb logcat -c")
    }

    fun forceStop(packageName: String) {
        shell("am", "force-stop", packageName).requireSuccess("am force-stop")
    }

    fun start(packageName: String, activity: String, debug: Boolean): CommandResult {
        val arguments = buildList {
            add("am")
            add("start")
            add("-W")
            if (debug) add("-D")
            add("-n")
            add("$packageName/$activity")
        }
        val result = shell(*arguments.toTypedArray()).requireSuccess("am start")
        check("Error:" !in result.stdout && "Exception" !in result.stdout) {
            "am start reported failure: ${result.stdout}"
        }
        return result
    }

    fun call(uri: String): CommandResult = shell(
        "content",
        "call",
        "--uri",
        uri,
        "--method",
        "probe",
    ).requireSuccess("content call $uri")

    fun pidOf(processName: String): Set<String> = shell("pidof", processName).stdout
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .toSet()

    fun resumeDebugger(processId: String) {
        val localPort = execute("forward", "tcp:0", "jdwp:$processId")
            .requireSuccess("adb forward jdwp:$processId")
            .stdout
            .trim()
            .toInt()
        try {
            jdwpResumer(localPort)
        } finally {
            execute("forward", "--remove", "tcp:$localPort")
                .requireSuccess("adb forward --remove tcp:$localPort")
        }
    }

    fun logcat(): String = execute("logcat", "-d", "-t", "2000", "-v", "brief")
        .requireSuccess("adb logcat -d")
        .stdout

    private fun shell(vararg arguments: String): CommandResult = execute(
        "shell",
        *arguments,
    )

    private fun execute(vararg arguments: String): CommandResult = executor.execute(
        listOf(executable, "-s", serial) + arguments,
    )
}

class RuntimeSmokeRunner(
    private val adb: AdbClient,
    private val wait: (Long) -> Unit = Thread::sleep,
) {
    fun verifySurvives(target: SmokeTarget): SmokeResult {
        adb.install(target.apk)
        adb.clearLogcat()
        adb.forceStop(target.packageName)
        adb.start(target.packageName, target.activity, debug = false)
        wait(START_SETTLE_MILLIS)

        target.mainProbeUri?.let(adb::call)
        target.workerProbeUri?.let(adb::call)
        wait(PROCESS_SETTLE_MILLIS)

        val mainProcesses = adb.pidOf(target.packageName)
        val workerProcesses = if (target.workerProbeUri == null) {
            emptySet()
        } else {
            adb.pidOf("${target.packageName}:worker")
        }
        val processIds = mainProcesses + workerProcesses
        val expectedCount = if (target.workerProbeUri == null) 1 else 2
        val logs = adb.logcat()
        return SmokeResult(
            passed = processIds.size == expectedCount && !hasAnr(logs),
            processIds = processIds,
            anrDetected = hasAnr(logs),
            diagnostics = logs,
        )
    }

    fun verifyTerminates(target: SmokeTarget, debug: Boolean): SmokeResult {
        adb.install(target.apk)
        adb.clearLogcat()
        adb.forceStop(target.packageName)
        adb.start(target.packageName, target.activity, debug)
        wait(START_SETTLE_MILLIS)

        if (debug) {
            val debugProcesses = adb.pidOf(target.packageName)
            check(debugProcesses.size <= 1) {
                "Expected at most one debug process, found $debugProcesses"
            }
            debugProcesses.singleOrNull()?.let(adb::resumeDebugger)
            wait(DEBUG_RESUME_SETTLE_MILLIS)
        }

        val processSnapshots = List(PROCESS_POLLS) {
            adb.pidOf(target.packageName).also { wait(PROCESS_SETTLE_MILLIS) }
        }
        val logs = adb.logcat()
        val anrDetected = hasAnr(logs)
        val restartLoopDetected = START_PROCESS.findAll(logs)
            .count { match -> target.packageName in match.value } > 1
        return SmokeResult(
            passed = processSnapshots.all(Set<String>::isEmpty) &&
                !anrDetected &&
                !restartLoopDetected,
            processIds = processSnapshots.flatten().toSet(),
            anrDetected = anrDetected,
            restartLoopDetected = restartLoopDetected,
            diagnostics = logs,
        )
    }

    private fun hasAnr(logs: String): Boolean =
        "ANR in" in logs || "Input dispatching timed out" in logs

    private companion object {
        const val START_SETTLE_MILLIS = 1_500L
        const val DEBUG_RESUME_SETTLE_MILLIS = 1_500L
        const val PROCESS_SETTLE_MILLIS = 500L
        const val PROCESS_POLLS = 3
        val START_PROCESS = Regex("Start proc[^\\r\\n]*")
    }
}

fun main(arguments: Array<String>) {
    val options = parseOptions(arguments)
    val adb = AdbClient(
        executable = options.getValue("adb"),
        serial = options.getValue("serial"),
    )
    val mode = options.getValue("mode")
    when (mode) {
        "profile" -> println(adb.readProfile())
        "survives" -> report(
            mode,
            RuntimeSmokeRunner(adb).verifySurvives(options.smokeTarget()),
        )
        "terminates" -> report(
            mode,
            RuntimeSmokeRunner(adb).verifyTerminates(
                options.smokeTarget(),
                debug = options["debug"].toBoolean(),
            ),
        )
        else -> error("Unsupported mode: $mode")
    }
}

private fun report(mode: String, result: SmokeResult) {
    val summary = "SmokeResult(mode=$mode, passed=${result.passed}, " +
        "processIds=${result.processIds}, anrDetected=${result.anrDetected}, " +
        "restartLoopDetected=${result.restartLoopDetected})"
    check(result.passed) { "$summary\n${result.diagnostics}" }
    println(summary)
}

private fun parseOptions(arguments: Array<String>): Map<String, String> {
    require(arguments.size % 2 == 0) { "Arguments must use --name value pairs" }
    val options = arguments.toList().chunked(2).associate { (name, value) ->
        require(name.startsWith("--")) { "Unexpected argument: $name" }
        name.removePrefix("--") to value
    }.toMutableMap()
    options.putIfAbsent("adb", "adb")
    options.putIfAbsent("debug", "false")
    require(options["serial"].isNullOrBlank().not()) { "--serial is required" }
    require(options["mode"].isNullOrBlank().not()) { "--mode is required" }
    return options
}

private fun Map<String, String>.smokeTarget(): SmokeTarget = SmokeTarget(
    apk = File(getValue("apk")).absolutePath,
    packageName = getValue("package"),
    activity = getValue("activity"),
    mainProbeUri = get("main-probe-uri"),
    workerProbeUri = get("worker-probe-uri"),
)
