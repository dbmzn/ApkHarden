package com.apkharden.shell;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

final class DexDecryptor {
    static byte[] decrypt(byte[] blob) throws Exception {
        byte[] iv = Arrays.copyOfRange(blob, 0, 16);
        byte[] body = Arrays.copyOfRange(blob, 16, blob.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Constants.aesKey(), "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(body);
    }

    private DexDecryptor() {}
}
