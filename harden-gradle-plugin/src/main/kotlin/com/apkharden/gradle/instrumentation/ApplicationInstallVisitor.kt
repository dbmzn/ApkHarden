package com.apkharden.gradle.instrumentation

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

class ApplicationInstallVisitor(
    private val nextVisitor: ClassVisitor,
) : ClassNode(Opcodes.ASM9) {
    override fun visitEnd() {
        super.visitEnd()
        if (fields.none { it.name == MARKER_FIELD }) {
            val attachBaseContext = methods.firstOrNull {
                it.name == ATTACH_BASE_CONTEXT && it.desc == ATTACH_BASE_CONTEXT_DESCRIPTOR
            }
            if (attachBaseContext == null) {
                methods.add(createAttachBaseContext())
            } else {
                require(attachBaseContext.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_STATIC) == 0) {
                    "$name.$ATTACH_BASE_CONTEXT must be a concrete instance method"
                }
                injectBeforeNormalReturns(attachBaseContext)
            }
            fields.add(
                FieldNode(
                    Opcodes.ASM9,
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
                    MARKER_FIELD,
                    "Z",
                    null,
                    true,
                ),
            )
        }
        accept(nextVisitor)
    }

    private fun injectBeforeNormalReturns(method: MethodNode) {
        val returns = method.instructions.asSequence()
            .filter { it.opcode == Opcodes.RETURN }
            .toList()
        returns.forEach { returnInstruction ->
            method.instructions.insertBefore(returnInstruction, runtimeInstallInstructions())
        }
    }

    private fun createAttachBaseContext(): MethodNode = MethodNode(
        Opcodes.ASM9,
        Opcodes.ACC_PROTECTED,
        ATTACH_BASE_CONTEXT,
        ATTACH_BASE_CONTEXT_DESCRIPTOR,
        null,
        null,
    ).apply {
        instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
        instructions.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                superName,
                ATTACH_BASE_CONTEXT,
                ATTACH_BASE_CONTEXT_DESCRIPTOR,
                false,
            ),
        )
        instructions.add(runtimeInstallInstructions())
        instructions.add(InsnNode(Opcodes.RETURN))
        maxStack = 2
        maxLocals = 2
    }

    private fun runtimeInstallInstructions(): InsnList = InsnList().apply {
        add(VarInsnNode(Opcodes.ALOAD, 0))
        add(
            FieldInsnNode(
                Opcodes.GETSTATIC,
                GENERATED_CONFIG,
                "INSTANCE",
                HARDEN_CONFIG_DESCRIPTOR,
            ),
        )
        add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HARDEN_RUNTIME,
                "install",
                HARDEN_INSTALL_DESCRIPTOR,
                false,
            ),
        )
    }

    private fun org.objectweb.asm.tree.InsnList.asSequence() =
        generateSequence(first) { it.next }

    companion object {
        const val MARKER_FIELD = "\$apkharden\$applicationInstall"

        private const val ATTACH_BASE_CONTEXT = "attachBaseContext"
        private const val ATTACH_BASE_CONTEXT_DESCRIPTOR = "(Landroid/content/Context;)V"
        private const val GENERATED_CONFIG = "com/apkharden/generated/HardenVariantConfig"
        private const val HARDEN_RUNTIME = "com/apkharden/runtime/HardenRuntime"
        private const val HARDEN_CONFIG_DESCRIPTOR = "Lcom/apkharden/runtime/HardenConfig;"
        private const val HARDEN_INSTALL_DESCRIPTOR =
            "(Landroid/app/Application;Lcom/apkharden/runtime/HardenConfig;)V"
    }
}
