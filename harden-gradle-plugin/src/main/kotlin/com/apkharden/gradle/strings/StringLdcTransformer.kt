package com.apkharden.gradle.strings

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class StringLdcTransformer(
    private val stringIds: Map<String, Int>,
    private val excludedStrings: Set<String> = emptySet(),
) {
    fun collect(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): List<String> = eligibleMethods(classNode, applicationClassName)
        .filterNot(FrameworkStringExclusions::excludes)
        .flatMap { method ->
            method.instructions.toArray().mapNotNull { instruction ->
                ((instruction as? LdcInsnNode)?.cst as? String)
                    ?.takeIf { value -> value.isNotEmpty() && value !in excludedStrings }
            }
        }

    fun transform(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): Int {
        var transformed = 0
        eligibleMethods(classNode, applicationClassName)
            .filterNot(FrameworkStringExclusions::excludes)
            .forEach { method ->
            method.instructions.toArray().forEach { instruction ->
                val plaintext = (instruction as? LdcInsnNode)?.cst as? String
                    ?: return@forEach
                if (plaintext in excludedStrings) return@forEach
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
    excludedClasses: Set<String> = emptySet(),
) {
    private val packagePrefixes = (protectedPackages.ifEmpty { setOf(applicationId) })
        .map { value -> value.trim().replace('.', '/').trimEnd('/') }
        .onEach { value -> require(value.isNotBlank()) { "protected package is blank" } }
    private val excludedClassPatterns = excludedClasses.map(::ClassPattern)

    fun includes(className: String): Boolean {
        val internalName = className.replace('.', '/')
        if (excludedClassPatterns.any { pattern -> pattern.matches(internalName) }) return false
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

    fun includes(classNode: ClassNode): Boolean {
        if (!includes(classNode.name)) return false
        val annotations = classNode.visibleAnnotations.orEmpty() + classNode.invisibleAnnotations.orEmpty()
        return annotations.none { annotation -> annotation.desc in FRAMEWORK_CLASS_ANNOTATIONS }
    }

    private class ClassPattern(value: String) {
        private val wildcard = value.endsWith(".*") || value.endsWith("/**")
        private val normalized = value
            .removeSuffix(".*")
            .removeSuffix("/**")
            .trim()
            .replace('.', '/')
            .trimEnd('/')
            .also { require(it.isNotBlank()) { "excluded class pattern is blank" } }

        fun matches(className: String): Boolean = if (wildcard) {
            className == normalized || className.startsWith("$normalized/")
        } else {
            className == normalized
        }
    }

    private companion object {
        val FRAMEWORK_CLASS_ANNOTATIONS = setOf(
            "Landroidx/room/Dao;",
            "Landroidx/room/Database;",
            "Landroidx/room/Entity;",
            "Landroidx/room/TypeConverter;",
            "Landroidx/room/TypeConverters;",
            "Lkotlinx/serialization/Serializable;",
        )
    }
}

private object FrameworkStringExclusions {
    fun excludes(method: MethodNode): Boolean = method.instructions.toArray().any { instruction ->
        val call = instruction as? MethodInsnNode ?: return@any false
        when {
            call.owner == "java/lang/Class" && call.name in CLASS_REFLECTION_METHODS -> true
            call.owner == "java/lang/ClassLoader" && call.name in CLASS_LOADER_METHODS -> true
            call.owner == "java/lang/System" && call.name in SYSTEM_LOADING_METHODS -> true
            call.owner == "java/lang/invoke/MethodHandles\$Lookup" && call.name.startsWith("find") -> true
            call.owner == "java/util/ServiceLoader" && call.name == "load" -> true
            call.owner == "android/content/res/Resources" && call.name == "getIdentifier" -> true
            call.owner == "android/content/res/AssetManager" && call.name in ASSET_METHODS -> true
            call.owner.startsWith("androidx/room/") -> true
            call.owner.startsWith("androidx/sqlite/") -> true
            call.owner.startsWith("kotlinx/serialization/") -> true
            else -> false
        }
    }

    private val CLASS_REFLECTION_METHODS = setOf(
        "forName",
        "getField",
        "getDeclaredField",
        "getMethod",
        "getDeclaredMethod",
        "getResource",
        "getResourceAsStream",
    )
    private val CLASS_LOADER_METHODS = setOf(
        "loadClass",
        "getResource",
        "getResources",
        "getResourceAsStream",
    )
    private val SYSTEM_LOADING_METHODS = setOf("load", "loadLibrary")
    private val ASSET_METHODS = setOf("open", "openFd", "list")
}
