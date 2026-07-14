package com.apkharden.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.apkharden.gradle.instrumentation.registerApplicationInstrumentation
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
            val androidComponents = project.extensions.getByType(
                ApplicationAndroidComponentsExtension::class.java,
            )
            registerApplicationVariants(androidComponents, extension) { variant, descriptor ->
                registerVariantGenerationTasks(project, extension, variant, descriptor)
                registerDefaultApplicationManifestTransform(project, variant)
                registerApplicationInstrumentation(variant)
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

internal fun registerApplicationVariants(
    androidComponents: ApplicationAndroidComponentsExtension,
    extension: ApkHardenExtension,
    onVariant: (ApplicationVariant, VariantDescriptor) -> Unit,
) {
    val allVariants = androidComponents.selector().all()
    androidComponents.onVariants(allVariants, Action { variant ->
        val descriptor = VariantDescriptor.from(variant)
        if (extension.enabled.get() && descriptor.name !in extension.excludedVariants.get()) {
            onVariant(variant, descriptor)
        }
    })
}
