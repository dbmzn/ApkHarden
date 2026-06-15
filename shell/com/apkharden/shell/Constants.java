package com.apkharden.shell;

public final class Constants {
    public static final String META_APP_NAME = "com.apkharden.APP_NAME";
    public static final String META_SIG_HASH = "com.apkharden.SIG_HASH";
    public static final String META_DEX_COUNT = "com.apkharden.DEX_COUNT";
    public static final String ENC_DIR = "d"; // assets/d/<i>

    // Same 16 bytes as packager Constants.AES_KEY, XOR'd with 0x5A.
    private static final byte[] OBF = {
        0x6B, 0x31, (byte) 0xC5, 0x7E, (byte) 0x92, 0x50, 0x0F, (byte) 0xB9,
        0x2D, 0x48, (byte) 0xF1, 0x17, (byte) 0xCA, 0x34, (byte) 0xD2, 0x45
    };

    public static byte[] aesKey() {
        byte[] k = new byte[OBF.length];
        for (int i = 0; i < OBF.length; i++) k[i] = (byte) (OBF[i] ^ 0x5A);
        return k;
    }

    private Constants() {}
}
