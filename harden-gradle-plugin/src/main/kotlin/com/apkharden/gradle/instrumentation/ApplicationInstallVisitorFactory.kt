package com.apkharden.gradle.instrumentation

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationVariant
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.w3c.dom.Element

abstract class ApplicationInstallVisitorFactory :
    AsmClassVisitorFactory<ApplicationInstallVisitorFactory.Parameters> {
    interface Parameters : InstrumentationParameters {
        @get:Input
        val applicationClassName: Property<String>
    }

    override fun isInstrumentable(classData: ClassData): Boolean =
        isApplicationClass(
            classData.className,
            parameters.get().applicationClassName.orNull?.ifBlank { null },
        )

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = ApplicationInstallVisitor(nextClassVisitor)
}

internal object ManifestApplicationResolver {
    fun resolve(manifest: File): String? {
        require(manifest.isFile) { "Merged manifest not found: $manifest" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val document = factory.newDocumentBuilder().parse(manifest)
        val manifestElement = document.documentElement
        val application = document.getElementsByTagName("application").item(0) as? Element
            ?: return null
        val name = application.getAttributeNS(ANDROID_NAMESPACE, "name")
            .ifBlank { application.getAttribute("android:name") }
            .trim()
        if (name.isBlank()) return null

        val packageName = manifestElement.getAttribute("package").trim()
        return when {
            name.startsWith('.') -> {
                require(packageName.isNotBlank()) { "Manifest package is required for $name" }
                packageName + name
            }
            '.' !in name -> {
                require(packageName.isNotBlank()) { "Manifest package is required for $name" }
                "$packageName.$name"
            }
            else -> name
        }
    }

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
}

internal fun isApplicationClass(className: String, applicationClassName: String?): Boolean =
    applicationClassName != null &&
        applicationClassName != DEFAULT_HARDEN_APPLICATION &&
        className == applicationClassName

private const val DEFAULT_HARDEN_APPLICATION = "com.apkharden.runtime.HardenApplication"

internal fun registerApplicationInstrumentation(variant: ApplicationVariant) {
    variant.instrumentation.transformClassesWith(
        ApplicationInstallVisitorFactory::class.java,
        InstrumentationScope.ALL,
    ) { parameters ->
        parameters.applicationClassName.set(
            variant.artifacts.get(SingleArtifact.MERGED_MANIFEST).map { manifest ->
                ManifestApplicationResolver.resolve(manifest.asFile).orEmpty()
            },
        )
    }
    variant.instrumentation.setAsmFramesComputationMode(
        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
    )
}
