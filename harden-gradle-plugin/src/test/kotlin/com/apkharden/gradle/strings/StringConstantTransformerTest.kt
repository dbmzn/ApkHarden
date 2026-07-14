package com.apkharden.gradle.strings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class StringConstantTransformerTest {
    @Test
    fun `private static final string becomes a decoded clinit assignment`() {
        val node = fixtureClass("com/example/Constants")
        node.fields.add(stringField(Opcodes.ACC_PRIVATE, "SECRET", "secret"))
        val transformer = StringConstantTransformer(mapOf("secret" to 4))

        assertEquals(listOf("secret"), transformer.collect(node))
        assertEquals(1, transformer.transform(node))

        assertNull(node.fields.single().value)
        val instructions = node.methods.single { it.name == "<clinit>" }.instructions.toArray()
        assertEquals(4, (instructions[0] as LdcInsnNode).cst)
        assertEquals("decode", (instructions[1] as MethodInsnNode).name)
        assertEquals("SECRET", (instructions[2] as FieldInsnNode).name)
    }

    @Test
    fun `existing clinit remains after inserted field initialization`() {
        val node = fixtureClass("com/example/Constants")
        node.fields.add(stringField(Opcodes.ACC_PRIVATE, "SECRET", "secret"))
        node.methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            instructions.add(LdcInsnNode("existing"))
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        })

        StringConstantTransformer(mapOf("secret" to 0)).transform(node)

        val constants = node.methods.single { it.name == "<clinit>" }.instructions.toArray()
            .filterIsInstance<LdcInsnNode>()
            .map(LdcInsnNode::cst)
        assertEquals(listOf(0, "existing"), constants)
    }

    @Test
    fun `public constants application constants and excluded strings remain static`() {
        val publicNode = fixtureClass("com/example/Constants").apply {
            fields.add(stringField(Opcodes.ACC_PUBLIC, "PUBLIC_VALUE", "public"))
        }
        val applicationNode = fixtureClass("com/example/App").apply {
            fields.add(stringField(Opcodes.ACC_PRIVATE, "SECRET", "secret"))
        }
        val excludedNode = fixtureClass("com/example/Constants").apply {
            fields.add(stringField(Opcodes.ACC_PRIVATE, "NAME", "api-name"))
        }

        assertEquals(0, StringConstantTransformer(mapOf("public" to 0)).transform(publicNode))
        assertEquals(
            0,
            StringConstantTransformer(mapOf("secret" to 0)).transform(
                applicationNode,
                "com.example.App",
            ),
        )
        assertEquals(
            0,
            StringConstantTransformer(
                mapOf("api-name" to 0),
                excludedStrings = setOf("api-name"),
            ).transform(excludedNode),
        )
    }

    private fun fixtureClass(name: String): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        this.name = name
        superName = "java/lang/Object"
    }

    private fun stringField(visibility: Int, name: String, value: String): FieldNode = FieldNode(
        visibility or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
        name,
        Type.getDescriptor(String::class.java),
        null,
        value,
    )
}
