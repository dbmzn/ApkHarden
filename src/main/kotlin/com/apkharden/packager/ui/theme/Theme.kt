package com.apkharden.packager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Shapes

/**
 * 语义色（随主题切换）：分层(需整改/需自查)、严重度徽章、建议文字、卡片底/边。
 * 屏幕统一从 [LocalSemantic] 取，不再硬编码颜色，保证深浅两套一致。
 */
data class SemanticColors(
    val tierIssue: Color,
    val tierReview: Color,
    val sevHigh: Color,
    val sevMedium: Color,
    val sevLow: Color,
    val sevInfo: Color,
    val advice: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val subtle: Color,
)

val LocalSemantic = staticCompositionLocalOf<SemanticColors> {
    error("SemanticColors not provided — wrap content in AppTheme")
}

private val DarkSemantic = SemanticColors(
    tierIssue = Color(0xFFF1696B), tierReview = Color(0xFF5B9BF0),
    sevHigh = Color(0xFFF1696B), sevMedium = Color(0xFFF6C14B),
    sevLow = Color(0xFFD6B24A), sevInfo = Color(0xFF8A93A3),
    advice = Color(0xFF33B88F),
    cardBg = Color(0xFF1B202B), cardBorder = Color(0xFF2A3040), subtle = Color(0xFF9AA0AD),
)

private val LightSemantic = SemanticColors(
    tierIssue = Color(0xFFDC2626), tierReview = Color(0xFF2563EB),
    sevHigh = Color(0xFFDC2626), sevMedium = Color(0xFFC97A00),
    sevLow = Color(0xFFA8730A), sevInfo = Color(0xFF6B7280),
    advice = Color(0xFF2E7D52),
    cardBg = Color(0xFFFFFFFF), cardBorder = Color(0xFFE9EBEF), subtle = Color(0xFF6B7280),
)

private val DarkColors = darkColors(
    primary = Color(0xFF2DD4A7), onPrimary = Color(0xFF06281F),
    secondary = Color(0xFF5B9BF0),
    background = Color(0xFF161922), onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF1B202B), onSurface = Color(0xFFE6E8EE),
    error = Color(0xFFF1696B),
)

private val LightColors = lightColors(
    primary = Color(0xFF4F46E5), onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2563EB),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF1C2128),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C2128),
    error = Color(0xFFDC2626),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

private val AppTypography = Typography(
    h6 = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    subtitle1 = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp),
    subtitle2 = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    body1 = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
    body2 = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    button = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    caption = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSemantic provides if (dark) DarkSemantic else LightSemantic
    ) {
        MaterialTheme(
            colors = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
