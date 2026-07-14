package com.apkharden.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationVariant
import java.io.File

data class ProductFlavor(
    val dimension: String,
    val value: String,
)

data class VariantDescriptor(
    val name: String,
    val buildType: String?,
    val productFlavors: List<ProductFlavor>,
    val applicationId: String,
    val minSdk: Int,
    val targetSdk: Int,
    val abiFilters: Set<String>,
) {
    companion object {
        fun from(
            variant: ApplicationVariant,
            abiFilters: Set<String> = variant.externalNativeBuild
                ?.abiFilters
                ?.getOrElse(emptySet())
                .orEmpty(),
        ): VariantDescriptor = VariantDescriptor(
            name = variant.name,
            buildType = variant.buildType,
            productFlavors = variant.productFlavors.map { (dimension, value) ->
                ProductFlavor(dimension, value)
            },
            applicationId = variant.applicationId.get(),
            minSdk = variant.minSdk.apiLevel,
            targetSdk = variant.targetSdk.apiLevel,
            abiFilters = abiFilters.toSortedSet(),
        )
    }
}

internal fun resolveAbiFilters(
    android: ApplicationExtension,
    variant: ApplicationVariant,
): Set<String> {
    val configured = mergeAbiFilters(
        android.defaultConfig.ndk.abiFilters,
        *variant.productFlavors.map { (_, flavorName) ->
            android.productFlavors.getByName(flavorName).ndk.abiFilters
        }.toTypedArray(),
        variant.buildType
            ?.let { android.buildTypes.getByName(it).ndk.abiFilters }
            .orEmpty(),
        variant.externalNativeBuild
            ?.abiFilters
            ?.getOrElse(emptySet())
            .orEmpty(),
    )
    if (configured.isNotEmpty()) return configured

    val sourceDirectories = variant.sources.jniLibs
        ?.all
        ?.getOrElse(emptyList())
        ?.flatten()
        ?.map { it.asFile }
        .orEmpty()
    return discoverSourceAbis(sourceDirectories)
}

internal fun mergeAbiFilters(vararg filters: Set<String>): Set<String> =
    filters.flatMapTo(sortedSetOf()) { it }

internal fun discoverSourceAbis(sourceDirectories: Collection<File>): Set<String> =
    sourceDirectories.asSequence()
        .filter(File::isDirectory)
        .flatMap { sourceDirectory ->
            sourceDirectory.walkTopDown()
                .filter { it.isFile && it.extension == "so" }
                .mapNotNull { library ->
                    library.relativeTo(sourceDirectory)
                        .invariantSeparatorsPath
                        .substringBefore('/')
                        .takeIf(KNOWN_ANDROID_ABIS::contains)
                }
        }
        .toCollection(sortedSetOf())

private val KNOWN_ANDROID_ABIS = setOf(
    "armeabi-v7a",
    "arm64-v8a",
    "x86",
    "x86_64",
    "riscv64",
)
