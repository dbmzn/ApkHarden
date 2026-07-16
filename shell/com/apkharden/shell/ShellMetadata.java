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

    private ShellMetadata(int dexCount, String payloadId, String app, String factory) {
        this.dexCount = dexCount;
        this.payloadId = payloadId;
        this.originalApplication = app;
        this.originalComponentFactory = factory;
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
            return new ShellMetadata(
                    count,
                    id,
                    properties.getProperty("originalApplication", "").trim(),
                    properties.getProperty("originalComponentFactory", "").trim());
        } finally {
            apk.close();
        }
    }

    String payloadEntry(int index) {
        return DIRECTORY + "/" + index + ".bin";
    }
}
