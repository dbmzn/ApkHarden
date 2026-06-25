package com.apkharden.packager.ui.tool

import androidx.compose.runtime.Composable

/** 一个工具箱条目。加新工具 = 实现一个 Tool 并加进 App 的列表，不碰外壳。 */
interface Tool {
    val id: String
    val title: String
    @Composable fun Content()
}
