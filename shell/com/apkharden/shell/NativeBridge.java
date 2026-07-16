package com.apkharden.shell;

final class NativeBridge {
    static {
        System.loadLibrary("apkharden");
    }

    static native byte[] decrypt(byte[] payload);

    private NativeBridge() {}
}
