package com.apkharden.gradle.task

import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.LifecycleTasks
import com.android.build.api.variant.SourceDirectories
import com.android.build.api.variant.Sources
import com.android.build.api.variant.VariantOutput
import com.apkharden.gradle.ApkHardenExtension
import com.apkharden.gradle.PluginBuildInfo
import com.apkharden.gradle.VariantDescriptor
import com.apkharden.release.metadata.HardenMetadataReader
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.tasks.TaskProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GenerateHardenMetadataTaskTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `metadata is deterministic and matches schema for every supported ABI variant`() {
        val scenarios = listOf(
            Scenario("product_32", false, setOf("armeabi-v7a"), 101L),
            Scenario("product_64", true, setOf("arm64-v8a"), 102L),
            Scenario("product_all", false, setOf("arm64-v8a", "armeabi-v7a"), 103L),
        )

        scenarios.forEachIndexed { index, scenario ->
            val project = ProjectBuilder.builder()
                .withProjectDir(File(temp, scenario.variantName).apply { mkdirs() })
                .build()
            val buildId = createBuildId(
                applicationId = "com.example.app",
                variantName = scenario.variantName,
                versionCode = scenario.versionCode,
                certificateSha256 = CERTIFICATE,
            )
            val output = File(project.projectDir, "harden-metadata.json")
            val task = project.tasks.register(
                "generateMetadata$index",
                GenerateHardenMetadataTask::class.java,
            ).get().apply {
                schemaVersion.set(1)
                pluginVersion.set(PluginBuildInfo.VERSION)
                runtimeVersion.set(PluginBuildInfo.RUNTIME_VERSION)
                variantName.set(scenario.variantName)
                applicationId.set("com.example.app")
                versionCode.set(scenario.versionCode)
                minSdk.set(23)
                targetSdk.set(36)
                debuggable.set(false)
                r8Enabled.set(scenario.r8Enabled)
                abis.set(scenario.abis.reversed())
                certificateSha256.set(CERTIFICATE.uppercase())
                this.buildId.set(buildId)
                outputFile.set(output)
            }

            task.generate()
            val first = output.readText()
            task.generate()

            assertEquals(first, output.readText())
            val metadata = HardenMetadataReader.read(output)
            assertEquals(scenario.variantName, metadata.variantName)
            assertEquals(scenario.r8Enabled, metadata.r8Enabled)
            assertEquals(scenario.abis.toSortedSet(), metadata.abis)
            assertEquals(CERTIFICATE, metadata.expectedCertificateSha256)
            assertEquals(buildId, metadata.buildId)
        }
    }

    @Test
    fun `generated config contains matching public constants and no secret material`() {
        val project = ProjectBuilder.builder().withProjectDir(temp).build()
        val buildId = createBuildId("com.example.app", "product_64", 202L, CERTIFICATE)
        val task = project.tasks.register(
            "generateConfig",
            GenerateHardenConfigTask::class.java,
        ).get().apply {
            schemaVersion.set(1)
            pluginVersion.set(PluginBuildInfo.VERSION)
            runtimeVersion.set(PluginBuildInfo.RUNTIME_VERSION)
            variantName.set("product_64")
            applicationId.set("com.example.app")
            versionCode.set(202L)
            minSdk.set(23)
            targetSdk.set(36)
            debuggable.set(false)
            r8Enabled.set(true)
            abis.set(setOf("arm64-v8a"))
            certificateSha256.set(CERTIFICATE.uppercase())
            this.buildId.set(buildId)
            outputDirectory.set(File(temp, "generated"))
        }

        task.generate()

        val source = File(
            task.outputDirectory.get().asFile,
            "com/apkharden/generated/HardenVariantConfig.java",
        ).readText()
        assertTrue(source.contains("SCHEMA_VERSION = 1"))
        assertTrue(source.contains("VARIANT_NAME = \"product_64\""))
        assertTrue(source.contains("VERSION_CODE = 202L"))
        assertTrue(source.contains("CERTIFICATE_SHA256 = \"$CERTIFICATE\""))
        assertTrue(source.contains("BUILD_ID = \"$buildId\""))
        assertTrue(source.contains("HardenConfig INSTANCE"))
        assertFalse(source.contains("password", ignoreCase = true))
        assertFalse(source.contains("privateKey", ignoreCase = true))
    }

    @Test
    fun `build id is stable for the same inputs and unique per variant`() {
        val first = createBuildId("com.example.app", "product_32", 1L, CERTIFICATE)
        val repeated = createBuildId("com.example.app", "product_32", 1L, CERTIFICATE.uppercase())
        val otherVariant = createBuildId("com.example.app", "product_64", 1L, CERTIFICATE)

        assertEquals(first, repeated)
        assertNotEquals(first, otherVariant)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `variant registration wires generated source and metadata output`() {
        val project = ProjectBuilder.builder().withProjectDir(temp).build()
        val extension = ApkHardenExtension(project.objects).apply {
            certificateSha256.set(CERTIFICATE.uppercase())
        }
        val versionCode = project.objects.property(Int::class.java).value(303)
        val output = proxy<VariantOutput> { method, _ ->
            when (method.name) {
                "getVersionCode" -> versionCode
                else -> unsupported(method)
            }
        }
        var generatedSourceTask: TaskProvider<out Task>? = null
        val javaSources = proxy<SourceDirectories.Flat> { method, arguments ->
            when (method.name) {
                "addGeneratedSourceDirectory" -> {
                    @Suppress("UNCHECKED_CAST")
                    generatedSourceTask = arguments!![0] as TaskProvider<out Task>
                    null
                }
                "getName" -> "java"
                else -> unsupported(method)
            }
        }
        val sources = proxy<Sources> { method, _ ->
            when (method.name) {
                "getJava" -> javaSources
                else -> unsupported(method)
            }
        }
        var preBuildDependency: Any? = null
        val lifecycleTasks = proxy<LifecycleTasks> { method, arguments ->
            when (method.name) {
                "registerPreBuild" -> {
                    val dependencies = arguments!![0] as Array<*>
                    preBuildDependency = dependencies.single()
                    null
                }
                else -> unsupported(method)
            }
        }
        val variant = proxy<ApplicationVariant> { method, arguments ->
            when (method.name) {
                "getOutputs" -> listOf(output)
                "getDebuggable" -> false
                "isMinifyEnabled" -> true
                "getSources" -> sources
                "getLifecycleTasks" -> lifecycleTasks
                "computeTaskName" -> arguments!!.joinToString(
                    separator = "Product64Release",
                    transform = Any?::toString,
                )
                else -> unsupported(method)
            }
        }
        val descriptor = VariantDescriptor(
            name = "product64Release",
            buildType = "release",
            productFlavors = emptyList(),
            applicationId = "com.example.app",
            minSdk = 23,
            targetSdk = 36,
            abiFilters = setOf("arm64-v8a"),
        )

        registerVariantGenerationTasks(project, extension, variant, descriptor)

        val metadataTask = project.tasks.named(
            "generateProduct64ReleaseHardenMetadata",
            GenerateHardenMetadataTask::class.java,
        ).get()
        val configTask = project.tasks.named(
            "generateProduct64ReleaseHardenConfig",
            GenerateHardenConfigTask::class.java,
        ).get()
        assertEquals(303L, metadataTask.versionCode.get())
        assertTrue(metadataTask.r8Enabled.get())
        assertEquals(CERTIFICATE.uppercase(), metadataTask.certificateSha256.get())
        assertEquals(configTask.name, generatedSourceTask?.name)
        assertEquals(metadataTask.name, (preBuildDependency as TaskProvider<*>).name)
        assertTrue(metadataTask.outputFile.get().asFile.path.replace('\\', '/').endsWith(
            "build/outputs/apk-harden/product64Release/harden-metadata.json",
        ))
    }

    private data class Scenario(
        val variantName: String,
        val r8Enabled: Boolean,
        val abis: Set<String>,
        val versionCode: Long,
    )

    private companion object {
        val CERTIFICATE = "ab".repeat(32)
    }

    private inline fun <reified T> proxy(
        crossinline handler: (Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { instance, method, arguments ->
        when (method.name) {
            "toString" -> "Fake${T::class.java.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === arguments?.firstOrNull()
            else -> handler(method, arguments)
        }
    } as T

    private fun unsupported(method: Method): Nothing =
        error("Unexpected method: ${method.declaringClass.simpleName}.${method.name}")
}
