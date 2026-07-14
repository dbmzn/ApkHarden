package com.apkharden.gradle.strings

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

class StringLdcTransformer(
    private val stringIds: Map<String, Int>,
) {
    fun transform(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): Int {
        val applicationInternalName = applicationClassName?.replace('.', '/')
        var transformed = 0
        classNode.methods.forEach { method ->
            if (
                classNode.name == applicationInternalName &&
                method.name in APPLICATION_INITIALIZERS
            ) {
                return@forEach
            }
            method.instructions.toArray().forEach { instruction ->
                val plaintext = (instruction as? LdcInsnNode)?.cst as? String
                    ?: return@forEach
                val entryId = stringIds[plaintext] ?: return@forEach
                instruction.cst = entryId
                method.instructions.insert(
                    instruction,
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HARDEN_STRINGS,
                        "decode",
                        "(I)Ljava/lang/String;",
                        false,
                    ),
                )
                transformed++
            }
        }
        return transformed
    }

    private companion object {
        val APPLICATION_INITIALIZERS = setOf("<init>", "<clinit>")
        const val HARDEN_STRINGS = "com/apkharden/runtime/HardenStrings"
    }
}

class StringProtectionSelection(
    protectedPackages: Set<String>,
    applicationId: String,
) {
    private val packagePrefixes = (protectedPackages.ifEmpty { setOf(applicationId) })
        .map { value -> value.trim().replace('.', '/').trimEnd('/') }
        .onEach { value -> require(value.isNotBlank()) { "protected package is blank" } }

    fun includes(className: String): Boolean {
        val internalName = className.replace('.', '/')
        if (internalName.startsWith("com/apkharden/runtime/")) return false
        if (internalName.startsWith("com/apkharden/generated/")) return false
        val simpleName = internalName.substringAfterLast('/')
        if (simpleName == "R" || simpleName.startsWith("R$") || simpleName == "BR") return false
        if (simpleName == "BuildConfig") return false
        if ("/databinding/" in internalName || simpleName.startsWith("DataBinderMapper")) return false
        if (simpleName.endsWith("AppComponentFactory")) return false
        return packagePrefixes.any { prefix ->
            internalName == prefix || internalName.startsWith("$prefix/")
        }
    }
}
