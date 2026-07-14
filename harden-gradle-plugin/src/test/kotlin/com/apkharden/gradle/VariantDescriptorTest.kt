package com.apkharden.gradle

import com.android.build.api.variant.AndroidVersion
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ExternalNativeBuild
import com.android.build.api.variant.VariantSelector
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VariantDescriptorTest {
    @Test
    fun `extension defaults are production safe`() {
        val project = ProjectBuilder.builder().build()
        val extension = ApkHardenExtension(project.objects)

        assertTrue(extension.enabled.get())
        assertEquals(emptySet<String>(), extension.excludedVariants.get())
        assertEquals(R8Policy.AUTO, extension.r8Policy.get())
        assertEquals(emptySet<String>(), extension.protectedPackages.get())
        assertFalse(extension.certificateSha256.isPresent)
    }

    @Test
    fun `descriptor captures public application variant properties`() {
        val project = ProjectBuilder.builder().build()
        val variant = fakeVariant(
            project = project,
            name = "productAllRelease",
            buildType = "release",
            flavors = listOf("market" to "product", "abi" to "all"),
            applicationId = "com.example.product",
            minSdk = 23,
            targetSdk = 36,
            abiFilters = setOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals(
            VariantDescriptor(
                name = "productAllRelease",
                buildType = "release",
                productFlavors = listOf(
                    ProductFlavor("market", "product"),
                    ProductFlavor("abi", "all"),
                ),
                applicationId = "com.example.product",
                minSdk = 23,
                targetSdk = 36,
                abiFilters = setOf("arm64-v8a", "armeabi-v7a"),
            ),
            VariantDescriptor.from(variant),
        )
    }

    @Test
    fun `descriptor uses empty ABI set without external native build`() {
        val project = ProjectBuilder.builder().build()

        val descriptor = VariantDescriptor.from(
            fakeVariant(project = project, name = "release", abiFilters = null),
        )

        assertEquals(emptySet<String>(), descriptor.abiFilters)
    }

    @Test
    fun `ABI filters merge every configured variant dimension`() {
        assertEquals(
            setOf("armeabi-v7a", "arm64-v8a", "x86_64"),
            mergeAbiFilters(
                setOf("armeabi-v7a"),
                setOf("arm64-v8a"),
                emptySet(),
                setOf("x86_64"),
            ),
        )
    }

    @Test
    fun `ABI discovery falls back to native source directories`() {
        val root = File("build/tmp/variant-descriptor-test/${System.nanoTime()}")
        try {
            File(root, "armeabi-v7a/libapp.so").apply {
                parentFile.mkdirs()
                writeText("fixture")
            }
            File(root, "arm64-v8a/libapp.so").apply {
                parentFile.mkdirs()
                writeText("fixture")
            }
            File(root, "notes/readme.txt").apply {
                parentFile.mkdirs()
                writeText("ignored")
            }

            assertEquals(
                setOf("armeabi-v7a", "arm64-v8a"),
                discoverSourceAbis(listOf(root)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `discovery uses all selector and excludes exact names only`() {
        val project = ProjectBuilder.builder().build()
        val extension = ApkHardenExtension(project.objects).apply {
            excludedVariants.set(setOf("release"))
        }
        var allCalls = 0
        lateinit var selector: VariantSelector
        selector = proxy { method, _ ->
            when (method.name) {
                "all" -> {
                    allCalls++
                    selector
                }
                else -> unsupported(method)
            }
        }
        var registeredSelector: VariantSelector? = null
        var registeredAction: Action<ApplicationVariant>? = null
        val androidComponents = proxy<ApplicationAndroidComponentsExtension> { method, arguments ->
            when (method.name) {
                "selector" -> selector
                "onVariants" -> {
                    registeredSelector = arguments!![0] as VariantSelector
                    @Suppress("UNCHECKED_CAST")
                    registeredAction = arguments[1] as Action<ApplicationVariant>
                    null
                }
                else -> unsupported(method)
            }
        }
        val discovered = mutableListOf<String>()

        registerApplicationVariants(androidComponents, extension) { _, descriptor ->
            discovered += descriptor.name
        }
        registeredAction!!.execute(fakeVariant(project, name = "release"))
        registeredAction!!.execute(fakeVariant(project, name = "productionRelease"))

        assertEquals(1, allCalls)
        assertTrue(registeredSelector === selector)
        assertEquals(listOf("productionRelease"), discovered)
    }

    @Test
    fun `plugin rejects Android library projects clearly`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.android.library")

        val error = assertThrows(GradleException::class.java) {
            project.pluginManager.apply(ApkHardenPlugin::class.java)
        }
        val messages = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")

        assertTrue(messages.contains("com.android.application")) { messages }
        assertTrue(messages.contains("com.android.library")) { messages }
    }

    private fun fakeVariant(
        project: org.gradle.api.Project,
        name: String,
        buildType: String? = "release",
        flavors: List<Pair<String, String>> = emptyList(),
        applicationId: String = "com.example.app",
        minSdk: Int = 23,
        targetSdk: Int = 36,
        abiFilters: Set<String>? = emptySet(),
    ): ApplicationVariant {
        val applicationIdProperty = project.objects.property(String::class.java).value(applicationId)
        val externalNativeBuild = abiFilters?.let {
            val abiFilterProperty = project.objects.setProperty(String::class.java).value(it)
            proxy<ExternalNativeBuild> { method, _ ->
                when (method.name) {
                    "getAbiFilters" -> abiFilterProperty
                    else -> unsupported(method)
                }
            }
        }
        val minimum = androidVersion(minSdk)
        val target = androidVersion(targetSdk)
        return proxy { method, _ ->
            when (method.name) {
                "getName" -> name
                "getBuildType" -> buildType
                "getProductFlavors" -> flavors
                "getApplicationId" -> applicationIdProperty
                "getMinSdk", "getMinSdkVersion" -> minimum
                "getTargetSdk", "getTargetSdkVersion" -> target
                "getExternalNativeBuild" -> externalNativeBuild
                else -> unsupported(method)
            }
        }
    }

    private fun androidVersion(apiLevel: Int): AndroidVersion = proxy { method, _ ->
        when (method.name) {
            "getApiLevel" -> apiLevel
            "getCodename" -> null
            else -> unsupported(method)
        }
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
