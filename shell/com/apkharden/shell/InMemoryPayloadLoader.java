package com.apkharden.shell;

import dalvik.system.InMemoryDexClassLoader;

import android.os.Build;

import java.io.File;
import java.nio.ByteBuffer;

/** API 29+ implementation isolated so API 23-25 never resolve InMemoryDexClassLoader. */
final class InMemoryPayloadLoader {
    static PayloadLoader.LoadedPayload load(
            String apkPath,
            String nativeLibraryDir,
            ClassLoader parent) throws Exception {
        ShellMetadata metadata = ShellMetadata.read(apkPath);
        byte[][] dexes = PayloadLoader.decryptDexes(apkPath, metadata);
        ByteBuffer[] buffers = new ByteBuffer[dexes.length];
        for (int i = 0; i < dexes.length; i++) {
            buffers[i] = ByteBuffer.allocateDirect(dexes[i].length);
            buffers[i].put(dexes[i]);
            buffers[i].flip();
            PayloadLoader.zero(dexes[i]);
        }
        ClassLoader loader = new InMemoryDexClassLoader(
                buffers,
                nativeLibrarySearchPath(apkPath, nativeLibraryDir),
                parent);
        return new PayloadLoader.LoadedPayload(loader, metadata);
    }

    /**
     * InMemoryDexClassLoader does not inherit the APK native-library elements from the app's
     * PathClassLoader. When extractNativeLibs=false, nativeLibraryDir exists but libraries such as
     * MMKV remain inside the APK, so passing only that directory makes System.loadLibrary fail.
     */
    private static String nativeLibrarySearchPath(String apkPath, String nativeLibraryDir) {
        StringBuilder path = new StringBuilder();
        appendPath(path, nativeLibraryDir);
        for (String abi : Build.SUPPORTED_ABIS) {
            appendPath(path, apkPath + "!/lib/" + abi);
        }
        return path.toString();
    }

    private static void appendPath(StringBuilder path, String value) {
        if (value == null || value.isEmpty()) return;
        if (path.length() > 0) path.append(File.pathSeparator);
        path.append(value);
    }

    private InMemoryPayloadLoader() {}
}
