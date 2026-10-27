package com.apkharden.packager.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

class SkinPaletteTest {
    @Test
    fun `every skin keeps normal text readable on both appearances`() {
        for (skin in ThemeSkin.entries) for (dark in listOf(false, true)) {
            val (colors, sem) = skin.palette(dark)
            val pairs = mapOf(
                "primary button" to (colors.onPrimary to colors.primary),
                "secondary button" to (colors.onSecondary to colors.secondary),
                "page text" to (colors.onBackground to colors.background),
                "form text" to (colors.onSurface to sem.formBg),
                "form caption" to (sem.subtle to sem.formBg),
                "card caption" to (sem.subtle to sem.cardBg),
                "primary panel" to (sem.onAccent to sem.accentBg),
                "secondary panel" to (sem.onSecondaryPanel to sem.secondaryBg),
            )
            for ((role, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue(ratio >= 4.5, "${skin.id}, dark=$dark, $role contrast=$ratio")
            }
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val values = listOf(luminance(foreground), luminance(background)).sorted()
        return (values[1] + .05) / (values[0] + .05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        return .2126 * channel(color.red) + .7152 * channel(color.green) + .0722 * channel(color.blue)
    }
}
