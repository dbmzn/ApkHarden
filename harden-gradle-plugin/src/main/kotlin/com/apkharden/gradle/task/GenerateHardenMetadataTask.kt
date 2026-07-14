package com.apkharden.gradle.task

import com.android.build.api.variant.ApplicationVariant
import com.apkharden.gradle.ApkHardenExtension
import com.apkharden.gradle.PluginBuildInfo
import com.apkharden.gradle.VariantDescriptor
import com.apkharden.release.metadata.HardenMetadata
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateHardenMetadataTask : HardenVariantGenerationTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val input = generationInput()
        val metadata = HardenMetadata(
            schemaVersion = input.schemaVersion,
            pluginVersion = input.pluginVersion,
            runtimeVersion = input.runtimeVersion,
            variantName = input.variantName,
            applicationId = input.applicationId,
            versionCode = input.versionCode,
            minSdk = input.minSdk,
            targetSdk = input.targetSdk,
            debuggable = input.debuggable,
            r8Enabled = input.r8Enabled,
            abis = input.abis,
            expectedCertificateSha256 = input.certificateSha256,
            buildId = input.buildId,
        )
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            METADATA_JSON.encodeToString(HardenMetadata.serializer(), metadata) + "\n",
            Charsets.UTF_8,
        )
    }

    private companion object {
        val METADATA_JSON = Json {
            prettyPrint = true
        }
    }
}

abstract class HardenVariantGenerationTask : DefaultTask() {
    @get:Input abstract val schemaVersion: Property<Int>
    @get:Input abstract val pluginVersion: Property<String>
    @get:Input abstract val runtimeVersion: Property<String>
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val versionCode: Property<Long>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:Input abstract val debuggable: Property<Boolean>
    @get:Input abstract val r8Enabled: Property<Boolean>
    @get:Input abstract val abis: SetProperty<String>
    @get:Input abstract val certificateSha256: Property<String>
    @get:Input abstract val buildId: Property<String>

    internal fun generationInput(): HardenGenerationInput = HardenGenerationInput(
        schemaVersion = schemaVersion.get(),
        pluginVersion = pluginVersion.get().requireNotBlank("pluginVersion"),
        runtimeVersion = runtimeVersion.get().requireNotBlank("runtimeVersion"),
        variantName = variantName.get().requireNotBlank("variantName"),
        applicationId = applicationId.get().requireNotBlank("applicationId"),
        versionCode = versionCode.get().also { require(it >= 0) { "versionCode is negative" } },
        minSdk = minSdk.get().also { require(it > 0) { "minSdk is invalid" } },
        targetSdk = targetSdk.get().also { require(it > 0) { "targetSdk is invalid" } },
        debuggable = debuggable.get(),
        r8Enabled = r8Enabled.get(),
        abis = abis.get().toSortedSet(),
        certificateSha256 = normalizeCertificate(certificateSha256.get()),
        buildId = buildId.get().requireNotBlank("buildId"),
    ).also {
        require(it.schemaVersion == 1) { "Unsupported metadata schema: ${it.schemaVersion}" }
    }
}

internal data class HardenGenerationInput(
    val schemaVersion: Int,
    val pluginVersion: String,
    val runtimeVersion: String,
    val variantName: String,
    val applicationId: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val r8Enabled: Boolean,
    val abis: Set<String>,
    val certificateSha256: String,
    val buildId: String,
)

internal fun createBuildId(
    applicationId: String,
    variantName: String,
    versionCode: Long,
    certificateSha256: String,
): String {
    val canonical = listOf(
        applicationId.requireNotBlank("applicationId"),
        variantName.requireNotBlank("variantName"),
        versionCode.also { require(it >= 0) { "versionCode is negative" } }.toString(),
        normalizeCertificate(certificateSha256),
    ).joinToString("\n")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun normalizeCertificate(value: String): String {
    require(value.matches(Regex("[0-9a-fA-F]{64}"))) {
        "certificateSha256 must contain 64 hexadecimal characters"
    }
    return value.lowercase()
}

private fun String.requireNotBlank(name: String): String =
    also { require(it.isNotBlank()) { "$name is blank" } }

internal fun registerVariantGenerationTasks(
    project: Project,
    extension: ApkHardenExtension,
    variant: ApplicationVariant,
    descriptor: VariantDescriptor,
) {
    val output = variant.outputs.singleOrNull() ?: throw GradleException(
        "ApkHarden requires exactly one APK output for variant ${descriptor.name}.",
    )
    val versionCodeProvider = output.versionCode.map { value ->
        requireNotNull(value) { "versionCode is missing for variant ${descriptor.name}" }.toLong()
    }
    val buildIdProvider = project.providers.provider {
        createBuildId(
            applicationId = descriptor.applicationId,
            variantName = descriptor.name,
            versionCode = versionCodeProvider.get(),
            certificateSha256 = extension.certificateSha256.get(),
        )
    }
    val metadataTask = project.tasks.register(
        variant.computeTaskName("generate", "HardenMetadata"),
        GenerateHardenMetadataTask::class.java,
    ) { task ->
        task.configureInputs(extension, variant, descriptor)
        task.versionCode.set(versionCodeProvider)
        task.buildId.set(buildIdProvider)
        task.outputFile.set(
            project.layout.buildDirectory.file(
                "outputs/apk-harden/${descriptor.name}/harden-metadata.json",
            ),
        )
    }
    val configTask = project.tasks.register(
        variant.computeTaskName("generate", "HardenConfig"),
        GenerateHardenConfigTask::class.java,
    ) { task ->
        task.configureInputs(extension, variant, descriptor)
        task.versionCode.set(versionCodeProvider)
        task.buildId.set(buildIdProvider)
        task.outputDirectory.set(
            project.layout.buildDirectory.dir("generated/source/apkHarden/${descriptor.name}"),
        )
    }

    val javaSources = variant.sources.java ?: throw GradleException(
        "Java source registration is unavailable for variant ${descriptor.name}.",
    )
    javaSources.addGeneratedSourceDirectory(
        configTask,
        GenerateHardenConfigTask::outputDirectory,
    )
    variant.lifecycleTasks.registerPreBuild(metadataTask)
}

private fun HardenVariantGenerationTask.configureInputs(
    extension: ApkHardenExtension,
    variant: ApplicationVariant,
    descriptor: VariantDescriptor,
) {
    schemaVersion.set(1)
    pluginVersion.set(PluginBuildInfo.VERSION)
    runtimeVersion.set(PluginBuildInfo.RUNTIME_VERSION)
    variantName.set(descriptor.name)
    applicationId.set(descriptor.applicationId)
    minSdk.set(descriptor.minSdk)
    targetSdk.set(descriptor.targetSdk)
    debuggable.set(variant.debuggable)
    r8Enabled.set(variant.isMinifyEnabled)
    abis.set(descriptor.abiFilters)
    certificateSha256.set(extension.certificateSha256)
}
