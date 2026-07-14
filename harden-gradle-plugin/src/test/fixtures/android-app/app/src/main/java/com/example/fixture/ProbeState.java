package com.example.fixture;

import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

final class ProbeState {
    private static final AtomicInteger APPLICATION_CREATES = new AtomicInteger();
    private static final AtomicInteger PROVIDER_CREATES = new AtomicInteger();

    static void recordApplicationCreate() {
        APPLICATION_CREATES.incrementAndGet();
    }

    static void recordProviderCreate() {
        PROVIDER_CREATES.incrementAndGet();
    }

    static Bundle snapshot() {
        Bundle result = new Bundle();
        result.putInt("pid", Process.myPid());
        result.putString("process", processName());
        result.putInt("applicationCreates", APPLICATION_CREATES.get());
        result.putInt("providerCreates", PROVIDER_CREATES.get());
        return result;
    }

    private static String processName() {
        try {
            return new String(java.nio.file.Files.readAllBytes(new File("/proc/self/cmdline").toPath()))
                .replace("\u0000", "")
                .trim();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private ProbeState() {}
}
