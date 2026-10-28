package com.apkharden.packager

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
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
        var developerToolsShortcut by remember { mutableStateOf(0) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "ApkHarden",
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed)) {
                    developerToolsShortcut++
                    true
                } else false
            },
            // Open centered on the screen instead of the platform default (top-left / cascading).
            state = rememberWindowState(
                width = 1180.dp, height = 820.dp,
                position = WindowPosition(Alignment.Center),
            ),
        ) {
            window.minimumSize = java.awt.Dimension(1000, 680)
            App(openDeveloperToolsSignal = developerToolsShortcut)
        }
    }
}
