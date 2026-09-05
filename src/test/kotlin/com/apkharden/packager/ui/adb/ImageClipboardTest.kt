package com.apkharden.packager.ui.adb

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.FlavorTable
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class ImageClipboardTest {
    private fun sample(): ByteArray {
        val image = BufferedImage(17, 11, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(5, 3, 0x804080c0.toInt())
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    @Test fun `encoded PNG can be read repeatedly without losing bytes or transparency`() {
        val bytes = sample()
        val transferable = screenshotTransferable(bytes, mac = true)
        repeat(2) {
            val actual = (transferable.getTransferData(pngClipboardFlavor) as InputStream).use { it.readBytes() }
            assertArrayEquals(bytes, actual)
            assertEquals(0x804080c0.toInt(), ImageIO.read(actual.inputStream()).getRGB(5, 3))
        }
        assertFalse(transferable.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertThrows(UnsupportedFlavorException::class.java) { transferable.getTransferData(DataFlavor.stringFlavor) }
    }

    @Test fun `other platforms keep the standard AWT image flavor`() {
        val transferable = screenshotTransferable(sample(), mac = false)
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertEquals(17, (transferable.getTransferData(DataFlavor.imageFlavor) as BufferedImage).width)
    }

    @Test @EnabledOnOs(OS.MAC)
    fun `macOS native PNG transfer contains PNG bytes instead of TIFF`() {
        // Exercise the real JDK native-format translation without changing the user's clipboard.
        val bytes = sample()
        val transferable = screenshotTransferable(bytes, mac = true)
        val type = Class.forName("sun.awt.datatransfer.DataTransferer")
        val converter = type.getMethod("getInstance").invoke(null)
        val formats = type.getMethod("getFormatsForTransferable", Transferable::class.java, FlavorTable::class.java)
            .invoke(converter, transferable, SystemFlavorMap.getDefaultFlavorMap()) as Map<*, *>
        assertEquals(1, formats.size)
        val nativeFormat = formats.keys.single() as Long
        assertEquals(8L, nativeFormat) // CDataTransferer.CF_PNG
        val actual = type.getMethod("translateTransferable", Transferable::class.java, DataFlavor::class.java, java.lang.Long.TYPE)
            .invoke(converter, transferable, pngClipboardFlavor, nativeFormat) as ByteArray
        assertArrayEquals(bytes, actual)
        assertEquals(11, ImageIO.read(actual.inputStream()).height)
    }
}
