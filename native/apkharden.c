#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

// Patched with an APK-unique random AES-256 key by NativeLibraryPatcher.
// Keep this exact 32-byte sequence in sync with the JVM packager.
__attribute__((used)) static const uint8_t g_key_slot[32] = {
        0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
        0xA9, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF, 0xB0,
        0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8,
        0xB9, 0xBA, 0xBB, 0xBC, 0xBD, 0xBE, 0xBF, 0xC0
};

static void wipe(uint8_t *data, size_t size) {
    if (data == NULL) return;
    volatile uint8_t *cursor = data;
    while (size-- > 0) *cursor++ = 0;
}

static void throw_security(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/lang/SecurityException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static uint32_t read_be32(const uint8_t *input) {
    return ((uint32_t) input[0] << 24U) | ((uint32_t) input[1] << 16U)
           | ((uint32_t) input[2] << 8U) | (uint32_t) input[3];
}

static jbyteArray aes_gcm_decrypt(
        JNIEnv *env,
        const uint8_t *header,
        const uint8_t *body,
        size_t body_size) {
    jbyteArray header_array = NULL;
    jbyteArray body_array = NULL;
    jbyteArray key_array = NULL;
    jbyteArray nonce_array = NULL;
    jbyteArray decrypted = NULL;
    jstring transformation = NULL;
    jstring aes_name = NULL;

    jclass cipher_class = (*env)->FindClass(env, "javax/crypto/Cipher");
    jclass key_class = (*env)->FindClass(env, "javax/crypto/spec/SecretKeySpec");
    jclass gcm_class = (*env)->FindClass(env, "javax/crypto/spec/GCMParameterSpec");
    if (cipher_class == NULL || key_class == NULL || gcm_class == NULL) goto cleanup;

    jmethodID get_instance = (*env)->GetStaticMethodID(
            env, cipher_class, "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;");
    jmethodID key_ctor = (*env)->GetMethodID(
            env, key_class, "<init>", "([BLjava/lang/String;)V");
    jmethodID gcm_ctor = (*env)->GetMethodID(env, gcm_class, "<init>", "(I[B)V");
    jmethodID init = (*env)->GetMethodID(
            env, cipher_class, "init",
            "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V");
    jmethodID update_aad = (*env)->GetMethodID(env, cipher_class, "updateAAD", "([B)V");
    jmethodID do_final = (*env)->GetMethodID(env, cipher_class, "doFinal", "([B)[B");
    if (get_instance == NULL || key_ctor == NULL || gcm_ctor == NULL || init == NULL
            || update_aad == NULL || do_final == NULL) goto cleanup;

    transformation = (*env)->NewStringUTF(env, "AES/GCM/NoPadding");
    aes_name = (*env)->NewStringUTF(env, "AES");
    key_array = (*env)->NewByteArray(env, 32);
    nonce_array = (*env)->NewByteArray(env, 12);
    header_array = (*env)->NewByteArray(env, 24);
    body_array = (*env)->NewByteArray(env, (jsize) body_size);
    if (transformation == NULL || aes_name == NULL || key_array == NULL || nonce_array == NULL
            || header_array == NULL || body_array == NULL) goto cleanup;

    (*env)->SetByteArrayRegion(env, key_array, 0, 32, (const jbyte *) g_key_slot);
    (*env)->SetByteArrayRegion(env, nonce_array, 0, 12, (const jbyte *) (header + 12));
    (*env)->SetByteArrayRegion(env, header_array, 0, 24, (const jbyte *) header);
    (*env)->SetByteArrayRegion(env, body_array, 0, (jsize) body_size, (const jbyte *) body);

    jobject cipher = (*env)->CallStaticObjectMethod(env, cipher_class, get_instance, transformation);
    jobject key = (*env)->NewObject(env, key_class, key_ctor, key_array, aes_name);
    jobject gcm = (*env)->NewObject(env, gcm_class, gcm_ctor, 128, nonce_array);
    if ((*env)->ExceptionCheck(env) || cipher == NULL || key == NULL || gcm == NULL) goto cleanup;
    (*env)->CallVoidMethod(env, cipher, init, 2, key, gcm); // Cipher.DECRYPT_MODE
    (*env)->CallVoidMethod(env, cipher, update_aad, header_array);
    decrypted = (jbyteArray) (*env)->CallObjectMethod(env, cipher, do_final, body_array);

cleanup:
    if (header_array != NULL) (*env)->DeleteLocalRef(env, header_array);
    if (body_array != NULL) (*env)->DeleteLocalRef(env, body_array);
    if (key_array != NULL) (*env)->DeleteLocalRef(env, key_array);
    if (nonce_array != NULL) (*env)->DeleteLocalRef(env, nonce_array);
    if (transformation != NULL) (*env)->DeleteLocalRef(env, transformation);
    if (aes_name != NULL) (*env)->DeleteLocalRef(env, aes_name);
    return decrypted;
}

JNIEXPORT __attribute__((visibility("default"))) jbyteArray JNICALL
Java_com_apkharden_shell_NativeBridge_decrypt(JNIEnv *env, jclass type, jbyteArray payload) {
    (void) type;
    if (payload == NULL) {
        throw_security(env, "Encrypted DEX payload is null");
        return NULL;
    }
    jsize payload_size = (*env)->GetArrayLength(env, payload);
    if (payload_size < 40) {
        throw_security(env, "Encrypted DEX payload is truncated");
        return NULL;
    }
    jbyte *payload_bytes = (*env)->GetByteArrayElements(env, payload, NULL);
    if (payload_bytes == NULL) return NULL;
    uint8_t *bytes = (uint8_t *) payload_bytes;
    if (bytes[0] != 'A' || bytes[1] != 'P' || bytes[2] != 'H' || bytes[3] != '1'
            || bytes[4] != 1) {
        (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
        throw_security(env, "Encrypted DEX header is invalid");
        return NULL;
    }
    uint32_t plain_size = read_be32(bytes + 8);
    if (plain_size < 40 || plain_size > 512U * 1024U * 1024U) {
        (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
        throw_security(env, "Encrypted DEX size is invalid");
        return NULL;
    }

    jbyteArray compressed_array = aes_gcm_decrypt(
            env, bytes, bytes + 24, (size_t) payload_size - 24U);
    (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
    if ((*env)->ExceptionCheck(env) || compressed_array == NULL) return NULL;

    jsize compressed_size = (*env)->GetArrayLength(env, compressed_array);
    jbyte *compressed_bytes = (*env)->GetByteArrayElements(env, compressed_array, NULL);
    uint8_t *plain = (uint8_t *) malloc(plain_size);
    if (compressed_bytes == NULL || plain == NULL) {
        if (plain != NULL) free(plain);
        if (compressed_bytes != NULL) {
            (*env)->ReleaseByteArrayElements(env, compressed_array, compressed_bytes, JNI_ABORT);
        }
        throw_security(env, "Cannot allocate decrypted DEX");
        return NULL;
    }
    uLongf output_size = plain_size;
    int inflate_result = uncompress(
            plain,
            &output_size,
            (const Bytef *) compressed_bytes,
            (uLong) compressed_size);
    wipe((uint8_t *) compressed_bytes, (size_t) compressed_size);
    (*env)->ReleaseByteArrayElements(env, compressed_array, compressed_bytes, 0);
    (*env)->DeleteLocalRef(env, compressed_array);
    if (inflate_result != Z_OK || output_size != plain_size
            || plain[0] != 'd' || plain[1] != 'e' || plain[2] != 'x' || plain[3] != '\n') {
        wipe(plain, plain_size);
        free(plain);
        throw_security(env, "Decrypted DEX integrity check failed");
        return NULL;
    }

    jbyteArray output = (*env)->NewByteArray(env, (jsize) plain_size);
    if (output != NULL) {
        (*env)->SetByteArrayRegion(env, output, 0, (jsize) plain_size, (const jbyte *) plain);
    }
    wipe(plain, plain_size);
    free(plain);
    return output;
}
