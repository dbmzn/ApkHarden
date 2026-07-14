package com.apkharden.runtime

import android.app.Application
import android.os.Build
import java.io.File
import org.json.JSONObject

enum class HardenFailure {
    DEBUGGABLE,
    DEBUGGER_CONNECTED,
    TRACER_DETECTED,
    CERTIFICATE_MISMATCH,
    STRING_TABLE_INVALID,
}

internal class FailureRecorder {
    fun write(application: Application, failure: HardenFailure) {
        runCatching {
            val output = File(application.filesDir, RELATIVE_PATH)
            check(output.parentFile?.mkdirs() != false || output.parentFile?.isDirectory == true)
            output.writeText(
                JSONObject()
                    .put("code", failure.name)
                    .put("runtimeVersion", RuntimeBuildInfo.VERSION)
                    .put("process", processName(application))
                    .toString(),
            )
        }
    }

    private fun processName(application: Application): String {
        val detected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().trimEnd('\u0000', '\r', '\n')
            }.getOrNull()
        }
        return detected?.takeIf { it.isNotBlank() } ?: application.packageName
    }

    companion object {
        const val RELATIVE_PATH = "apkharden/failure.json"
    }
}
