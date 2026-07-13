package com.apkharden.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuntimeModuleTest {
    @Test fun `runtime module uses expected version`() {
        assertEquals("0.1.0", RuntimeBuildInfo.VERSION)
    }
}
