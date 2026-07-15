package com.apkharden.packager

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.apkharden.packager.ui.App
import javax.swing.UIManager

fun main() {
    // Render the Swing file chooser with the native Windows look instead of the dated Metal theme.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "ApkHarden",
            // Open centered on the screen instead of the platform default (top-left / cascading).
            state = rememberWindowState(
                width = 880.dp, height = 680.dp,
                position = WindowPosition(Alignment.Center),
            ),
        ) {
            App()
        }
    }
}
