package com.apkharden.shell;

import dalvik.system.InMemoryDexClassLoader;

import java.nio.ByteBuffer;
import java.util.zip.ZipFile;

/** API 29+ implementation isolated so API 23-25 never resolve InMemoryDexClassLoader. */
final class InMemoryPayloadLoader {
    static PayloadLoader.LoadedPayload load(
            String apkPath,
        String nativeLibraryDir,
        ClassLoader parent) throws Exception {
        ShellMetadata metadata = ShellMetadata.read(apkPath);
        ByteBuffer[] buffers = new ByteBuffer[metadata.dexCount];
        ZipFile apk = new ZipFile(apkPath);
        try {
            for (int i = 0; i < metadata.dexCount; i++) {
                byte[] dex = PayloadLoader.decryptDex(apk, metadata, i);
                try {
                    buffers[i] = ByteBuffer.allocateDirect(dex.length);
                    buffers[i].put(dex);
                    buffers[i].flip();
                } finally {
                    PayloadLoader.zero(dex);
                }
            }
        } finally {
            apk.close();
        }
        ClassLoader loader = new InMemoryDexClassLoader(
                buffers,
                PayloadLoader.nativeLibrarySearchPath(apkPath, nativeLibraryDir),
                parent);
        return new PayloadLoader.LoadedPayload(loader, metadata);
    }

    private InMemoryPayloadLoader() {}
}
