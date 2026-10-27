package com.apkharden.packager.ui.theme

import java.util.prefs.Preferences

enum class AppearanceMode(val id: String, val title: String) {
    LIGHT("light", "浅色"), DARK("dark", "深色"), SYSTEM("system", "跟随系统");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }
}

data class AppearanceSettings(
    val skin: ThemeSkin = ThemeSkin.ORIGINAL,
    val mode: AppearanceMode = AppearanceMode.LIGHT,
)

/** Kept separate from the signing profile; selecting a skin never touches credentials. */
internal class AppearanceStore(
    private val preferences: Preferences = Preferences.userRoot().node("/com/apkharden/packager/appearance"),
) {
    fun load(): AppearanceSettings = runCatching {
        val parts = preferences.get("selection", "").split('|')
        AppearanceSettings(
            skin = ThemeSkin.entries.find { it.id == parts.getOrNull(0) } ?: ThemeSkin.ORIGINAL,
            mode = AppearanceMode.entries.find { it.id == parts.getOrNull(1) } ?: AppearanceMode.LIGHT,
        )
    }.getOrDefault(AppearanceSettings())

    fun save(settings: AppearanceSettings) {
        // A single value prevents partially saved skin/mode combinations.
        preferences.put("selection", "${settings.skin.id}|${settings.mode.id}")
        preferences.flush()
    }
}
