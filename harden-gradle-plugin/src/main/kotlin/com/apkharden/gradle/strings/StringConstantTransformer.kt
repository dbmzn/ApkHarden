package com.apkharden.gradle.strings

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class StringConstantTransformer(
    private val stringIds: Map<String, Int>,
    private val excludedStrings: Set<String> = emptySet(),
) {
    fun collect(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): List<String> = eligibleFields(classNode, applicationClassName)
        .mapNotNull { field -> (field.value as? String)?.takeIf { it !in excludedStrings } }

    fun transform(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): Int {
        val assignments = eligibleFields(classNode, applicationClassName).mapNotNull { field ->
            val plaintext = field.value as? String ?: return@mapNotNull null
            if (plaintext in excludedStrings) return@mapNotNull null
            val entryId = stringIds[plaintext] ?: return@mapNotNull null
            field.value = null
            assignment(classNode.name, field, entryId)
        }
        if (assignments.isEmpty()) return 0

        val classInitializer = classNode.methods.firstOrNull { method ->
            method.name == "<clinit>" && method.desc == "()V"
        } ?: MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).also { method ->
            method.instructions.add(InsnNode(Opcodes.RETURN))
            classNode.methods.add(method)
        }
        val initialization = InsnList()
        assignments.forEach(initialization::add)
        classInitializer.instructions.insert(initialization)
        classInitializer.maxStack = maxOf(classInitializer.maxStack, 1)
        return assignments.size
    }

    private fun eligibleFields(
        classNode: ClassNode,
        applicationClassName: String?,
    ): List<FieldNode> {
        if (classNode.name == applicationClassName?.replace('.', '/')) return emptyList()
        return classNode.fields.filter { field ->
            field.desc == Type.getDescriptor(String::class.java) &&
                field.value is String &&
                field.access and REQUIRED_ACCESS == REQUIRED_ACCESS &&
                field.access and Opcodes.ACC_PUBLIC == 0 &&
                field.access and Opcodes.ACC_PROTECTED == 0
        }
    }

    private fun assignment(owner: String, field: FieldNode, entryId: Int): InsnList =
        InsnList().apply {
            add(LdcInsnNode(entryId))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    HARDEN_STRINGS,
                    "decode",
                    "(I)Ljava/lang/String;",
                    false,
                ),
            )
            add(FieldInsnNode(Opcodes.PUTSTATIC, owner, field.name, field.desc))
        }

    private companion object {
        const val REQUIRED_ACCESS = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
        const val HARDEN_STRINGS = "com/apkharden/runtime/HardenStrings"
    }
}
