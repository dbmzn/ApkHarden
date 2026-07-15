package com.apkharden.packager.ui.common

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
 * 加固与隐私扫描共用。返回所选路径，取消则 null。
 */
fun pickFile(
    filterName: String? = null,
    save: Boolean = false,
    extensions: List<String> = emptyList(),
): String? {
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
