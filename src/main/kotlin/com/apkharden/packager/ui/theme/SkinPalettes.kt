package com.apkharden.packager.ui.theme

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color

/** Stable IDs are persisted; display names and enum order can change independently. */
enum class ThemeSkin(val id: String, val title: String, val description: String) {
    ORIGINAL("original", "经典绿青", "参考配色 · 浅绿与浅青"),
    LEMON_SKY("lemon-sky", "晴空柠檬", "柠檬黄与晴空蓝"),
    MINT_GRAPE("mint-grape", "薄荷葡萄", "薄荷绿与葡萄紫"),
    PEACH_CREAM("peach-cream", "蜜桃奶油", "蜜桃粉与奶油黄"),
}

internal data class SkinPalette(val colors: Colors, val semantic: SemanticColors)

internal fun ThemeSkin.palette(dark: Boolean): SkinPalette = when (this) {
    ThemeSkin.ORIGINAL -> if (dark) originalDark else originalLight
    ThemeSkin.LEMON_SKY -> if (dark) lemonDark else lemonLight
    ThemeSkin.MINT_GRAPE -> if (dark) mintDark else mintLight
    ThemeSkin.PEACH_CREAM -> if (dark) peachDark else peachLight
}

private fun palette(
    dark: Boolean,
    primary: Long, onPrimary: Long, secondary: Long, onSecondary: Long,
    background: Long, text: Long, subtle: Long, card: Long, form: Long, border: Long,
    accent: Long, onAccent: Long, secondaryPanel: Long, onSecondaryPanel: Long, selection: Long,
): SkinPalette {
    val error = Color(if (dark) 0xFFFFB4AB else 0xFFBA1A1A)
    val colors = if (dark) darkColors() else lightColors()
    return SkinPalette(
        colors.copy(
            primary = Color(primary), primaryVariant = Color(primary), onPrimary = Color(onPrimary),
            secondary = Color(secondary), secondaryVariant = Color(secondary), onSecondary = Color(onSecondary),
            background = Color(background), onBackground = Color(text),
            surface = Color(if (dark) card else background), onSurface = Color(text),
            error = error, onError = Color(if (dark) 0xFF601410 else 0xFFFFFFFF),
        ),
        SemanticColors(
            tierIssue = error, tierReview = Color(primary), sevHigh = error,
            sevMedium = Color(if (dark) 0xFFECC16D else 0xFF896012),
            sevLow = Color(if (dark) 0xFFD9C3A5 else 0xFF786243), sevInfo = Color(subtle),
            advice = Color(if (dark) 0xFFA6D7B3 else 0xFF39734E),
            cardBg = Color(card), cardBorder = Color(border), subtle = Color(subtle), formBg = Color(form),
            accentBg = Color(accent), onAccent = Color(onAccent),
            secondaryBg = Color(secondaryPanel), onSecondaryPanel = Color(onSecondaryPanel),
            selectionBg = Color(selection),
        ),
    )
}

// The original green/cyan design is the first-run default in both appearances.
private val originalLight = palette(
    dark = false, primary = 0xFF4D662B, onPrimary = 0xFFFFFFFF, secondary = 0xFF326B67, onSecondary = 0xFFFFFFFF,
    background = 0xFFF8FAEF, text = 0xFF1B2115, subtle = 0xFF606555,
    card = 0xFFF0F2E7, form = 0xFFE4E7DB, border = 0xFFCDD1C1,
    accent = 0xFFD2EEA6, onAccent = 0xFF1A2B09,
    secondaryPanel = 0xFFBCEBE7, onSecondaryPanel = 0xFF123B39, selection = 0xFFDEE8C8,
)
private val originalDark = palette(
    dark = true, primary = 0xFFB5D58C, onPrimary = 0xFF23360E, secondary = 0xFFA1D4CE, onSecondary = 0xFF123B39,
    background = 0xFF11150E, text = 0xFFE2E6D8, subtle = 0xFFC0C5B4,
    card = 0xFF1C2017, form = 0xFF303529, border = 0xFF454B3C,
    accent = 0xFF354F16, onAccent = 0xFFD2EEA6,
    secondaryPanel = 0xFF1C4B49, onSecondaryPanel = 0xFFBCEBE7, selection = 0xFF3F4A30,
)
private val lemonLight = palette(
    dark = false, primary = 0xFF2465D7, onPrimary = 0xFFFFFFFF, secondary = 0xFF245B76, onSecondary = 0xFFFFFFFF,
    background = 0xFFFFFEF7, text = 0xFF192C3A, subtle = 0xFF576A73,
    card = 0xFFF0F5E9, form = 0xFFE8EEDC, border = 0xFFC9D4CB,
    accent = 0xFFFFF08A, onAccent = 0xFF383717,
    secondaryPanel = 0xFFAFE4FC, onSecondaryPanel = 0xFF103D54, selection = 0xFFF6ECA4,
)
private val lemonDark = palette(
    dark = true, primary = 0xFFA9D0FF, onPrimary = 0xFF112F5A, secondary = 0xFFC2EDFF, onSecondary = 0xFF103D54,
    background = 0xFF121B23, text = 0xFFE7F2F7, subtle = 0xFFBDCED9,
    card = 0xFF1F3039, form = 0xFF2B3C44, border = 0xFF425965,
    accent = 0xFF58501A, onAccent = 0xFFFFF2A5,
    secondaryPanel = 0xFF17465C, onSecondaryPanel = 0xFFC2EDFF, selection = 0xFF514B29,
)
private val mintLight = palette(
    dark = false, primary = 0xFF6246CE, onPrimary = 0xFFFFFFFF, secondary = 0xFF216C43, onSecondary = 0xFFFFFFFF,
    background = 0xFFF9FFF9, text = 0xFF20352F, subtle = 0xFF53665B,
    card = 0xFFEFF5DE, form = 0xFFE4EDD7, border = 0xFFC6D7C9,
    accent = 0xFFB7F2C8, onAccent = 0xFF183E2B,
    secondaryPanel = 0xFFDBCBFF, onSecondaryPanel = 0xFF36205E, selection = 0xFFCDEBD4,
)
private val mintDark = palette(
    dark = true, primary = 0xFFD1BCFF, onPrimary = 0xFF342058, secondary = 0xFFA6E5BD, onSecondary = 0xFF183E2B,
    background = 0xFF141C19, text = 0xFFE4F3E9, subtle = 0xFFB9D0C1,
    card = 0xFF26382B, form = 0xFF304536, border = 0xFF496153,
    accent = 0xFF24573D, onAccent = 0xFFC6FBD7,
    secondaryPanel = 0xFF48316F, onSecondaryPanel = 0xFFEADBFF, selection = 0xFF345842,
)
private val peachLight = palette(
    dark = false, primary = 0xFFBB3F61, onPrimary = 0xFFFFFFFF, secondary = 0xFF7C5B14, onSecondary = 0xFFFFFFFF,
    background = 0xFFFFFCF5, text = 0xFF3B2831, subtle = 0xFF725D65,
    card = 0xFFFFF2E3, form = 0xFFF4E7D9, border = 0xFFD9C9C0,
    accent = 0xFFFFC4D4, onAccent = 0xFF5C2034,
    secondaryPanel = 0xFFFFEA99, onSecondaryPanel = 0xFF4C3C12, selection = 0xFFF5D3DE,
)
private val peachDark = palette(
    dark = true, primary = 0xFFFFACC4, onPrimary = 0xFF5A1930, secondary = 0xFFF4D980, onSecondary = 0xFF4C3C12,
    background = 0xFF24171D, text = 0xFFF9E7EC, subtle = 0xFFDCC0C9,
    card = 0xFF3B2A26, form = 0xFF4A3532, border = 0xFF644350,
    accent = 0xFF713146, onAccent = 0xFFFFDBE6,
    secondaryPanel = 0xFF5A491D, onSecondaryPanel = 0xFFFFF0AF, selection = 0xFF633A48,
)
