package com.apkharden.gradle.strings

import com.apkharden.crypto.StringCrypto
import java.security.SecureRandom
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

fun interface EntropySource {
    fun nextBytes(size: Int): ByteArray
}

class SecureEntropySource(
    private val random: SecureRandom = SecureRandom(),
) : EntropySource {
    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

class StringTableCompiler(
    private val entropy: EntropySource = SecureEntropySource(),
) {
    fun compile(
        strings: List<String>,
        certificateSha256: String,
        applicationId: String,
        buildId: String,
    ): CompiledStringTable {
        val ids = linkedMapOf<String, Int>()
        strings.forEach { value -> ids.getOrPut(value) { ids.size } }

        val fragmentA = entropy.bytes(KEY_FRAGMENT_SIZE, "fragmentA")
        val fragmentB = entropy.bytes(KEY_FRAGMENT_SIZE, "fragmentB")
        val key = StringCrypto.deriveKey(
            fragmentA,
            fragmentB,
            certificateSha256,
            applicationId,
            buildId,
        )
        return try {
            val entries = ids.entries.map { (plaintext, entryId) ->
                val iv = entropy.bytes(GCM_IV_SIZE, "iv[$entryId]")
                EncryptedStringEntry(
                    iv = iv,
                    ciphertext = StringCrypto.encrypt(
                        plaintext,
                        key,
                        iv,
                        StringCrypto.entryAad(entryId),
                    ),
                )
            }
            CompiledStringTable(
                ids = ids.toMap(),
                fragmentA = fragmentA,
                fragmentB = fragmentB,
                entries = entries,
            )
        } finally {
            key.fill(0)
        }
    }

    private fun EntropySource.bytes(size: Int, name: String): ByteArray =
        nextBytes(size).also { value ->
            require(value.size == size) { "$name entropy must contain $size bytes" }
        }

    private companion object {
        const val KEY_FRAGMENT_SIZE = 16
        const val GCM_IV_SIZE = 12
    }
}

data class EncryptedStringEntry(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

class CompiledStringTable(
    val ids: Map<String, Int>,
    fragmentA: ByteArray,
    fragmentB: ByteArray,
    entries: List<EncryptedStringEntry>,
) {
    val fragmentA = fragmentA.copyOf()
    val fragmentB = fragmentB.copyOf()
    val entries = entries.map { entry ->
        EncryptedStringEntry(entry.iv.copyOf(), entry.ciphertext.copyOf())
    }

    fun javaSource(): String {
        val ivValues = entries.joinToString(",\n                    ") { entry ->
            "new byte[] {${entry.iv.javaBytes()}}"
        }
        val ciphertextValues = entries.joinToString(",\n                    ") { entry ->
            "new byte[] {${entry.ciphertext.javaBytes()}}"
        }
        return """
            package com.apkharden.generated;

            import com.apkharden.runtime.HardenStringTable;

            public final class HardenStringTableConfig {
                public static final HardenStringTable INSTANCE = new HardenStringTable(
                    new byte[] {${fragmentA.javaBytes()}},
                    new byte[] {${fragmentB.javaBytes()}},
                    new byte[][] {
                        $ivValues
                    },
                    new byte[][] {
                        $ciphertextValues
                    }
                );

                private HardenStringTableConfig() {}
            }
        """.trimIndent() + "\n"
    }

    fun classBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            GENERATED_TABLE_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "INSTANCE",
            HARDEN_STRING_TABLE_DESCRIPTOR,
            null,
            null,
        ).visitEnd()
        writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, HARDEN_STRING_TABLE)
            visitInsn(Opcodes.DUP)
            pushByteArray(fragmentA)
            pushByteArray(fragmentB)
            pushByteArrays(entries.map(EncryptedStringEntry::iv))
            pushByteArrays(entries.map(EncryptedStringEntry::ciphertext))
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                HARDEN_STRING_TABLE,
                "<init>",
                "([B[B[[B[[B)V",
                false,
            )
            visitFieldInsn(
                Opcodes.PUTSTATIC,
                GENERATED_TABLE_CLASS,
                "INSTANCE",
                HARDEN_STRING_TABLE_DESCRIPTOR,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(16, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun MethodVisitor.pushByteArrays(values: List<ByteArray>) {
        pushInt(values.size)
        visitTypeInsn(Opcodes.ANEWARRAY, "[B")
        values.forEachIndexed { index, value ->
            visitInsn(Opcodes.DUP)
            pushInt(index)
            pushByteArray(value)
            visitInsn(Opcodes.AASTORE)
        }
    }

    private fun MethodVisitor.pushByteArray(value: ByteArray) {
        pushInt(value.size)
        visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        value.forEachIndexed { index, byte ->
            visitInsn(Opcodes.DUP)
            pushInt(index)
            pushInt(byte.toInt())
            visitInsn(Opcodes.BASTORE)
        }
    }

    private fun MethodVisitor.pushInt(value: Int) {
        when (value) {
            -1 -> visitInsn(Opcodes.ICONST_M1)
            in 0..5 -> visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> visitIntInsn(Opcodes.SIPUSH, value)
            else -> visitLdcInsn(value)
        }
    }

    private fun ByteArray.javaBytes(): String = joinToString(", ") { byte ->
        "(byte) 0x%02x".format(byte.toInt() and 0xff)
    }

    companion object {
        const val GENERATED_TABLE_ENTRY =
            "com/apkharden/generated/HardenStringTableConfig.class"
        private const val GENERATED_TABLE_CLASS =
            "com/apkharden/generated/HardenStringTableConfig"
        private const val HARDEN_STRING_TABLE = "com/apkharden/runtime/HardenStringTable"
        private const val HARDEN_STRING_TABLE_DESCRIPTOR =
            "Lcom/apkharden/runtime/HardenStringTable;"
    }
}
