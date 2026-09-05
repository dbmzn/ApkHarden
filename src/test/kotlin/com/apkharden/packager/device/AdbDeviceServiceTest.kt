package com.apkharden.packager.device

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    @Test
    fun `recognizes Huawei missing screenrecord error`() {
        val error = IllegalStateException("/system/bin/sh: screenrecord: inaccessible or not found")

        assertTrue(isScreenRecordUnavailable(error))
        assertTrue(isScreenRecordUnavailable(IllegalStateException("Encoder failed (err=-38)")))
        assertFalse(isScreenRecordUnavailable(IllegalStateException("device offline")))
    }

    @Test
    fun `scrcpy recording is capped at thirty fps and keeps arguments separate`() {
        val output = File("C:/Downloads/recording with spaces.mp4")

        val args = buildScrcpyRecordingArgs("device serial", output, 15)

        assertEquals(
            listOf(
                "--serial", "device serial",
                "--record", output.absolutePath,
                "--time-limit", "15",
                "--no-window",
                "--no-audio",
                "--no-control",
                "--no-clipboard-autosync",
                "--max-fps", "30",
                "--max-size", "1920",
            ),
            args,
        )
    }

    @Test
    fun `scrcpy mirror keeps control enabled and uses a dedicated window title`() {
        val args = buildScrcpyMirrorArgs("device serial", "ApkHarden mirror", 900, 560)

        assertEquals(
            listOf(
                "--serial", "device serial",
                "--window-title", "ApkHarden mirror",
                "--window-width", "900",
                "--window-height", "560",
                "--shortcut-mod", "lctrl",
                "--no-audio",
                "--no-clipboard-autosync",
                "--max-fps", "30",
            ),
            args,
        )
        assertFalse("--no-control" in args)
        assertFalse("--no-window" in args)
    }

    @Test
    fun `large tablet mirror caps the video encoder before launch`() {
        val args = buildScrcpyMirrorArgs(
            serial = "tablet",
            windowTitle = "ApkHarden tablet",
            windowWidth = 2560,
            windowHeight = 1600,
            maxVideoSize = 1920,
        )

        assertEquals(listOf("--max-size", "1920"), args.takeLast(2))
    }

    @Test
    fun `device screen size prefers an override`() {
        val size = parseDeviceScreenSize(
            "Physical size: 1080x2336\nOverride size: 720x1557",
        )

        assertEquals(DeviceScreenSize(720, 1557), size)
    }

    @Test
    fun `current display size uses the rotated default display bounds`() {
        val output = """
            Display: mDisplayId=9
              overrideConfig={ winConfig={ mBounds=Rect(0, 0 - 1920, 1200) } }
            Display: mDisplayId=0 (organized)
              overrideConfig={ winConfig={ mBounds=Rect(0, 0 - 2560, 1600) } }
        """.trimIndent()

        assertEquals(DeviceScreenSize(2560, 1600), parseCurrentDisplaySize(output))
    }

    @Test
    fun `mirror window fills the available screen without exceeding it`() {
        val fitted = fitMirrorWindow(
            device = DeviceScreenSize(1080, 2340),
            maxWidth = 3072,
            maxHeight = 1768,
        )

        assertEquals(MirrorWindowSize(816, 1768), fitted)
        assertTrue(fitted.width <= 3072)
        assertTrue(fitted.height <= 1768)
    }

    @Test
    fun `compatibility frame is bounded and has even dimensions`() {
        val source = BufferedImage(1081, 2341, BufferedImage.TYPE_INT_ARGB)

        val normalized = normalizeRecordingFrame(source)

        assertTrue(maxOf(normalized.width, normalized.height) <= 1280)
        assertEquals(0, normalized.width % 2)
        assertEquals(0, normalized.height % 2)
    }

    @Test
    fun `compatibility frames are encoded as mp4`() {
        val directory = Files.createTempDirectory("apkharden-recording-test").toFile()
        val output = File(directory, "recording.mp4")
        try {
            val frames = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
                BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR).apply {
                    createGraphics().let { graphics ->
                        graphics.color = color
                        graphics.fillRect(0, 0, width, height)
                        graphics.dispose()
                    }
                }
            }

            encodeRecordingFrames(frames, output, 4)

            assertTrue(output.length() > 100)
            assertTrue(output.readBytes().copyOfRange(4, 8).contentEquals("ftyp".toByteArray()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `records mp4 on an explicitly selected real device`() {
        val serial = System.getenv("APK_HARDEN_TEST_DEVICE").orEmpty()
        assumeTrue(serial.isNotBlank(), "Set APK_HARDEN_TEST_DEVICE to run the real-device recording check")
        val directory = Files.createTempDirectory("apkharden-real-recording-test").toFile()
        val output = File(directory, "recording.mp4")
        try {
            val mode = AdbDeviceService.recordScreen(
                AndroidDevice(serial = serial, state = "device", model = "real-device"),
                output,
                seconds = 3,
            )

            assertTrue(output.length() > 100)
            assertTrue(output.readBytes().copyOfRange(4, 8).contentEquals("ftyp".toByteArray()))
            assertTrue(mode in ScreenRecordingMode.entries)
            System.getenv("APK_HARDEN_EXPECTED_RECORDING_MODE")?.takeIf(String::isNotBlank)?.let {
                assertEquals(ScreenRecordingMode.valueOf(it), mode)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
