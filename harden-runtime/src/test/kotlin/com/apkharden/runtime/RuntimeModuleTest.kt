package com.apkharden.runtime

import android.app.Application
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeModuleTest {
    @Test fun `runtime module uses expected version`() {
        assertEquals("0.1.0", RuntimeBuildInfo.VERSION)
    }

    @Test fun `install entry point is callable as a JVM static method`() {
        val install = HardenRuntime::class.java.getDeclaredMethod(
            "install",
            Application::class.java,
            HardenConfig::class.java,
        )

        assertTrue(Modifier.isStatic(install.modifiers))
    }

    @Test fun `generated config loader reads static HardenConfig instance`() {
        val config = GeneratedConfigLoader.load(
            "com.apkharden.runtime.fixture.TestGeneratedConfig",
            requireNotNull(javaClass.classLoader),
        )

        assertEquals("com.example.app", config.applicationId)
        assertEquals("release", config.variantName)
    }
}
