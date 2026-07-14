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
    ): List<String> = classNode.methods
        .filter { method -> methodStringExclusionReason(classNode, method, applicationClassName) == null }
        .flatMap { method ->
            method.instructions.toArray().mapNotNull { instruction ->
                ((instruction as? LdcInsnNode)?.cst as? String)
                    ?.takeIf { value -> literalStringExclusionReason(value, excludedStrings) == null }
            }
        }

    fun transform(
        classNode: ClassNode,
        applicationClassName: String? = null,
    ): Int {
        var transformed = 0
        classNode.methods
            .filter { method -> methodStringExclusionReason(classNode, method, applicationClassName) == null }
            .forEach { method ->
            method.instructions.toArray().forEach { instruction ->
                val plaintext = (instruction as? LdcInsnNode)?.cst as? String
                    ?: return@forEach
                if (literalStringExclusionReason(plaintext, excludedStrings) != null) return@forEach
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
        return exclusionReason(className) == null
    }

    internal fun exclusionReason(className: String): StringExclusionReasonCode? {
        val internalName = className.replace('.', '/')
        if (excludedClassPatterns.any { pattern -> pattern.matches(internalName) }) {
            return StringExclusionReasonCode.EXPLICIT_CLASS_EXCLUSION
        }
        if (internalName.startsWith("com/apkharden/runtime/")) {
            return StringExclusionReasonCode.HARDEN_RUNTIME_CLASS
        }
        if (internalName.startsWith("com/apkharden/generated/")) {
            return StringExclusionReasonCode.GENERATED_HARDEN_CLASS
        }
        val simpleName = internalName.substringAfterLast('/')
        if (simpleName == "R" || simpleName.startsWith("R$") || simpleName == "BR" || simpleName == "BuildConfig") {
            return StringExclusionReasonCode.ANDROID_GENERATED_CLASS
        }
        if ("/databinding/" in internalName || simpleName.startsWith("DataBinderMapper")) {
            return StringExclusionReasonCode.DATABINDING_CLASS
        }
        if (simpleName.endsWith("AppComponentFactory")) {
            return StringExclusionReasonCode.APP_COMPONENT_FACTORY_CLASS
        }
        if (packagePrefixes.none { prefix ->
            internalName == prefix || internalName.startsWith("$prefix/")
        }) return StringExclusionReasonCode.OUTSIDE_PROTECTED_PACKAGES
        return null
    }

    fun includes(classNode: ClassNode): Boolean {
        return exclusionReason(classNode) == null
    }

    internal fun exclusionReason(classNode: ClassNode): StringExclusionReasonCode? {
        exclusionReason(classNode.name)?.let { return it }
        val annotations = classNode.visibleAnnotations.orEmpty() + classNode.invisibleAnnotations.orEmpty()
        if (annotations.any { annotation -> annotation.desc in FRAMEWORK_CLASS_ANNOTATIONS }) {
            return StringExclusionReasonCode.FRAMEWORK_ANNOTATED_CLASS
        }
        return null
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

internal fun literalStringExclusionReason(
    value: String,
    excludedStrings: Set<String>,
): StringExclusionReasonCode? = when {
    value.isEmpty() -> StringExclusionReasonCode.EMPTY_STRING
    value in excludedStrings -> StringExclusionReasonCode.EXPLICIT_STRING_EXCLUSION
    else -> null
}

internal fun methodStringExclusionReason(
    classNode: ClassNode,
    method: MethodNode,
    applicationClassName: String?,
): StringExclusionReasonCode? {
    if (
        classNode.name == applicationClassName?.replace('.', '/') &&
        method.name in APPLICATION_INITIALIZERS
    ) return StringExclusionReasonCode.APPLICATION_INITIALIZER
    return FrameworkStringExclusions.reason(method)
}

private object FrameworkStringExclusions {
    fun reason(method: MethodNode): StringExclusionReasonCode? {
        method.instructions.toArray().forEach { instruction ->
            val call = instruction as? MethodInsnNode ?: return@forEach
            val reason = when {
                call.owner == "java/lang/Class" && call.name in CLASS_REFLECTION_METHODS ->
                    StringExclusionReasonCode.FRAMEWORK_REFLECTION_CONTRACT
                call.owner == "java/lang/ClassLoader" && call.name in CLASS_LOADER_METHODS ->
                    StringExclusionReasonCode.FRAMEWORK_CLASS_LOADING_CONTRACT
                call.owner == "java/lang/System" && call.name in SYSTEM_LOADING_METHODS ->
                    StringExclusionReasonCode.FRAMEWORK_NATIVE_LOADING_CONTRACT
                call.owner == "java/lang/invoke/MethodHandles\$Lookup" && call.name.startsWith("find") ->
                    StringExclusionReasonCode.FRAMEWORK_METHOD_HANDLE_CONTRACT
                call.owner == "java/util/ServiceLoader" && call.name == "load" ->
                    StringExclusionReasonCode.FRAMEWORK_SERVICE_LOADER_CONTRACT
                call.owner == "android/content/res/Resources" && call.name == "getIdentifier" ->
                    StringExclusionReasonCode.FRAMEWORK_RESOURCE_NAME_CONTRACT
                call.owner == "android/content/res/AssetManager" && call.name in ASSET_METHODS ->
                    StringExclusionReasonCode.FRAMEWORK_RESOURCE_NAME_CONTRACT
                call.owner.startsWith("androidx/room/") || call.owner.startsWith("androidx/sqlite/") ->
                    StringExclusionReasonCode.FRAMEWORK_ROOM_CONTRACT
                call.owner.startsWith("kotlinx/serialization/") ->
                    StringExclusionReasonCode.FRAMEWORK_SERIALIZATION_CONTRACT
                else -> null
            }
            if (reason != null) return reason
        }
        return null
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

private val APPLICATION_INITIALIZERS = setOf("<init>", "<clinit>")
