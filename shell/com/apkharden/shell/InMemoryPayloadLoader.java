package com.apkharden.shell;

import dalvik.system.InMemoryDexClassLoader;

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
        ClassLoader loader = new InMemoryDexClassLoader(buffers, nativeLibraryDir, parent);
        return new PayloadLoader.LoadedPayload(loader, metadata);
    }

    private InMemoryPayloadLoader() {}
}
