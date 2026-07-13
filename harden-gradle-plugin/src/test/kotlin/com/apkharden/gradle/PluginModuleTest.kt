package com.apkharden.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluginModuleTest {
    @Test fun `plugin module uses expected version`() {
        assertEquals("0.1.0", PluginBuildInfo.VERSION)
        assertEquals(17, java.lang.Runtime.version().feature())
    }
}
