package com.apkharden.packager

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.apkharden.packager.ui.HardenScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ApkHarden",
        state = rememberWindowState(width = 720.dp, height = 640.dp),
    ) {
        MaterialTheme { HardenScreen() }
    }
}
