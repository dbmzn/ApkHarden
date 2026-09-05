package com.apkharden.packager.ui.adb

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal val pngClipboardFlavor = DataFlavor("image/png;class=java.io.InputStream")

internal fun screenshotTransferable(bytes: ByteArray, mac: Boolean): Transferable {
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("截图格式无效")
    val png = bytes.copyOf()
    require(png.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) {
        "截图必须为 PNG 格式"
    }
    if (mac) {
        // JBR 17 converts imageFlavor into TIFF, even under the PNG native type.
        // Transfer encoded bytes directly so browsers receive a real public.png payload.
        val flavors = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
        flavors.setNativesForFlavor(pngClipboardFlavor, arrayOf("PNG"))
        flavors.addFlavorForUnencodedNative("PNG", pngClipboardFlavor)
    }
    return object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            if (mac) arrayOf(pngClipboardFlavor) else arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return if (flavor == pngClipboardFlavor) ByteArrayInputStream(png) else image
        }
    }
}

internal fun copyImageToClipboard(bytes: ByteArray) {
    val mac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(screenshotTransferable(bytes, mac), null)
}
