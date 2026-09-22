package com.apkharden.packager.ui.barcode

import com.apkharden.packager.core.BarcodeTool
import com.apkharden.packager.ui.adb.pngClipboardFlavor
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.swing.ImageIcon

internal fun clipboardImage(contents: Transferable): BufferedImage {
    if (contents.isDataFlavorSupported(pngClipboardFlavor)) {
        return (contents.getTransferData(pngClipboardFlavor) as InputStream).use(BarcodeTool::readImage)
    }
    if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val source = ImageIcon(contents.getTransferData(DataFlavor.imageFlavor) as Image).image
        val width = source.getWidth(null)
        val height = source.getHeight(null)
        require(width > 0 && height > 0 && width.toLong() * height <= 20_000_000) { "剪贴板图片过大或未加载完成" }
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().let { g -> try { g.drawImage(source, 0, 0, null) } finally { g.dispose() } }
        }
    }
    if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        val file = files?.firstOrNull() as? File ?: error("剪贴板没有图片文件")
        return BarcodeTool.readImage(file)
    }
    error("剪贴板没有图片，请先复制图片或截图")
}
internal fun readClipboardImage(): BufferedImage = clipboardImage(
    Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: error("剪贴板为空"),
)
