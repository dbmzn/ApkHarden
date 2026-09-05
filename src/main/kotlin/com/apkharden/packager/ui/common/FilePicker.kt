package com.apkharden.packager.ui.common

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NFDFilterItem
import org.lwjgl.util.nfd.NativeFileDialog.NFD_FreePath
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Init
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OpenDialog
import org.lwjgl.util.nfd.NativeFileDialog.NFD_PickFolder
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit
import org.lwjgl.util.nfd.NativeFileDialog.NFD_SaveDialog

/**
 * OS 原生文件对话框（Windows 上是现代 IFileOpenDialog，含快速访问栏），LWJGL NFD 驱动。
 * 打开系统文件选择器并返回所选路径，取消则返回 null。
 */
fun pickFile(
    filterName: String? = null,
    save: Boolean = false,
    extensions: List<String> = emptyList(),
): String? {
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        return pickMacPath(filterName ?: "选择文件", save, extensions)
    }
    NFD_Init()
    try {
        MemoryStack.stackPush().use { stack ->
            val outPath = stack.mallocPointer(1)
            val filters = if (extensions.isNotEmpty()) {
                val items = NFDFilterItem.malloc(1, stack)
                items[0].name(stack.UTF8(filterName ?: "支持的文件"))
                    .spec(stack.UTF8(extensions.joinToString(",")))
                items
            } else null
            val result = if (save)
                NFD_SaveDialog(outPath, filters, null as CharSequence?, null as CharSequence?)
            else
                NFD_OpenDialog(outPath, filters, null as CharSequence?)
            if (result != NFD_OKAY) return null
            val path = outPath.getStringUTF8(0)
            NFD_FreePath(outPath.get(0))
            return path
        }
    } finally {
        NFD_Quit()
    }
}

fun pickDirectory(): String? {
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        return pickMacPath("选择文件夹", directory = true)
    }
    NFD_Init()
    try {
        MemoryStack.stackPush().use { stack ->
            val outPath = stack.mallocPointer(1)
            if (NFD_PickFolder(outPath, null as CharSequence?) != NFD_OKAY) return null
            val path = outPath.getStringUTF8(0)
            NFD_FreePath(outPath.get(0))
            return path
        }
    } finally {
        NFD_Quit()
    }
}

// AWT dispatches Cocoa panels to the macOS main thread; direct NFD calls from
// the Compose event thread abort the process when NSWindow is constructed.
private fun pickMacPath(
    title: String,
    save: Boolean = false,
    extensions: List<String> = emptyList(),
    directory: Boolean = false,
): String? {
    var selected: String? = null
    val showDialog = Runnable {
        val property = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(property)
        val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
        try {
            System.setProperty(property, directory.toString())
            if (extensions.isNotEmpty() && !directory) {
                dialog.setFilenameFilter { _, name ->
                    extensions.any { name.endsWith(".$it", ignoreCase = true) }
                }
            }
            dialog.isVisible = true
            selected = dialog.file?.let { File(dialog.directory, it).absolutePath }
        } finally {
            dialog.dispose()
            if (previous == null) System.clearProperty(property)
            else System.setProperty(property, previous)
        }
    }
    if (EventQueue.isDispatchThread()) showDialog.run()
    else EventQueue.invokeAndWait(showDialog)
    return selected
}
