package com.apkharden.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.apkharden.gradle.instrumentation.registerApplicationInstrumentation
import com.apkharden.gradle.strings.registerStringProtectionTransform
import com.apkharden.gradle.task.registerDefaultApplicationManifestTransform
import com.apkharden.gradle.task.registerVariantGenerationTasks
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class ApkHardenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "apkHarden",
            ApkHardenExtension::class.java,
        )
        var applicationPluginApplied = false

        project.pluginManager.withPlugin(ANDROID_LIBRARY_PLUGIN) {
            throw unsupportedProject("com.android.library")
        }
        project.pluginManager.withPlugin(ANDROID_APPLICATION_PLUGIN) {
            applicationPluginApplied = true
            configureRuntimeSupport(project, extension)
            val androidComponents = project.extensions.getByType(
                ApplicationAndroidComponentsExtension::class.java,
            )
            val android = project.extensions.getByType(ApplicationExtension::class.java)
            registerApplicationVariants(androidComponents, extension, android) { variant, descriptor ->
                val buildId = registerVariantGenerationTasks(project, extension, variant, descriptor)
                registerDefaultApplicationManifestTransform(project, variant)
                registerApplicationInstrumentation(variant)
                registerStringProtectionTransform(project, extension, variant, descriptor, buildId)
            }
        }
        project.afterEvaluate {
            if (!applicationPluginApplied) {
                throw unsupportedProject("a non-Android application project")
            }
        }
    }

    private fun unsupportedProject(actualType: String): GradleException = GradleException(
        "Plugin com.apkharden.production requires com.android.application; " +
            "$actualType is not supported.",
    )

    private companion object {
        const val ANDROID_APPLICATION_PLUGIN = "com.android.application"
        const val ANDROID_LIBRARY_PLUGIN = "com.android.library"
    }
}

internal fun configureRuntimeSupport(
    project: Project,
    extension: ApkHardenExtension,
) {
    when (extension.r8Policy.get()) {
        R8Policy.AUTO, R8Policy.IGNORE -> Unit
    }
    val implementation = project.configurations.getByName("implementation")
    val alreadyAdded = implementation.dependencies.any { dependency ->
        dependency.group == PluginBuildInfo.RUNTIME_GROUP &&
            dependency.name == PluginBuildInfo.RUNTIME_ARTIFACT
    }
    if (!alreadyAdded) {
        project.dependencies.add("implementation", PluginBuildInfo.RUNTIME_COORDINATE)
    }
}

internal fun registerApplicationVariants(
    androidComponents: ApplicationAndroidComponentsExtension,
    extension: ApkHardenExtension,
    android: ApplicationExtension? = null,
    onVariant: (ApplicationVariant, VariantDescriptor) -> Unit,
) {
    val allVariants = androidComponents.selector().all()
    androidComponents.onVariants(allVariants, Action { variant ->
        val descriptor = VariantDescriptor.from(
            variant,
            android?.let { resolveAbiFilters(it, variant) }
                ?: variant.externalNativeBuild?.abiFilters?.getOrElse(emptySet()).orEmpty(),
        )
        if (extension.enabled.get() && descriptor.name !in extension.excludedVariants.get()) {
            onVariant(variant, descriptor)
        }
    })
}
