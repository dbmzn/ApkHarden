package com.apkharden.shell;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Inflater;

final class DexDecryptor {
    // Blob layout: [16-byte IV] + AES/CBC/PKCS5( deflate(dex) ).
    // The packager deflates the dex before encrypting (AES ciphertext is incompressible,
    // so this keeps the encrypted asset small); we decrypt, then inflate back to the dex.
    static byte[] decrypt(byte[] blob) throws Exception {
        byte[] iv = Arrays.copyOfRange(blob, 0, 16);
        byte[] body = Arrays.copyOfRange(blob, 16, blob.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Constants.aesKey(), "AES"),
                new IvParameterSpec(iv));
        return inflate(cipher.doFinal(body));
    }

    private static byte[] inflate(byte[] compressed) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
        byte[] buf = new byte[64 * 1024];
        while (!inflater.finished()) {
            int n = inflater.inflate(buf);
            if (n == 0 && inflater.needsInput()) break; // malformed / truncated input
            out.write(buf, 0, n);
        }
        inflater.end();
        return out.toByteArray();
    }

    private DexDecryptor() {}
}
