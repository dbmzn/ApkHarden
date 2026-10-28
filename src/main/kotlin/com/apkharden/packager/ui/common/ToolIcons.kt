package com.apkharden.packager.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small, consistent outline icons; no platform font or extra icon library required. */
object ToolIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()

    val Capture = icon("Capture", "M4 7H7L9 4H15L17 7H20Q22 7 22 9V19Q22 21 20 21H4Q2 21 2 19V9Q2 7 4 7Z M16 13A4 4 0 1 1 8 13A4 4 0 1 1 16 13")
    val Mirror = icon("Mirror", "M3 3H21V17H3Z M8 21H16 M12 17V21 M10 7L16 10L10 13Z")
    val Clipboard = icon("Clipboard", "M8 5H5V22H19V5H16 M8 2H16V7H8Z M8 12H16 M8 16H14")
    val Transfer = icon("Transfer", "M3 7H20L16 3 M21 17H4L8 21 M20 7L16 11 M4 17L8 13")
    val Code = icon("Code", "M3 3H9V9H3Z M15 3H21V9H15Z M3 15H9V21H3Z M15 15H18V18H21V21 M15 21V18 M21 13V15")
    val Debug = icon("Debug", "M4 4H20V20H4Z M7 8L10 11L7 14 M13 15H17")
    val Device = icon("Device", "M7 2H17Q19 2 19 4V20Q19 22 17 22H7Q5 22 5 20V4Q5 2 7 2Z M9 5H15 M10 18L12 20L16 16")
    val Shield = icon("Shield", "M12 2L21 6V12Q21 18 12 22Q3 18 3 12V6Z M8 12L11 15L16 9")
    val Inspect = icon("Inspect", "M10 3A7 7 0 1 1 10 17A7 7 0 1 1 10 3 M15 15L21 21 M7 10H13 M10 7V13")
    val Compare = icon("Compare", "M5 3H19 M15 0L19 3L15 6 M19 21H5 M9 18L5 21L9 24 M3 9H10V16H3Z M14 9H21V16H14Z")
    val Manifest = icon("Manifest", "M5 2H15L20 7V22H5Z M15 2V7H20 M8 11H16 M8 15H16 M8 19H13")
    val Folder = icon("Folder", "M3 5H9L12 8H21V20H3Z")
    val Chart = icon("Chart", "M3 3H21V21H3Z M7 16V12 M12 16V7 M17 16V10")
    val Key = icon("Key", "M9 6A6 6 0 1 0 9 18A6 6 0 0 0 14.5 14H18V18H21V14H23V10H14.5A6 6 0 0 0 9 6 M7 10A2 2 0 1 1 7 14A2 2 0 1 1 7 10")
    val Toolkit = icon("Toolkit", "M3 7H21V21H3Z M8 7V3H16V7 M3 12H21 M10 10H14V14H10Z")
    val Theme = icon("Theme", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3 M12 3V21 M15 5V19 M18 8V16")
    val Video = icon("Video", "M3 5H16V19H3Z M16 10L22 6V18L16 14")
}
