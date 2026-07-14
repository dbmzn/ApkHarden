package com.apkharden.gradle.strings

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import com.apkharden.gradle.ApkHardenExtension
import com.apkharden.gradle.VariantDescriptor
import com.apkharden.gradle.instrumentation.ManifestApplicationResolver
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

@DisableCachingByDefault(because = "Each clean build generates fresh key fragments and IVs")
abstract class TransformHardenStringsTask : DefaultTask() {
    @get:Classpath
    abstract val inputJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val inputDirectories: ListProperty<Directory>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Input
    abstract val certificateSha256: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val buildId: Property<String>

    @get:Input
    abstract val applicationClassName: Property<String>

    @get:Input
    abstract val protectedPackages: SetProperty<String>

    @get:Input
    abstract val excludedClasses: SetProperty<String>

    @get:Input
    abstract val excludedStrings: SetProperty<String>

    @TaskAction
    fun transform() {
        val entries = readInputs()
        check(CompiledStringTable.GENERATED_TABLE_ENTRY !in entries) {
            "Input already contains ${CompiledStringTable.GENERATED_TABLE_ENTRY}"
        }
        val selection = StringProtectionSelection(
            protectedPackages.get(),
            applicationId.get(),
            excludedClasses.get(),
        )
        val applicationClass = applicationClassName.orNull?.ifBlank { null }
        val nodes = linkedMapOf<String, ClassNode>()
        val collector = StringLdcTransformer(emptyMap(), excludedStrings.get())
        val constantCollector = StringConstantTransformer(emptyMap(), excludedStrings.get())
        val strings = buildList {
            entries.forEach { (name, bytes) ->
                if (!name.endsWith(CLASS_SUFFIX)) return@forEach
                val className = name.removeSuffix(CLASS_SUFFIX)
                val node = ClassNode(Opcodes.ASM9)
                ClassReader(bytes).accept(node, 0)
                if (!selection.includes(node)) return@forEach
                nodes[name] = node
                addAll(collector.collect(node, applicationClass))
                addAll(constantCollector.collect(node, applicationClass))
            }
        }
        val table = StringTableCompiler().compile(
            strings = strings,
            certificateSha256 = certificateSha256.get(),
            applicationId = applicationId.get(),
            buildId = buildId.get(),
        )
        val transformer = StringLdcTransformer(table.ids, excludedStrings.get())
        val constantTransformer = StringConstantTransformer(table.ids, excludedStrings.get())
        nodes.forEach { (name, node) ->
            transformer.transform(node, applicationClass)
            constantTransformer.transform(node, applicationClass)
            entries[name] = ClassWriter(0).also(node::accept).toByteArray()
        }
        entries[CompiledStringTable.GENERATED_TABLE_ENTRY] = table.classBytes()
        writeOutput(entries)
    }

    private fun readInputs(): java.util.SortedMap<String, ByteArray> = sortedMapOf<String, ByteArray>().apply {
        inputJars.get().map(RegularFile::getAsFile).sortedBy(File::getAbsolutePath).forEach { jar ->
            JarFile(jar).use { input ->
                input.entries().asSequence()
                    .filterNot { it.isDirectory || it.name == JarFile.MANIFEST_NAME }
                    .sortedBy(JarEntry::getName)
                    .forEach { entry ->
                        putInput(entry.name, input.getInputStream(entry).use { it.readBytes() }, jar)
                    }
            }
        }
        inputDirectories.get().map(Directory::getAsFile).sortedBy(File::getAbsolutePath).forEach { directory ->
            directory.walkTopDown()
                .filter(File::isFile)
                .sortedBy { file -> file.relativeTo(directory).invariantSeparatorsPath }
                .forEach { file ->
                    putInput(file.relativeTo(directory).invariantSeparatorsPath, file.readBytes(), directory)
                }
        }
    }

    private fun MutableMap<String, ByteArray>.putInput(
        name: String,
        bytes: ByteArray,
        source: File,
    ) {
        val existing = putIfAbsent(name, bytes) ?: return
        check(!name.endsWith(CLASS_SUFFIX) && existing.contentEquals(bytes)) {
            "Duplicate class entry $name from $source"
        }
    }

    private fun writeOutput(entries: Map<String, ByteArray>) {
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        JarOutputStream(output.outputStream().buffered()).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name).apply { time = 0L })
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }

    private companion object {
        const val CLASS_SUFFIX = ".class"
    }
}

internal fun registerStringProtectionTransform(
    project: Project,
    extension: ApkHardenExtension,
    variant: ApplicationVariant,
    descriptor: VariantDescriptor,
    buildId: Provider<String>,
) {
    val task = project.tasks.register(
        variant.computeTaskName("transform", "HardenStrings"),
        TransformHardenStringsTask::class.java,
    ) { transform ->
        transform.certificateSha256.set(extension.certificateSha256)
        transform.applicationId.set(descriptor.applicationId)
        transform.buildId.set(buildId)
        transform.protectedPackages.set(extension.protectedPackages)
        transform.excludedClasses.set(extension.excludedClasses)
        transform.excludedStrings.set(extension.excludedStrings)
        transform.applicationClassName.set(
            variant.artifacts.get(SingleArtifact.MERGED_MANIFEST).map { manifest ->
                ManifestApplicationResolver.resolve(manifest.asFile).orEmpty()
            },
        )
    }
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
        .use(task)
        .toTransform(
            ScopedArtifact.CLASSES,
            TransformHardenStringsTask::inputJars,
            TransformHardenStringsTask::inputDirectories,
            TransformHardenStringsTask::outputJar,
        )
}
