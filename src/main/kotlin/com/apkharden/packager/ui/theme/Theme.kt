package com.apkharden.packager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared roles for content, forms, feature panels and inspection results. */
data class SemanticColors(
    val tierIssue: Color, val tierReview: Color,
    val sevHigh: Color, val sevMedium: Color, val sevLow: Color, val sevInfo: Color,
    val advice: Color, val cardBg: Color, val cardBorder: Color, val subtle: Color,
    val formBg: Color, val accentBg: Color, val onAccent: Color,
    val secondaryBg: Color, val onSecondaryPanel: Color, val selectionBg: Color,
)

val LocalSemantic = staticCompositionLocalOf<SemanticColors> {
    error("SemanticColors not provided — wrap content in AppTheme")
}

private val AppTypography = Typography(
    h4 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 44.sp),
    h5 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    h6 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 29.sp),
    subtitle1 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    subtitle2 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    body1 = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    body2 = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    button = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.sp),
    caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
)

@Composable
fun AppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    skin: ThemeSkin = ThemeSkin.ORIGINAL,
    content: @Composable () -> Unit,
) {
    val palette = skin.palette(dark)
    CompositionLocalProvider(LocalSemantic provides palette.semantic) {
        MaterialTheme(
            colors = palette.colors,
            typography = AppTypography,
            shapes = Shapes(RoundedCornerShape(20.dp), RoundedCornerShape(24.dp), RoundedCornerShape(32.dp)),
            content = content,
        )
    }
}
