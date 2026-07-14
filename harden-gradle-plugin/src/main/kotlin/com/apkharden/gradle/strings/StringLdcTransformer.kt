package com.apkharden.gradle.strings

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

class StringLdcTransformer(
    private val stringIds: Map<String, Int>,
) {
    fun collect(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): List<String> = eligibleMethods(classNode, applicationClassName)
        .flatMap { method ->
            method.instructions.toArray().mapNotNull { instruction ->
                ((instruction as? LdcInsnNode)?.cst as? String)?.takeIf(String::isNotEmpty)
            }
        }

    fun transform(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): Int {
        var transformed = 0
        eligibleMethods(classNode, applicationClassName).forEach { method ->
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

    private fun eligibleMethods(
        classNode: ClassNode,
        applicationClassName: String?,
    ) = classNode.methods.filterNot { method ->
        classNode.name == applicationClassName?.replace('.', '/') &&
            method.name in APPLICATION_INITIALIZERS
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
