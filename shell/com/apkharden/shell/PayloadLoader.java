package com.apkharden.shell;

import dalvik.system.DexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PayloadLoader {
    static LoadedPayload loadLegacy(
            String apkPath,
            String dataDir,
            String nativeLibraryDir,
            ClassLoader parent) throws Exception {
        ShellMetadata metadata = ShellMetadata.read(apkPath);
        byte[][] dexes = decryptDexes(apkPath, metadata);
        // Older releases do not have the public early class-loader hook. Use versioned,
        // private, read-only DEX files and a normal DexClassLoader. This class intentionally has
        // no reference to InMemoryDexClassLoader so API 23-25 can verify it.
        File cache = new File(new File(dataDir, "code_cache"), "apkharden/" + metadata.payloadId);
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IllegalStateException("Cannot create protected DEX cache: " + cache);
        }
        ArrayList<String> paths = new ArrayList<String>();
        for (int i = 0; i < dexes.length; i++) {
            File dex = new File(cache, "c" + i + ".dex");
            writeReadOnlyDex(dex, dexes[i]);
            zero(dexes[i]);
            paths.add(dex.getAbsolutePath());
        }
        ClassLoader loader = new DexClassLoader(
                join(paths, File.pathSeparator),
                cache.getAbsolutePath(),
                nativeLibraryDir,
                parent);
        return new LoadedPayload(loader, metadata);
    }

    static byte[][] decryptDexes(String apkPath, ShellMetadata metadata) throws Exception {
        byte[][] dexes = new byte[metadata.dexCount][];
        ZipFile apk = new ZipFile(apkPath);
        try {
            for (int i = 0; i < metadata.dexCount; i++) {
                ZipEntry entry = apk.getEntry(metadata.payloadEntry(i));
                if (entry == null) throw new IllegalStateException("Encrypted DEX " + i + " is missing");
                byte[] encrypted = readAll(apk.getInputStream(entry));
                try {
                    dexes[i] = NativeBridge.decrypt(encrypted);
                } finally {
                    zero(encrypted);
                }
                if (!isDex(dexes[i])) throw new SecurityException("Decrypted payload " + i + " is not DEX");
            }
            return dexes;
        } finally {
            apk.close();
        }
    }

    private static void writeReadOnlyDex(File output, byte[] bytes) throws Exception {
        if (isValidDex(output, bytes.length)) return;
        File temp = new File(output.getAbsolutePath() + ".tmp");
        FileOutputStream stream = new FileOutputStream(temp);
        try {
            stream.write(bytes);
            stream.getFD().sync();
        } finally {
            stream.close();
        }
        if (!temp.setReadable(true, true) || !temp.setWritable(false, false) || !temp.setExecutable(false, false)) {
            temp.delete();
            throw new SecurityException("Cannot make decrypted DEX read-only");
        }
        if (output.exists() && !output.delete()) throw new IllegalStateException("Cannot replace DEX cache");
        if (!temp.renameTo(output)) throw new IllegalStateException("Cannot publish DEX cache");
    }

    private static boolean isValidDex(File file, int expectedLength) {
        if (!file.isFile() || file.length() != expectedLength || file.canWrite()) return false;
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] magic = new byte[4];
            return input.read(magic) == 4 && isDex(magic);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isDex(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'd' && bytes[1] == 'e'
                && bytes[2] == 'x' && bytes[3] == '\n';
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String join(ArrayList<String> values, String separator) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) output.append(separator);
            output.append(value);
        }
        return output.toString();
    }

    static void zero(byte[] bytes) {
        if (bytes == null) return;
        for (int i = 0; i < bytes.length; i++) bytes[i] = 0;
    }

    static final class LoadedPayload {
        final ClassLoader classLoader;
        final ShellMetadata metadata;

        LoadedPayload(ClassLoader classLoader, ShellMetadata metadata) {
            this.classLoader = classLoader;
            this.metadata = metadata;
        }
    }

    private PayloadLoader() {}
}
