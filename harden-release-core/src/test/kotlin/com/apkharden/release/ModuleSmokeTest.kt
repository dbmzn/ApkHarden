package com.apkharden.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleSmokeTest {
    @Test fun `release core runs on Java 17`() {
        assertEquals(17, Runtime.version().feature())
    }
}
