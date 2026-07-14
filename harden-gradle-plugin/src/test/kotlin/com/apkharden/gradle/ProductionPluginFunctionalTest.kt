package com.apkharden.gradle

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.apkharden.release.metadata.HardenMetadataReader
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProductionPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    @BeforeEach
    fun prepareFixture() {
        val fixture = File("src/test/fixtures/android-app")
        fixture.copyRecursively(projectDir, overwrite = true)
        val runtimeAar = File(requireNotNull(System.getProperty("apkharden.runtime.aar")))
        val stringCryptoJar = File(
            requireNotNull(System.getProperty("apkharden.string.crypto.jar")),
        )
        val stringCryptoModule = File(
            projectDir,
            "repo/com/apkharden/harden-string-crypto/0.1.0",
        ).apply { mkdirs() }
        stringCryptoJar.copyTo(
            File(stringCryptoModule, "harden-string-crypto-0.1.0.jar"),
            overwrite = true,
        )
        File(stringCryptoModule, "harden-string-crypto-0.1.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.apkharden</groupId>
              <artifactId>harden-string-crypto</artifactId>
              <version>0.1.0</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent(),
        )
        val runtimeModule = File(
            projectDir,
            "repo/com/apkharden/harden-runtime/0.1.0",
        ).apply { mkdirs() }
        runtimeAar.copyTo(File(runtimeModule, "harden-runtime-0.1.0.aar"), overwrite = true)
        File(runtimeModule, "harden-runtime-0.1.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.apkharden</groupId>
              <artifactId>harden-runtime</artifactId>
              <version>0.1.0</version>
              <packaging>aar</packaging>
              <dependencies>
                <dependency>
                  <groupId>com.apkharden</groupId>
                  <artifactId>harden-string-crypto</artifactId>
                  <version>0.1.0</version>
                  <scope>runtime</scope>
                </dependency>
                <dependency>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-stdlib</artifactId>
                  <version>2.1.0</version>
                  <scope>runtime</scope>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent(),
        )
        val sdkDir = File(requireNotNull(System.getenv("ANDROID_HOME") ?: "C:\\AndroidSdk"))
        File(projectDir, "local.properties").writeText(
            "sdk.dir=${sdkDir.absolutePath.replace("\\", "\\\\")}\n",
        )
    }

    @Test
    fun `builds every ABI flavor with R8 disabled and enabled`() {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(
                "assembleDebug",
                "assembleDevelop",
                "assembleRelease",
                "--stacktrace",
                "--console=plain",
            )
            .forwardOutput()
            .build()

        val expectedVariants = listOf(
            ExpectedVariant("product_32Debug", "product_32", "debug", false, setOf("armeabi-v7a")),
            ExpectedVariant("product_32Develop", "product_32", "develop", false, setOf("armeabi-v7a")),
            ExpectedVariant("product_32Release", "product_32", "release", true, setOf("armeabi-v7a")),
            ExpectedVariant("product_64Debug", "product_64", "debug", false, setOf("arm64-v8a")),
            ExpectedVariant("product_64Develop", "product_64", "develop", false, setOf("arm64-v8a")),
            ExpectedVariant("product_64Release", "product_64", "release", true, setOf("arm64-v8a")),
            ExpectedVariant(
                "product_allDebug",
                "product_all",
                "debug",
                false,
                setOf("armeabi-v7a", "arm64-v8a"),
            ),
            ExpectedVariant(
                "product_allDevelop",
                "product_all",
                "develop",
                false,
                setOf("armeabi-v7a", "arm64-v8a"),
            ),
            ExpectedVariant(
                "product_allRelease",
                "product_all",
                "release",
                true,
                setOf("armeabi-v7a", "arm64-v8a"),
            ),
        )
        val outputRoot = File(projectDir, "app/build/outputs/apk-harden")
        val actualVariants = outputRoot.listFiles().orEmpty().filter(File::isDirectory).map(File::getName).toSet()
        val configSources = File(projectDir, "app/build/generated/java")
            .walkTopDown()
            .filter { it.isFile && it.name == "HardenVariantConfig.java" }
            .toList()
        val configByVariant = configSources.associateBy { source ->
            requireNotNull(VARIANT_NAME.find(source.readText())?.groupValues?.get(1)) {
                "Generated config does not contain VARIANT_NAME: $source"
            }
        }
        val mergedManifests = File(projectDir, "app/build/intermediates/merged_manifests")
            .walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .toList()
        val apks = File(projectDir, "app/build/outputs/apk")
            .walkTopDown()
            .filter { it.isFile && it.extension == "apk" }
            .toList()

        assertEquals(expectedVariants.mapTo(mutableSetOf(), ExpectedVariant::name), actualVariants)
        assertEquals(expectedVariants.mapTo(mutableSetOf(), ExpectedVariant::name), configByVariant.keys)
        expectedVariants.forEach { variant ->
            val metadata = HardenMetadataReader.read(
                File(outputRoot, "${variant.name}/harden-metadata.json"),
            )
            assertEquals(variant.name, metadata.variantName)
            assertEquals(variant.r8Enabled, metadata.r8Enabled)
            assertEquals(variant.abis, metadata.abis)
            assertEquals(CERTIFICATE, metadata.expectedCertificateSha256)

            val config = configByVariant.getValue(variant.name).readText()
            assertTrue(config.contains("R8_ENABLED = ${variant.r8Enabled}"))
            assertTrue(config.contains("CERTIFICATE_SHA256 = \"${metadata.expectedCertificateSha256}\""))
            assertTrue(config.contains("BUILD_ID = \"${metadata.buildId}\""))

            val manifest = mergedManifests.single { file ->
                "/${variant.name}/" in file.invariantSeparatorsPath
            }
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(manifest)
            val application = document.getElementsByTagName("application").item(0)
            val activity = document.getElementsByTagName("activity").item(0)
            val providers = document.getElementsByTagName("provider")
            assertEquals(
                "com.example.fixture.BusinessApplication",
                componentName(application.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue),
            )
            assertEquals(
                "com.example.fixture.FixtureAppComponentFactory",
                componentName(
                    application.attributes.getNamedItemNS(ANDROID_NAMESPACE, "appComponentFactory").nodeValue,
                ),
            )
            assertEquals(
                "com.example.fixture.SmokeActivity",
                componentName(activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue),
            )
            val providerAuthorities = (0 until providers.length).associate { index ->
                val provider = providers.item(index)
                provider.attributes.getNamedItemNS(ANDROID_NAMESPACE, "authorities").nodeValue to
                    provider.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
            }
            assertEquals(null, providerAuthorities["com.example.fixture.main-probe"])
            assertEquals(":worker", providerAuthorities["com.example.fixture.worker-probe"])

            val apk = apks.single { file ->
                "-${variant.flavor}-${variant.buildType}" in file.name
            }
            assertEquals(variant.abis, apkAbis(apk))
            assertFalse(apkDexContains(apk, PROTECTED_FIXTURE_STRING)) {
                "Protected fixture plaintext remains in $apk"
            }
            assertTrue(GENERATED_STRING_TABLE in apkClassTypes(apk)) {
                "Generated string table is missing from $apk"
            }
            val applicationReferences = applicationMethodReferences(apk)
            val installTarget = runtimeInstallTarget(variant)
            assertTrue(applicationReferences.any { reference ->
                reference.definingClass == installTarget.definingClass &&
                    reference.name == installTarget.methodName
            }) {
                "BusinessApplication does not call ${installTarget.definingClass}->" +
                    "${installTarget.methodName} in $apk. " +
                    "References: ${applicationReferences.joinToString()}"
            }
        }
    }

    private fun apkAbis(apk: File): Set<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.substringAfter("lib/").substringBefore('/') }
            .toSet()
    }

    private fun apkDexContains(apk: File, value: String): Boolean = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.matches(DEX_ENTRY) }
            .any { entry ->
                zip.getInputStream(entry).use { input ->
                    input.readBytes().toString(Charsets.ISO_8859_1).contains(value)
                }
            }
    }

    private fun apkClassTypes(apk: File): Set<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.matches(DEX_ENTRY) }
            .flatMap { entry ->
                val dex = zip.getInputStream(entry).use { input ->
                    DexBackedDexFile.fromInputStream(
                        Opcodes.getDefault(),
                        BufferedInputStream(input),
                    )
                }
                dex.classes.asSequence().map { it.type }
            }
            .toSet()
    }

    private fun applicationMethodReferences(apk: File): List<MethodReference> = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.matches(DEX_ENTRY) }
            .flatMap { entry ->
                val dex = zip.getInputStream(entry).use { input ->
                    DexBackedDexFile.fromInputStream(
                        Opcodes.getDefault(),
                        BufferedInputStream(input),
                    )
                }
                dex.classes.asSequence()
                    .filter { it.type == BUSINESS_APPLICATION }
                    .flatMap { it.methods.asSequence() }
                    .filter { it.name == "attachBaseContext" }
                    .flatMap { method -> method.implementation?.instructions?.asSequence().orEmpty() }
                    .filterIsInstance<ReferenceInstruction>()
                    .mapNotNull { it.reference as? MethodReference }
            }
            .toList()
    }

    private fun runtimeInstallTarget(variant: ExpectedVariant): MethodTarget {
        if (!variant.r8Enabled) return MethodTarget(HARDEN_RUNTIME, "install")

        val mapping = File(
            projectDir,
            "app/build/outputs/mapping/${variant.name}/mapping.txt",
        ).readLines()
        val classIndex = mapping.indexOfFirst { line ->
            line.startsWith("com.apkharden.runtime.HardenRuntime -> ")
        }
        require(classIndex >= 0) { "HardenRuntime is missing from R8 mapping for ${variant.name}" }
        val definingClass = mapping[classIndex]
            .substringAfter(" -> ")
            .removeSuffix(":")
            .replace('.', '/')
            .let { "L$it;" }
        val nextClassIndex = (classIndex + 1 until mapping.size).firstOrNull { index ->
            CLASS_MAPPING.matches(mapping[index])
        } ?: mapping.size
        val methodLine = mapping.subList(classIndex + 1, nextClassIndex)
            .firstOrNull { line ->
                line.startsWith(' ') && Regex("\\binstall\\(").containsMatchIn(line)
            }
        requireNotNull(methodLine) {
            "HardenRuntime.install is missing from R8 mapping for ${variant.name}. " +
                mapping.filter { "HardenRuntime" in it || "install" in it }.joinToString()
        }
        val methodName = methodLine
            .substringAfterLast(" -> ")
            .trim()
        return MethodTarget(definingClass, methodName)
    }

    private fun componentName(name: String): String = when {
        name.startsWith('.') -> "com.example.fixture$name"
        '.' !in name -> "com.example.fixture.$name"
        else -> name
    }

    private data class ExpectedVariant(
        val name: String,
        val flavor: String,
        val buildType: String,
        val r8Enabled: Boolean,
        val abis: Set<String>,
    )

    private data class MethodTarget(
        val definingClass: String,
        val methodName: String,
    )

    private companion object {
        val CERTIFICATE = "ab".repeat(32)
        val VARIANT_NAME = Regex("VARIANT_NAME = \"([^\"]+)\"")
        val DEX_ENTRY = Regex("classes(?:\\d+)?\\.dex")
        val CLASS_MAPPING = Regex("^[^#\\s].+ -> .+:$")
        const val BUSINESS_APPLICATION = "Lcom/example/fixture/BusinessApplication;"
        const val GENERATED_STRING_TABLE =
            "Lcom/apkharden/generated/HardenStringTableConfig;"
        const val HARDEN_RUNTIME = "Lcom/apkharden/runtime/HardenRuntime;"
        const val PROTECTED_FIXTURE_STRING = "ApkHarden smoke fixture"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
