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
    ): List<String> = classNode.fields.mapNotNull { field ->
        val value = field.value as? String ?: return@mapNotNull null
        value.takeIf {
            stringConstantExclusionReason(classNode, field, applicationClassName, excludedStrings) == null
        }
    }

    fun transform(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): Int {
        val assignments = classNode.fields.mapNotNull { field ->
            val plaintext = field.value as? String ?: return@mapNotNull null
            if (
                stringConstantExclusionReason(
                    classNode,
                    field,
                    applicationClassName,
                    excludedStrings,
                ) != null
            ) return@mapNotNull null
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
        const val HARDEN_STRINGS = "com/apkharden/runtime/HardenStrings"
    }
}

internal fun stringConstantExclusionReason(
    classNode: ClassNode,
    field: FieldNode,
    applicationClassName: String?,
    excludedStrings: Set<String>,
): StringExclusionReasonCode? {
    val value = field.value as? String ?: return StringExclusionReasonCode.UNSAFE_CONSTANT_FIELD
    literalStringExclusionReason(value, excludedStrings)?.let { return it }
    if (classNode.name == applicationClassName?.replace('.', '/')) {
        return StringExclusionReasonCode.APPLICATION_CONSTANT_FIELD
    }
    if (field.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0) {
        return StringExclusionReasonCode.PUBLIC_CONSTANT_INLINING_RISK
    }
    val requiredAccess = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
    if (
        field.desc != Type.getDescriptor(String::class.java) ||
        field.access and requiredAccess != requiredAccess
    ) return StringExclusionReasonCode.UNSAFE_CONSTANT_FIELD
    return null
}
