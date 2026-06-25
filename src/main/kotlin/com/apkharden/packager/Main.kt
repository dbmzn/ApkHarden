package com.apkharden.packager

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.apkharden.packager.ui.harden.HardenScreen
import javax.swing.UIManager

fun main() {
    // Render the Swing file chooser with the native Windows look instead of the dated Metal theme.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "ApkHarden",
            state = rememberWindowState(width = 720.dp, height = 640.dp),
        ) {
            MaterialTheme { HardenScreen() }
        }
    }
}
