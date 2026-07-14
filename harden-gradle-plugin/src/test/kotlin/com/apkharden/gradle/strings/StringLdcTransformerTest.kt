package com.apkharden.gradle.strings

import java.io.PrintWriter
import java.io.StringWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.util.CheckClassAdapter

class StringLdcTransformerTest {
    @Test
    fun `eligible ldc strings become integer decode calls`() {
        val node = fixtureClass("com/example/Business")
        node.methods.add(stringMethod("protectedValue", "secret"))
        node.methods.add(stringMethod("unprotectedValue", "framework-name"))

        val transformed = StringLdcTransformer(mapOf("secret" to 7)).transform(node)

        assertEquals(1, transformed)
        val instructions = node.methods.single { it.name == "protectedValue" }.instructions.toArray()
        assertTrue(instructions.any { it is LdcInsnNode && it.cst == 7 })
        assertTrue(
            instructions.any {
                it is MethodInsnNode &&
                    it.owner == "com/apkharden/runtime/HardenStrings" &&
                    it.name == "decode" &&
                    it.desc == "(I)Ljava/lang/String;"
            },
        )
        assertFalse(instructions.any { it is LdcInsnNode && it.cst == "secret" })
        assertTrue(
            node.methods.single { it.name == "unprotectedValue" }
                .instructions.toArray()
                .any { it is LdcInsnNode && it.cst == "framework-name" },
        )
    }

    @Test
    fun `application constructors and static initializers remain unchanged`() {
        val node = fixtureClass("com/example/App")
        node.methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            instructions.add(LdcInsnNode("secret"))
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        val constructor = node.methods.single { it.name == "<init>" }
        constructor.instructions.insertBefore(
            constructor.instructions.last,
            LdcInsnNode("secret"),
        )
        constructor.instructions.insertBefore(
            constructor.instructions.last,
            InsnNode(Opcodes.POP),
        )

        val transformed = StringLdcTransformer(mapOf("secret" to 0)).transform(
            node,
            applicationClassName = "com.example.App",
        )

        assertEquals(0, transformed)
        assertEquals(2, node.methods.sumOf { method ->
            method.instructions.toArray().count { it is LdcInsnNode && it.cst == "secret" }
        })
    }

    @Test
    fun `field constants and annotation values are not transformed`() {
        val node = fixtureClass("com/example/Business")
        node.fields.add(FieldNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "VALUE",
            Type.getDescriptor(String::class.java),
            null,
            "secret",
        ))
        node.visitAnnotation("Lcom/example/Label;", true).visit("value", "secret")

        val transformed = StringLdcTransformer(mapOf("secret" to 0)).transform(node)

        assertEquals(0, transformed)
        assertEquals("secret", node.fields.single().value)
        assertEquals("secret", node.visibleAnnotations.single().values[1])
    }

    @Test
    fun `transformed class passes asm verification`() {
        val node = fixtureClass("com/example/Business")
        node.methods.add(stringMethod("value", "secret"))
        StringLdcTransformer(mapOf("secret" to 0)).transform(node)
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val errors = StringWriter()

        CheckClassAdapter.verify(
            ClassReader(writer.toByteArray()),
            false,
            PrintWriter(errors),
        )

        assertTrue(errors.toString().isBlank(), errors.toString())
    }

    @Test
    fun `class selection defaults to application id and excludes generated contracts`() {
        val selection = StringProtectionSelection(emptySet(), "com.example.app")

        assertTrue(selection.includes("com/example/app/Feature"))
        assertFalse(selection.includes("com/example/other/Feature"))
        assertFalse(selection.includes("com/example/app/R"))
        assertFalse(selection.includes("com/example/app/R\$string"))
        assertFalse(selection.includes("com/example/app/BuildConfig"))
        assertFalse(selection.includes("com/example/app/databinding/ScreenBinding"))
        assertFalse(selection.includes("com/example/app/FixtureAppComponentFactory"))
        assertFalse(selection.includes("com/apkharden/generated/HardenVariantConfig"))
        assertFalse(selection.includes("com/apkharden/runtime/HardenStrings"))
    }

    @Test
    fun `class selection honors explicit protected package prefixes`() {
        val selection = StringProtectionSelection(setOf("com.shared.feature"), "com.example.app")

        assertTrue(selection.includes("com/shared/feature/Screen"))
        assertFalse(selection.includes("com/example/app/Screen"))
    }

    private fun fixtureClass(name: String): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        this.name = name
        superName = "java/lang/Object"
        methods.add(MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false,
                ),
            )
            instructions.add(InsnNode(Opcodes.RETURN))
        })
    }

    private fun stringMethod(name: String, value: String): MethodNode = MethodNode(
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
