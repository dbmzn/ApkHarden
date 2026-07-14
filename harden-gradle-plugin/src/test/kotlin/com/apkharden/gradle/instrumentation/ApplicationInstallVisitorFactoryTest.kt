package com.apkharden.gradle.instrumentation

import com.android.build.api.artifact.Artifacts
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.variant.Instrumentation
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationVariant
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.jvm.functions.Function1
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApplicationInstallVisitorFactoryTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `manifest resolver normalizes custom Application names`() {
        assertEquals("com.example.FullApp", resolve("com.example.FullApp"))
        assertEquals("com.example.RelativeApp", resolve(".RelativeApp"))
        assertEquals("com.example.SimpleApp", resolve("SimpleApp"))
    }

    @Test
    fun `manifest resolver returns null without a custom Application`() {
        val manifest = File(temp, "AndroidManifest.xml")
        manifest.writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example"><application /></manifest>""",
        )

        assertNull(ManifestApplicationResolver.resolve(manifest))
    }

    @Test
    fun `application class matching is exact`() {
        assertTrue(isApplicationClass("com.example.App", "com.example.App"))
        assertFalse(isApplicationClass("com.example.AppHelper", "com.example.App"))
        assertFalse(isApplicationClass("com/example/App", "com.example.App"))
        assertFalse(isApplicationClass(
            "com.apkharden.runtime.HardenApplication",
            "com.apkharden.runtime.HardenApplication",
        ))
    }

    @Test
    fun `variant registration uses merged manifest all scope and instrumented method frames`() {
        val project = ProjectBuilder.builder().withProjectDir(temp).build()
        val manifest = File(temp, "merged.xml").apply {
            writeText(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example"><application android:name=".App" /></manifest>""",
            )
        }
        val manifestFile = project.objects.fileProperty().fileValue(manifest)
        val artifacts = proxy<Artifacts> { method, _ ->
            when (method.name) {
                "get" -> manifestFile
                else -> unsupported(method)
            }
        }
        var factoryClass: Class<*>? = null
        var scope: InstrumentationScope? = null
        var configure: Function1<ApplicationInstallVisitorFactory.Parameters, Unit>? = null
        var framesMode: FramesComputationMode? = null
        val instrumentation = proxy<Instrumentation> { method, arguments ->
            when (method.name) {
                "transformClassesWith" -> {
                    factoryClass = arguments!![0] as Class<*>
                    scope = arguments[1] as InstrumentationScope
                    @Suppress("UNCHECKED_CAST")
                    configure = arguments[2] as Function1<ApplicationInstallVisitorFactory.Parameters, Unit>
                    null
                }
                "setAsmFramesComputationMode" -> {
                    framesMode = arguments!![0] as FramesComputationMode
                    null
                }
                else -> unsupported(method)
            }
        }
        val variant = proxy<ApplicationVariant> { method, _ ->
            when (method.name) {
                "getArtifacts" -> artifacts
                "getInstrumentation" -> instrumentation
                else -> unsupported(method)
            }
        }

        registerApplicationInstrumentation(variant)
        val parameterApplicationClassName = project.objects.property(String::class.java)
        val parameters = proxy<ApplicationInstallVisitorFactory.Parameters> { method, _ ->
            when (method.name) {
                "getApplicationClassName" -> parameterApplicationClassName
                else -> unsupported(method)
            }
        }
        configure!!.invoke(parameters)

        assertEquals(ApplicationInstallVisitorFactory::class.java, factoryClass)
        assertEquals(InstrumentationScope.ALL, scope)
        assertEquals("com.example.App", parameterApplicationClassName.get())
        assertEquals(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS, framesMode)
    }

    private fun resolve(applicationName: String): String {
        val manifest = File(temp, "${applicationName.hashCode()}.xml")
        manifest.writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example"><application android:name="$applicationName" /></manifest>""",
        )
        return ManifestApplicationResolver.resolve(manifest)!!
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
