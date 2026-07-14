package com.apkharden.gradle.task

import com.android.build.api.artifact.Artifacts
import com.android.build.api.artifact.InAndOutFileOperationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.TaskBasedOperation
import com.android.build.api.variant.ApplicationVariant
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element

class TransformHardenManifestTaskTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `missing custom Application becomes HardenApplication without changing declarations`() {
        val input = File(temp, "input.xml").apply { writeText(manifest(applicationName = null)) }
        val output = File(temp, "output.xml")
        val task = ProjectBuilder.builder().withProjectDir(temp).build().tasks.register(
            "transformManifest",
            TransformHardenManifestTask::class.java,
        ).get().apply {
            inputManifest.set(input)
            outputManifest.set(output)
        }

        task.transform()

        val application = output.applicationElement()
        assertEquals(
            "com.apkharden.runtime.HardenApplication",
            application.getAttributeNS(ANDROID_NAMESPACE, "name"),
        )
        assertEquals("com.example.Factory", application.getAttributeNS(ANDROID_NAMESPACE, "appComponentFactory"))
        assertEquals("false", application.getAttributeNS(ANDROID_NAMESPACE, "debuggable"))
        assertEquals("false", application.getAttributeNS(ANDROID_NAMESPACE, "testOnly"))
        val provider = output.document().getElementsByTagName("provider").item(0) as Element
        assertEquals(":worker", provider.getAttributeNS(ANDROID_NAMESPACE, "process"))
        assertEquals("com.example.provider", provider.getAttributeNS(ANDROID_NAMESPACE, "authorities"))
    }

    @Test
    fun `custom Application manifest is copied byte for byte`() {
        val input = File(temp, "custom-input.xml").apply {
            writeText(manifest(applicationName = ".BusinessApplication"))
        }
        val output = File(temp, "custom-output.xml")
        val task = ProjectBuilder.builder().withProjectDir(temp).build().tasks.register(
            "transformCustomManifest",
            TransformHardenManifestTask::class.java,
        ).get().apply {
            inputManifest.set(input)
            outputManifest.set(output)
        }

        task.transform()

        assertArrayEquals(input.readBytes(), output.readBytes())
    }

    @Test
    fun `variant registration transforms merged manifest artifact`() {
        val project = ProjectBuilder.builder().withProjectDir(temp).build()
        var registeredTask: TaskProvider<out Task>? = null
        var transformedArtifact: Any? = null
        val request = proxy<InAndOutFileOperationRequest> { method, arguments ->
            when (method.name) {
                "toTransform" -> {
                    transformedArtifact = arguments!![0]
                    null
                }
                else -> unsupported(method)
            }
        }
        val operation = proxy<TaskBasedOperation<TransformHardenManifestTask>> { method, _ ->
            when (method.name) {
                "wiredWithFiles" -> request
                else -> unsupported(method)
            }
        }
        val artifacts = proxy<Artifacts> { method, arguments ->
            when (method.name) {
                "use" -> {
                    @Suppress("UNCHECKED_CAST")
                    registeredTask = arguments!![0] as TaskProvider<out Task>
                    operation
                }
                else -> unsupported(method)
            }
        }
        val variant = proxy<ApplicationVariant> { method, arguments ->
            when (method.name) {
                "getArtifacts" -> artifacts
                "computeTaskName" -> arguments!!.joinToString(
                    separator = "Release",
                    transform = Any?::toString,
                )
                else -> unsupported(method)
            }
        }

        registerDefaultApplicationManifestTransform(project, variant)

        assertEquals("transformReleaseHardenManifest", registeredTask?.name)
        assertTrue(transformedArtifact === SingleArtifact.MERGED_MANIFEST)
    }

    private fun manifest(applicationName: String?): String {
        val name = applicationName?.let { " android:name=\"$it\"" }.orEmpty()
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="$ANDROID_NAMESPACE" package="com.example">
                <application$name
                    android:appComponentFactory="com.example.Factory"
                    android:debuggable="false"
                    android:testOnly="false">
                    <provider
                        android:name="com.example.Provider"
                        android:authorities="com.example.provider"
                        android:exported="false"
                        android:process=":worker" />
                </application>
            </manifest>
        """.trimIndent()
    }

    private fun File.applicationElement(): Element =
        document().getElementsByTagName("application").item(0) as Element

    private fun File.document() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(this)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
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
