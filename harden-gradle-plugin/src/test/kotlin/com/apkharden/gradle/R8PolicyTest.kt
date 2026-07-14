package com.apkharden.gradle

import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class R8PolicyTest {
    @ParameterizedTest
    @EnumSource(R8Policy::class)
    fun `runtime support never mutates business R8 settings`(policy: R8Policy) {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("implementation")
        project.extensions.extraProperties["minifyEnabled"] = false
        project.extensions.extraProperties["shrinkResources"] = true
        project.extensions.extraProperties["proguardFiles"] = listOf("business-rules.pro")
        project.dependencies.add("implementation", "com.example:business:1.0")
        val extension = ApkHardenExtension(project.objects).apply {
            r8Policy.set(policy)
        }

        configureRuntimeSupport(project, extension)
        configureRuntimeSupport(project, extension)

        val dependencies = project.configurations.getByName("implementation").dependencies
        val coordinates = dependencies.filterIsInstance<ExternalModuleDependency>()
            .map { "${it.group}:${it.name}:${it.version}" }
        assertEquals(1, coordinates.count { it == PluginBuildInfo.RUNTIME_COORDINATE })
        assertTrue("com.example:business:1.0" in coordinates)
        assertEquals(false, project.extensions.extraProperties["minifyEnabled"])
        assertEquals(true, project.extensions.extraProperties["shrinkResources"])
        assertEquals(listOf("business-rules.pro"), project.extensions.extraProperties["proguardFiles"])
        assertEquals(policy, extension.r8Policy.get())
    }
}
