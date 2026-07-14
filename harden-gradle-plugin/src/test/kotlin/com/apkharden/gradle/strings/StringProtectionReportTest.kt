package com.apkharden.gradle.strings

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class StringProtectionReportTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `analysis uses stable reasons and report contains no plaintext`() {
        val node = ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = "com/example/app/Contracts"
            superName = "java/lang/Object"
            fields.add(stringField(Opcodes.ACC_PRIVATE, "PRIVATE_SECRET", "private-secret"))
            fields.add(stringField(Opcodes.ACC_PUBLIC, "PUBLIC_CONTRACT", "public-contract"))
            methods.add(stringMethod("business", "business-secret"))
            methods.add(stringMethod("explicit", "explicit-contract"))
            methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "reflect", "()V", null, null).apply {
                instructions.add(LdcInsnNode("com.example.Target"))
                instructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Class",
                        "forName",
                        "(Ljava/lang/String;)Ljava/lang/Class;",
                        false,
                    ),
                )
                instructions.add(InsnNode(Opcodes.POP))
                instructions.add(LdcInsnNode("reflection-companion"))
                instructions.add(InsnNode(Opcodes.POP))
                instructions.add(InsnNode(Opcodes.RETURN))
            })
        }

        val statistics = analyzeStringProtection(
            classNodes = listOf(node),
            selection = StringProtectionSelection(emptySet(), "com.example.app"),
            applicationClassName = null,
            excludedStrings = setOf("explicit-contract"),
        )

        assertEquals(1, statistics.protectedMethodBodySites)
        assertEquals(1, statistics.protectedPrivateConstantSites)
        assertEquals(1, statistics.excludedByReason[StringExclusionReasonCode.PUBLIC_CONSTANT_INLINING_RISK])
        assertEquals(1, statistics.excludedByReason[StringExclusionReasonCode.EXPLICIT_STRING_EXCLUSION])
        assertEquals(2, statistics.excludedByReason[StringExclusionReasonCode.FRAMEWORK_REFLECTION_CONTRACT])

        val output = File(temp, "string-report.json")
        StringProtectionReportWriter.write(
            StringProtectionReport("release", 2, statistics),
            output,
        )
        val text = output.readText()
        listOf(
            "private-secret",
            "public-contract",
            "business-secret",
            "explicit-contract",
            "com.example.Target",
            "reflection-companion",
        ).forEach { plaintext -> assertFalse(text.contains(plaintext)) }
        assertTrue(text.contains("PUBLIC_CONSTANT_INLINING_RISK"))
        assertTrue(text.contains("FRAMEWORK_REFLECTION_CONTRACT"))
    }

    private fun stringField(visibility: Int, name: String, value: String) = FieldNode(
        visibility or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
        name,
        Type.getDescriptor(String::class.java),
        null,
        value,
    )

    private fun stringMethod(name: String, value: String) = MethodNode(
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        name,
        "()Ljava/lang/String;",
        null,
        null,
    ).apply {
        instructions.add(LdcInsnNode(value))
        instructions.add(InsnNode(Opcodes.ARETURN))
    }
}
