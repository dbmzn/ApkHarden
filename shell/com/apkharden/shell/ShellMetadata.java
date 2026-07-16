package com.apkharden.shell;

import java.io.InputStream;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ShellMetadata {
    static final String DIRECTORY = "assets/.apkharden";
    static final String METADATA_ENTRY = DIRECTORY + "/metadata.properties";

    final int dexCount;
    final String payloadId;
    final String originalApplication;
    final String originalComponentFactory;
    final int[] dexSizes;
    final String[] dexSha256;

    private ShellMetadata(
            int dexCount,
            String payloadId,
            String app,
            String factory,
            int[] dexSizes,
            String[] dexSha256) {
        this.dexCount = dexCount;
        this.payloadId = payloadId;
        this.originalApplication = app;
        this.originalComponentFactory = factory;
        this.dexSizes = dexSizes;
        this.dexSha256 = dexSha256;
    }

    static ShellMetadata read(String apkPath) throws Exception {
        ZipFile apk = new ZipFile(apkPath);
        try {
            ZipEntry entry = apk.getEntry(METADATA_ENTRY);
            if (entry == null) throw new IllegalStateException("ApkHarden metadata is missing");
            Properties properties = new Properties();
            InputStream input = apk.getInputStream(entry);
            try {
                properties.load(input);
            } finally {
                input.close();
            }
            int count = Integer.parseInt(properties.getProperty("dexCount", "0"));
            String id = properties.getProperty("payloadId", "").trim();
            if (count <= 0 || id.length() < 8) {
                throw new IllegalStateException("ApkHarden metadata is invalid");
            }
            int[] sizes = new int[count];
            String[] hashes = new String[count];
            for (int i = 0; i < count; i++) {
                sizes[i] = Integer.parseInt(properties.getProperty("dex." + i + ".size", "0"));
                hashes[i] = properties.getProperty("dex." + i + ".sha256", "").trim().toLowerCase();
                if (sizes[i] <= 0 || !hashes[i].matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("ApkHarden DEX metadata " + i + " is invalid");
                }
            }
            return new ShellMetadata(
                    count,
                    id,
                    properties.getProperty("originalApplication", "").trim(),
                    properties.getProperty("originalComponentFactory", "").trim(),
                    sizes,
                    hashes);
        } finally {
            apk.close();
        }
    }

    String payloadEntry(int index) {
        return DIRECTORY + "/" + index + ".bin";
    }
}
