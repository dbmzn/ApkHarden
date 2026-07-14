package com.apkharden.gradle

import com.android.build.api.variant.ApplicationVariant

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
        fun from(variant: ApplicationVariant): VariantDescriptor = VariantDescriptor(
            name = variant.name,
            buildType = variant.buildType,
            productFlavors = variant.productFlavors.map { (dimension, value) ->
                ProductFlavor(dimension, value)
            },
            applicationId = variant.applicationId.get(),
            minSdk = variant.minSdk.apiLevel,
            targetSdk = variant.targetSdk.apiLevel,
            abiFilters = variant.externalNativeBuild
                ?.abiFilters
                ?.getOrElse(emptySet())
                ?.toSortedSet()
                .orEmpty(),
        )
    }
}
