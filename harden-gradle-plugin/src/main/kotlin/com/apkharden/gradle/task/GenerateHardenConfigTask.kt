package com.apkharden.gradle.task

import java.io.File
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateHardenConfigTask : HardenVariantGenerationTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val input = generationInput()
        val output = File(
            outputDirectory.get().asFile,
            "com/apkharden/generated/HardenVariantConfig.java",
        )
        output.parentFile.mkdirs()
        output.writeText(javaSource(input), Charsets.UTF_8)
    }

    private fun javaSource(input: HardenGenerationInput): String {
        val abiValues = input.abis.joinToString(", ") { "\"${it.javaEscaped()}\"" }
        return """
            package com.apkharden.generated;

            import com.apkharden.runtime.HardenConfig;

            public final class HardenVariantConfig {
                public static final int SCHEMA_VERSION = ${input.schemaVersion};
                public static final String PLUGIN_VERSION = "${input.pluginVersion.javaEscaped()}";
                public static final String RUNTIME_VERSION = "${input.runtimeVersion.javaEscaped()}";
                public static final String VARIANT_NAME = "${input.variantName.javaEscaped()}";
                public static final String APPLICATION_ID = "${input.applicationId.javaEscaped()}";
                public static final long VERSION_CODE = ${input.versionCode}L;
                public static final int MIN_SDK = ${input.minSdk};
                public static final int TARGET_SDK = ${input.targetSdk};
                public static final boolean DEBUGGABLE = ${input.debuggable};
                public static final boolean R8_ENABLED = ${input.r8Enabled};
                public static final String[] ABIS = new String[] {$abiValues};
                public static final String CERTIFICATE_SHA256 = "${input.certificateSha256}";
                public static final String BUILD_ID = "${input.buildId.javaEscaped()}";
                public static final HardenConfig INSTANCE = new HardenConfig(
                    APPLICATION_ID,
                    VARIANT_NAME,
                    BUILD_ID,
                    CERTIFICATE_SHA256
                );

                private HardenVariantConfig() {}
            }
        """.trimIndent() + "\n"
    }

    private fun String.javaEscaped(): String = buildString(length) {
        this@javaEscaped.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
