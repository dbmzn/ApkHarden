package com.apkharden.gradle.instrumentation

import java.io.PrintWriter
import java.io.StringWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.util.CheckClassAdapter

class ApplicationInstallVisitorTest {
    @Test
    fun `Java Application injects install before every normal return`() {
        val transformed = transform(applicationClass(hasAttachBaseContext = true, multipleReturns = true))
        val attach = transformed.classNode().attachBaseContext()
        val returns = attach.instructions.asSequence().filter { it.opcode == Opcodes.RETURN }.toList()

        assertEquals(2, returns.size)
        returns.forEach { instruction ->
            assertTrue(instruction.previousMeaningful().isRuntimeInstall())
        }
        assertValid(transformed)
    }

    @Test
    fun `missing attachBaseContext invokes real superclass before install`() {
        val transformed = transform(
            applicationClass(
                superName = "com/example/BaseApplication",
                hasAttachBaseContext = false,
            ),
        )
        val attach = transformed.classNode().attachBaseContext()
        val calls = attach.instructions.asSequence().filterIsInstance<MethodInsnNode>().toList()

        assertTrue(calls.any {
            it.opcode == Opcodes.INVOKESPECIAL &&
                it.owner == "com/example/BaseApplication" &&
                it.name == "attachBaseContext"
        })
        assertTrue(calls.any { it.isRuntimeInstall() })
        assertTrue(calls.indexOfFirst { it.name == "attachBaseContext" } < calls.indexOfFirst { it.isRuntimeInstall() })
    }

    @Test
    fun `generated attachBaseContext passes bytecode verification`() {
        val transformed = transform(applicationClass(hasAttachBaseContext = false))

        assertEquals(1, transformed.classNode().attachBaseContext().runtimeInstallCalls())
        assertValid(transformed)
    }

    @Test
    fun `Kotlin Application metadata is preserved`() {
        val transformed = transform(applicationClass(hasAttachBaseContext = true, kotlinMetadata = true))
        val node = transformed.classNode()

        assertNotNull(node.visibleAnnotations?.singleOrNull { it.desc == "Lkotlin/Metadata;" })
        assertEquals(1, node.attachBaseContext().runtimeInstallCalls())
        assertValid(transformed)
    }

    @Test
    fun `duplicate instrumentation is idempotent`() {
        val first = transform(applicationClass(hasAttachBaseContext = true))
        val second = transform(first)
        val node = second.classNode()

        assertEquals(1, node.attachBaseContext().runtimeInstallCalls())
        assertEquals(1, node.fields.count { it.name == ApplicationInstallVisitor.MARKER_FIELD })
        assertValid(second)
    }

    private fun transform(input: ByteArray): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        ClassReader(input).accept(ApplicationInstallVisitor(writer), 0)
        return writer.toByteArray()
    }

    private fun applicationClass(
        superName: String = "android/app/Application",
        hasAttachBaseContext: Boolean,
        multipleReturns: Boolean = false,
        kotlinMetadata: Boolean = false,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "com/example/TestApplication",
            null,
            superName,
            null,
        )
        if (kotlinMetadata) {
            writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        if (hasAttachBaseContext) {
            writer.visitMethod(
                Opcodes.ACC_PROTECTED,
                "attachBaseContext",
                "(Landroid/content/Context;)V",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    superName,
                    "attachBaseContext",
                    "(Landroid/content/Context;)V",
                    false,
                )
                if (multipleReturns) {
                    val nonNull = Label()
                    visitVarInsn(Opcodes.ALOAD, 1)
                    visitJumpInsn(Opcodes.IFNONNULL, nonNull)
                    visitInsn(Opcodes.RETURN)
                    visitLabel(nonNull)
                }
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun ByteArray.classNode(): ClassNode = ClassNode().also { node ->
        ClassReader(this).accept(node, 0)
    }

    private fun ClassNode.attachBaseContext(): MethodNode = methods.single {
        it.name == "attachBaseContext" && it.desc == "(Landroid/content/Context;)V"
    }

    private fun MethodNode.runtimeInstallCalls(): Int = instructions.asSequence()
        .filterIsInstance<MethodInsnNode>()
        .count { it.isRuntimeInstall() }

    private fun MethodInsnNode.isRuntimeInstall(): Boolean =
        opcode == Opcodes.INVOKESTATIC &&
            owner == "com/apkharden/runtime/HardenRuntime" &&
            name == "install" &&
            desc == "(Landroid/app/Application;Lcom/apkharden/runtime/HardenConfig;)V"

    private fun AbstractInsnNode?.previousMeaningful(): AbstractInsnNode? {
        var current = this?.previous
        while (current != null && current.opcode < 0) current = current.previous
        return current
    }

    private fun AbstractInsnNode?.isRuntimeInstall(): Boolean =
        this is MethodInsnNode && isRuntimeInstall()

    private fun assertValid(bytes: ByteArray) {
        val errors = StringWriter()
        CheckClassAdapter.verify(ClassReader(bytes), false, PrintWriter(errors))
        assertEquals("", errors.toString())
    }

    private fun org.objectweb.asm.tree.InsnList.asSequence(): Sequence<AbstractInsnNode> =
        generateSequence(first) { it.next }
}
