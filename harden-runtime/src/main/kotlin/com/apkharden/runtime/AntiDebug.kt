package com.apkharden.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import java.io.File

object AntiDebug {
    fun detect(context: Context): HardenFailure? = when {
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ->
            HardenFailure.DEBUGGABLE
        Debug.isDebuggerConnected() || Debug.waitingForDebugger() ->
            HardenFailure.DEBUGGER_CONNECTED
        readTracerPid() -> HardenFailure.TRACER_DETECTED
        else -> null
    }

    internal fun hasTracer(lines: Sequence<String>): Boolean = lines
        .firstOrNull { it.startsWith("TracerPid:") }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()
        ?.let { it > 0 }
        ?: false

    private fun readTracerPid(): Boolean = runCatching {
        File("/proc/self/status").useLines { lines -> hasTracer(lines) }
    }.getOrDefault(false)
}
