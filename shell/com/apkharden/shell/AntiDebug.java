package com.apkharden.shell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Debug;
import java.io.BufferedReader;
import java.io.FileReader;

final class AntiDebug {
    /** @return true if a debugger / tracer is detected. */
    static boolean isDetected(Context ctx) {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/status"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    String v = line.substring("TracerPid:".length()).trim();
                    if (!"0".equals(v)) return true;
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return false;
    }

    private AntiDebug() {}
}
