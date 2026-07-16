package com.apkharden.shell;

import dalvik.system.DexClassLoader;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
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
        // Older releases do not have the public early class-loader hook. Use versioned,
        // private, read-only DEX files and a normal DexClassLoader. This class intentionally has
        // no reference to InMemoryDexClassLoader so API 23-25 can verify it.
        File cache = new File(new File(dataDir, "code_cache"), "apkharden/" + metadata.payloadId);
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IllegalStateException("Cannot create protected DEX cache: " + cache);
        }
        ArrayList<String> paths = new ArrayList<String>();
        ZipFile apk = new ZipFile(apkPath);
        try {
            for (int i = 0; i < metadata.dexCount; i++) {
                File output = new File(cache, "c" + i + ".dex");
                if (!isValidDex(output, metadata.dexSizes[i], metadata.dexSha256[i])) {
                    byte[] dex = decryptDex(apk, metadata, i);
                    try {
                        if (!sha256(dex).equals(metadata.dexSha256[i])) {
                            throw new SecurityException("Decrypted payload " + i + " digest mismatch");
                        }
                        writeReadOnlyDex(output, dex);
                    } finally {
                        zero(dex);
                    }
                }
                paths.add(output.getAbsolutePath());
            }
        } finally {
            apk.close();
        }
        ClassLoader loader = new DexClassLoader(
                join(paths, File.pathSeparator),
                cache.getAbsolutePath(),
                nativeLibrarySearchPath(apkPath, nativeLibraryDir),
                parent);
        return new LoadedPayload(loader, metadata);
    }

    static byte[] decryptDex(ZipFile apk, ShellMetadata metadata, int index) throws Exception {
        ZipEntry entry = apk.getEntry(metadata.payloadEntry(index));
        if (entry == null) throw new IllegalStateException("Encrypted DEX " + index + " is missing");
        byte[] encrypted = readAll(apk.getInputStream(entry));
        try {
            byte[] dex = NativeBridge.decrypt(encrypted);
            if (!isDex(dex)) throw new SecurityException("Decrypted payload " + index + " is not DEX");
            return dex;
        } finally {
            zero(encrypted);
        }
    }

    private static void writeReadOnlyDex(File output, byte[] bytes) throws Exception {
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

    private static boolean isValidDex(File file, int expectedLength, String expectedSha256) {
        if (!file.isFile() || file.length() != expectedLength || file.canWrite()) return false;
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] magic = new byte[4];
            if (input.read(magic) != 4 || !isDex(magic)) return false;
            input.close();
            input = null;
            return sha256(file).equals(expectedSha256);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) {}
        }
    }

    static String nativeLibrarySearchPath(String apkPath, String nativeLibraryDir) {
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

    private static String sha256(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } finally {
            input.close();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format("%02x", value & 0xff));
        return output.toString();
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
