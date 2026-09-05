package com.apkharden.packager.verification

import com.apkharden.packager.core.*
import com.apkharden.packager.device.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Opt-in tests operate only on our dedicated verification package, never business apps. */
@EnabledIfEnvironmentVariable(named = "APK_HARDEN_DEVICE_TEST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceVerificationTest {
    private val output = File("build/verification").absoluteFile
    private val input = File(output, "verification.apk")
    private val hardened = File(output, "verification-hardened.apk")
    private val pkg = "com.apkharden.verification"
    private lateinit var device: AndroidDevice

    @BeforeAll fun connectedDevice() {
        device = AdbDeviceService.listDevices().single { it.serial == System.getenv("APK_HARDEN_DEVICE_TEST") }
        assertEquals("device", device.state)
        assertTrue(input.isFile, "Run scripts/build-verification-fixture.py first")
        assertEquals(pkg, ApkInspector.inspect(input).packageName)
    }

    @Test @Order(1) fun `full hardening preserves identity and signs encrypted multidex payload`() {
        val result = ProductionHardenPipeline.harden(input, hardened,
            File("src/test/resources/test.jks"), "123456", "test", "123456")
        assertTrue(result.reportFile.isFile)
        val original = ApkInspector.inspect(input)
        val final = ApkInspector.inspect(hardened)
        assertEquals(original.packageName, final.packageName)
        assertEquals(original.versionCode, final.versionCode)
        val signature = ApkInspector.signature(hardened)
        assertTrue(signature.verified)
        assertTrue(signature.schemes.containsAll(listOf("V1", "V2", "V3")))
        ZipFile(hardened).use { zip ->
            assertNotNull(zip.getEntry(Constants.encryptedDexEntry(0)))
            assertNull(zip.getEntry("classes2.dex"))
        }
    }

    @Test @Order(2) fun `all static analysis tools process real original and hardened APKs`() {
        val manifest = ManifestExplorer.analyze(input)
        assertEquals(pkg, manifest.packageName)
        assertTrue(manifest.deepLinks.contains("apkharden-test://verify"))
        assertTrue(manifest.permissions.contains("android.permission.POST_NOTIFICATIONS"))
        File(output, "manifest.txt").writeText(manifest.toString())
        val report = ApkToolbox.analyze(hardened)
        assertEquals(0, report.blockerCount)
        File(output, "inspection.txt").writeText(report.toString())
        val compare = ApkToolbox.compare(input, hardened)
        assertTrue(compare.signaturesMatch)
        assertTrue(compare.changes.isNotEmpty())
        val archive = ApkArchiveExplorer.analyze(hardened)
        assertTrue(archive.entries.isNotEmpty())
        val size = ApkArchiveExplorer.compare(input, hardened)
        assertTrue(size.changes.isNotEmpty())
        File(output, "comparison.txt").writeText(compare.toString() + "\n" + size)
    }

    @Test @Order(3) fun `original then hardened APK install and cold launch on device`() {
        assertTrue(AdbDeviceService.verifyLaunch(input, pkg, device, waitSeconds = 3).passed)
        val result = AdbDeviceService.verifyLaunch(hardened, pkg, device, waitSeconds = 5)
        File(output, "launch.txt").writeText(result.toString())
        assertTrue(result.passed, result.toString())
        val log = AdbDeviceService.logcat(device, pkg, "ACTIVITY_OK")
        assertTrue(log.contains("ACTIVITY_OK"), log)
        File(output, "fixture-log.txt").writeText(log)
    }

    @Test @Order(4) fun `device capture recording and diagnostics export usable files`() {
        val capture = AdbDeviceService.captureScreenshot(device)
        val image = ImageIO.read(ByteArrayInputStream(capture))
        assertNotNull(image)
        assertTrue(image.width > 0 && image.height > 0)
        File(output, "device-screenshot.png").writeBytes(capture)
        val screen = AdbDeviceService.screenSize(device)
        assertTrue(screen.width > 0 && screen.height > 0)
        val video = File(output, "device-recording.mp4")
        val mode = AdbDeviceService.recordScreen(device, video, 5)
        assertTrue(video.length() > 100)
        assertTrue(String(video.readBytes().take(64).toByteArray(), Charsets.ISO_8859_1).contains("ftyp"))
        File(output, "recording-mode.txt").writeText(mode.name)
        val diagnostic = AdbDeviceService.collectDiagnostics(device, pkg, File(output, "diagnostics.zip"))
        ZipFile(diagnostic).use { zip ->
            assertEquals(12, zip.size())
            assertTrue(zip.getInputStream(zip.getEntry("package.txt")).bufferedReader().readText().contains(pkg))
        }
    }

    @Test @Order(5) fun `logs performance processes activity and deep link work`() {
        assertTrue(AdbDeviceService.processes(device, pkg).contains(pkg))
        assertTrue(AdbDeviceService.activityStack(device, pkg).contains(pkg))
        val performance = AdbDeviceService.performanceSnapshot(device, pkg)
        assertTrue(performance.contains("TOTAL PSS"), performance)
        File(output, "performance.txt").writeText(performance)
        val intent = AdbDeviceService.launchIntent(device, IntentLaunchRequest(dataUri = "apkharden-test://verify", packageName = pkg))
        assertTrue(intent.contains("Status: ok"), intent)
        File(output, "intent.txt").writeText(intent)
    }

    @Test @Order(6) fun `permission grant and data clear operate on verification package only`() {
        assertEquals("授权成功", AdbDeviceService.grantPermission(device, pkg, "android.permission.POST_NOTIFICATIONS"))
        assertEquals("Success", AdbDeviceService.clearData(device, pkg))
        assertTrue(AdbDeviceService.verifyLaunch(hardened, pkg, device, waitSeconds = 3, skipInstall = true).passed)
    }
}
