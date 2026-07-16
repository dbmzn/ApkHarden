package com.apkharden.packager.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AdbDeviceServiceTest {
    @Test
    fun `intent command keeps every user value as an individual argument`() {
        val args = buildStartIntentArgs(
            IntentLaunchRequest(
                action = "android.intent.action.VIEW",
                dataUri = "demo://detail?id=1&name=a b",
                packageName = "com.demo.app",
                component = "com.demo.app/.MainActivity",
                categories = listOf("android.intent.category.BROWSABLE", "android.intent.category.DEFAULT"),
            )
        )

        assertEquals(
            listOf(
                "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
                "-d", "demo://detail?id=1&name=a b",
                "-c", "android.intent.category.BROWSABLE",
                "-c", "android.intent.category.DEFAULT",
                "-p", "com.demo.app",
                "-n", "com.demo.app/.MainActivity",
            ),
            args,
        )
        assertFalse(args.any { it.contains(";") })
    }

    @Test
    fun `intent command rejects a blank action`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildStartIntentArgs(IntentLaunchRequest(action = "  "))
        }
    }
}
