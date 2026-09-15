package com.apkharden.packager.device

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import javax.imageio.ImageIO

class WirelessAdbTest {
    @Test fun `validates ports and rejects shell or adb options`() {
        listOf("192.168.1.2:5555", "phone.local:37123", "[fe80::1%en0]:12345").forEach {
            assertEquals(it, wirelessAddress(" $it "))
        }
        listOf("-H:123", "x:0", "x:65536", "x:abc", "x:5555;reboot", "x:123\n--help", "x", "x:2 y:3").forEach {
            assertThrows(IllegalArgumentException::class.java) { wirelessAddress(it) }
        }
    }
    @Test fun `discovery keeps pairing and connection ports distinct and ignores malformed entries`() {
        val found = parseWirelessServices("""
            List of discovered mdns services
            phone _adb-tls-pairing._tcp 192.168.1.2:37111
            phone _adb-tls-connect._tcp 192.168.1.2:37222
            old _adb._tcp 192.168.1.3:5555
            invalid _adb._tcp nope
            printer _ipp._tcp 192.168.1.4:80
        """.trimIndent())
        assertEquals(3, found.size)
        assertEquals("192.168.1.2:37111", found[0].address)
        assertEquals("_adb-tls-connect._tcp", found[1].type)
    }
    @Test fun `legacy address requires an unambiguous wifi IPv4 address`() {
        assertEquals("192.168.11.233", wifiIpv4("inet 192.168.11.233/23 brd 192.168.11.255 scope global wlan0"))
        assertThrows(IllegalArgumentException::class.java) { wifiIpv4("no address") }
        assertThrows(IllegalArgumentException::class.java) { wifiIpv4("inet 192.168.1.1/24\ninet 192.168.1.2/24") }
    }
    @Test fun `wireless transport detection includes mdns serials`() {
        assertTrue(isWirelessSerial("192.168.1.1:5555"))
        assertTrue(isWirelessSerial("adb-phone._adb-tls-connect._tcp"))
        assertFalse(isWirelessSerial("XPH5T19327001807"))
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "APK_HARDEN_WIFI_TEST", matches = ".+")
    fun `legacy USB device connects over wifi reads clipboard and captures screenshot then returns to USB`() {
        val usb = AdbDeviceService.listDevices().single { it.serial == System.getenv("APK_HARDEN_WIFI_TEST") }
        var wifi: AndroidDevice? = null
        try {
            val serial = WirelessAdb.enableLegacy(usb)
            wifi = AdbDeviceService.listDevices().single { it.serial == serial }
            assertEquals("device", wifi.state)
            assertNotNull(ImageIO.read(AdbDeviceService.captureScreenshot(wifi).inputStream()))
            val clipboard = DeviceClipboardSession(wifi)
            try {
                val until = System.nanoTime() + 10_000_000_000L
                while (clipboard.state.text == null && System.nanoTime() < until) Thread.sleep(100)
                assertTrue(clipboard.state.connected)
                assertNotNull(clipboard.state.text, "请在手机先复制测试文字")
            } finally {
                clipboard.close()
                assertTrue(clipboard.awaitStopped(35_000))
            }
            WirelessAdb.disconnect(serial)
            assertEquals(serial, WirelessAdb.connect(serial))
        } finally {
            WirelessAdb.disableLegacy(wifi ?: usb)
        }
    }
}
