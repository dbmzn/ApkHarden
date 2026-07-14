package com.apkharden.gradle.strings

import java.io.File
import java.util.EnumMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode

enum class StringExclusionReasonCode {
    OUTSIDE_PROTECTED_PACKAGES,
    EXPLICIT_CLASS_EXCLUSION,
    HARDEN_RUNTIME_CLASS,
    GENERATED_HARDEN_CLASS,
    ANDROID_GENERATED_CLASS,
    DATABINDING_CLASS,
    APP_COMPONENT_FACTORY_CLASS,
    FRAMEWORK_ANNOTATED_CLASS,
    APPLICATION_INITIALIZER,
    FRAMEWORK_REFLECTION_CONTRACT,
    FRAMEWORK_CLASS_LOADING_CONTRACT,
    FRAMEWORK_NATIVE_LOADING_CONTRACT,
    FRAMEWORK_METHOD_HANDLE_CONTRACT,
    FRAMEWORK_SERVICE_LOADER_CONTRACT,
    FRAMEWORK_RESOURCE_NAME_CONTRACT,
    FRAMEWORK_ROOM_CONTRACT,
    FRAMEWORK_SERIALIZATION_CONTRACT,
    EMPTY_STRING,
    EXPLICIT_STRING_EXCLUSION,
    ANNOTATION_VALUE_CONTRACT,
    INVOKEDYNAMIC_BOOTSTRAP_CONTRACT,
    CONSTANT_DYNAMIC_CONTRACT,
    APPLICATION_CONSTANT_FIELD,
    PUBLIC_CONSTANT_INLINING_RISK,
    UNSAFE_CONSTANT_FIELD,
}

data class StringProtectionStatistics(
    val protectedMethodBodySites: Int,
    val protectedPrivateConstantSites: Int,
    val excludedByReason: Map<StringExclusionReasonCode, Int>,
) {
    val protectedSites: Int = protectedMethodBodySites + protectedPrivateConstantSites
    val excludedSites: Int = excludedByReason.values.sum()
}

data class StringProtectionReport(
    val variantName: String,
    val uniqueProtectedStrings: Int,
    val statistics: StringProtectionStatistics,
)

object StringProtectionReportWriter {
    private const val SCHEMA_VERSION = 1
    private const val PUBLIC_CONSTANT_RISK =
        "Public or protected String constants remain plaintext because changing ConstantValue " +
            "would break Java and Kotlin compile-time inlining semantics."
    private val json = Json { prettyPrint = true }

    fun write(report: StringProtectionReport, output: File) {
        val root = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("variantName", report.variantName)
            put("protected", buildJsonObject {
                put("sites", report.statistics.protectedSites)
                put("uniqueStrings", report.uniqueProtectedStrings)
                put("methodBodySites", report.statistics.protectedMethodBodySites)
                put("privateConstantSites", report.statistics.protectedPrivateConstantSites)
            })
            put("excluded", buildJsonObject {
                put("sites", report.statistics.excludedSites)
                put("byReason", buildJsonArray {
                    report.statistics.excludedByReason.toSortedMap(compareBy { it.name })
                        .forEach { (reason, count) ->
                            add(buildJsonObject {
                                put("code", reason.name)
                                put("count", count)
                            })
                        }
                })
            })
            put("risks", buildJsonArray {
                report.statistics.excludedByReason[StringExclusionReasonCode.PUBLIC_CONSTANT_INLINING_RISK]
                    ?.takeIf { it > 0 }
                    ?.let { count ->
                        add(buildJsonObject {
                            put("code", StringExclusionReasonCode.PUBLIC_CONSTANT_INLINING_RISK.name)
                            put("count", count)
                            put("message", PUBLIC_CONSTANT_RISK)
                        })
                    }
            })
        }
        output.parentFile.mkdirs()
        output.writeText(json.encodeToString(root) + "\n", Charsets.UTF_8)
    }
}

internal fun analyzeStringProtection(
    classNodes: Collection<ClassNode>,
    selection: StringProtectionSelection,
    applicationClassName: String?,
    excludedStrings: Set<String>,
): StringProtectionStatistics {
    var protectedMethods = 0
    var protectedConstants = 0
    val exclusions = EnumMap<StringExclusionReasonCode, Int>(StringExclusionReasonCode::class.java)

    fun exclude(reason: StringExclusionReasonCode, count: Int = 1) {
        if (count > 0) exclusions[reason] = exclusions.getOrDefault(reason, 0) + count
    }

    classNodes.forEach { classNode ->
        val classReason = selection.exclusionReason(classNode)
        if (classReason != null) {
            exclude(classReason, classNode.stringSiteCount())
            return@forEach
        }

        exclude(StringExclusionReasonCode.ANNOTATION_VALUE_CONTRACT, classNode.annotationStringCount())
        classNode.fields.forEach { field ->
            exclude(StringExclusionReasonCode.ANNOTATION_VALUE_CONTRACT, field.annotationStringCount())
            if (field.value !is String) return@forEach
            val reason = stringConstantExclusionReason(
                classNode,
                field,
                applicationClassName,
                excludedStrings,
            )
            if (reason == null) protectedConstants++ else exclude(reason)
        }
        classNode.methods.forEach { method ->
            exclude(StringExclusionReasonCode.ANNOTATION_VALUE_CONTRACT, method.annotationStringCount())
            val methodReason = methodStringExclusionReason(classNode, method, applicationClassName)
            method.instructions.toArray().forEach { instruction ->
                when (instruction) {
                    is LdcInsnNode -> when (val value = instruction.cst) {
                        is String -> {
                            val reason = methodReason ?: literalStringExclusionReason(value, excludedStrings)
                            if (reason == null) protectedMethods++ else exclude(reason)
                        }
                        is ConstantDynamic -> exclude(
                            methodReason ?: StringExclusionReasonCode.CONSTANT_DYNAMIC_CONTRACT,
                            value.embeddedStringCount(),
                        )
                    }
                    is InvokeDynamicInsnNode -> exclude(
                        methodReason ?: StringExclusionReasonCode.INVOKEDYNAMIC_BOOTSTRAP_CONTRACT,
                        instruction.bsmArgs.sumOf { it.embeddedStringCount() },
                    )
                }
            }
        }
    }
    return StringProtectionStatistics(
        protectedMethodBodySites = protectedMethods,
        protectedPrivateConstantSites = protectedConstants,
        excludedByReason = exclusions.toMap(),
    )
}

private fun ClassNode.stringSiteCount(): Int =
    annotationStringCount() +
        fields.sumOf { field -> (if (field.value is String) 1 else 0) + field.annotationStringCount() } +
        methods.sumOf { method ->
            method.annotationStringCount() + method.instructions.toArray().sumOf { instruction ->
                when (instruction) {
                    is LdcInsnNode -> instruction.cst.embeddedStringCount()
                    is InvokeDynamicInsnNode -> instruction.bsmArgs.sumOf { it.embeddedStringCount() }
                    else -> 0
                }
            }
        }

private fun ClassNode.annotationStringCount(): Int =
    (visibleAnnotations.orEmpty() + invisibleAnnotations.orEmpty()).sumOf(AnnotationNode::stringCount)

private fun FieldNode.annotationStringCount(): Int =
    (visibleAnnotations.orEmpty() + invisibleAnnotations.orEmpty()).sumOf(AnnotationNode::stringCount)

private fun MethodNode.annotationStringCount(): Int =
    (visibleAnnotations.orEmpty() + invisibleAnnotations.orEmpty()).sumOf(AnnotationNode::stringCount) +
        visibleParameterAnnotations.orEmpty().sumOf { annotations -> annotations.orEmpty().sumOf(AnnotationNode::stringCount) } +
        invisibleParameterAnnotations.orEmpty().sumOf { annotations -> annotations.orEmpty().sumOf(AnnotationNode::stringCount) }

private fun AnnotationNode.stringCount(): Int = values.orEmpty()
    .filterIndexed { index, _ -> index % 2 == 1 }
    .sumOf { value -> value.embeddedStringCount() }

private fun Any?.embeddedStringCount(): Int = when (this) {
    is String -> 1
    is AnnotationNode -> stringCount()
    is List<*> -> sumOf { value -> value.embeddedStringCount() }
    is ConstantDynamic -> (0 until bootstrapMethodArgumentCount)
        .sumOf { index -> getBootstrapMethodArgument(index).embeddedStringCount() }
    else -> 0
}
