package com.apkharden.runtime

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AntiDebugTest {
    @Test fun `TracerPid zero is not detected`() {
        assertFalse(AntiDebug.hasTracer(sequenceOf("Name:\tapp", "TracerPid:\t0")))
    }

    @Test fun `positive TracerPid is detected`() {
        assertTrue(AntiDebug.hasTracer(sequenceOf("TracerPid:\t132")))
    }

    @Test fun `one-time initializer runs action once`() {
        val count = AtomicInteger()
        val initializer = OneTimeInitializer()
        repeat(5) { initializer.runOnce { count.incrementAndGet() } }
        assertEquals(1, count.get())
    }

    @Test fun `failed initialization can be retried`() {
        val initializer = OneTimeInitializer()
        runCatching { initializer.runOnce { error("first") } }
        var completed = false
        initializer.runOnce { completed = true }
        assertTrue(completed)
    }
}
