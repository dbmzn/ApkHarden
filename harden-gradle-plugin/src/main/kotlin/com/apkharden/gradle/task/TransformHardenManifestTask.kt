package com.apkharden.gradle.task

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

@CacheableTask
abstract class TransformHardenManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputManifest: RegularFileProperty

    @get:OutputFile
    abstract val outputManifest: RegularFileProperty

    @TaskAction
    fun transform() {
        val input = inputManifest.get().asFile
        val output = outputManifest.get().asFile
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(input)
        val application = document.getElementsByTagName("application").item(0) as? Element
            ?: error("Merged manifest does not contain an application element")
        val currentName = application.getAttributeNS(ANDROID_NAMESPACE, "name")
            .ifBlank { application.getAttribute("android:name") }
            .trim()

        output.parentFile.mkdirs()
        if (currentName.isNotBlank()) {
            input.copyTo(output, overwrite = true)
            return
        }

        application.setAttributeNS(
            ANDROID_NAMESPACE,
            "android:name",
            HARDEN_APPLICATION,
        )
        val transformer = TransformerFactory.newInstance().apply {
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "")
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        transformer.transform(DOMSource(document), StreamResult(output))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val HARDEN_APPLICATION = "com.apkharden.runtime.HardenApplication"
    }
}

internal fun registerDefaultApplicationManifestTransform(
    project: Project,
    variant: ApplicationVariant,
) {
    val task = project.tasks.register(
        variant.computeTaskName("transform", "HardenManifest"),
        TransformHardenManifestTask::class.java,
    )
    variant.artifacts.use(task)
        .wiredWithFiles(
            TransformHardenManifestTask::inputManifest,
            TransformHardenManifestTask::outputManifest,
        )
        .toTransform(SingleArtifact.MERGED_MANIFEST)
}

private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }
