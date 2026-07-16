package com.apkharden.shell;

/** API-neutral state holder so API 23-27 never resolve android.app.AppComponentFactory. */
final class ShellRuntime {
    private static volatile PayloadLoader.LoadedPayload loadedPayload;

    static PayloadLoader.LoadedPayload currentPayload() {
        return loadedPayload;
    }

    static void install(PayloadLoader.LoadedPayload loaded) {
        loadedPayload = loaded;
        Thread.currentThread().setContextClassLoader(loaded.classLoader);
    }

    private ShellRuntime() {}
}
