package com.apkharden.packager.ui.theme

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.prefs.Preferences

class AppearanceStoreTest {
    private val preferences = Preferences.userRoot().node("/com/apkharden/packager/test/appearance-${UUID.randomUUID()}")

    @AfterEach
    fun cleanUp() {
        preferences.removeNode()
        preferences.flush()
    }

    @Test
    fun `first launch uses original green and light appearance without writing preferences`() {
        assertEquals(AppearanceSettings(ThemeSkin.ORIGINAL, AppearanceMode.LIGHT), AppearanceStore(preferences).load())
        assertTrue(preferences.keys().isEmpty())
    }

    @Test
    fun `all skin and mode selections survive a new store instance`() {
        for (skin in ThemeSkin.entries) for (mode in AppearanceMode.entries) {
            val expected = AppearanceSettings(skin, mode)
            AppearanceStore(preferences).save(expected)
            assertEquals(expected, AppearanceStore(preferences).load())
        }
    }

    @Test
    fun `removed or corrupt identifiers fall back independently`() {
        preferences.put("selection", "missing-skin|dark")
        assertEquals(AppearanceSettings(ThemeSkin.ORIGINAL, AppearanceMode.DARK), AppearanceStore(preferences).load())
        preferences.put("selection", "mint-grape|missing-mode")
        assertEquals(AppearanceSettings(ThemeSkin.MINT_GRAPE, AppearanceMode.LIGHT), AppearanceStore(preferences).load())
        preferences.put("selection", "corrupt")
        assertEquals(AppearanceSettings(), AppearanceStore(preferences).load())
    }

    @Test
    fun `restoring defaults persists original skin without clearing unrelated settings`() {
        preferences.put("unrelated-setting", "keep")
        val store = AppearanceStore(preferences)
        store.save(AppearanceSettings(ThemeSkin.PEACH_CREAM, AppearanceMode.DARK))
        store.save(AppearanceSettings())
        assertEquals(AppearanceSettings(), AppearanceStore(preferences).load())
        assertEquals("keep", preferences.get("unrelated-setting", null))
    }

    @Test
    fun `system mode follows the system and explicit appearance overrides it`() {
        assertFalse(AppearanceMode.SYSTEM.isDark(false))
        assertTrue(AppearanceMode.SYSTEM.isDark(true))
        assertFalse(AppearanceMode.LIGHT.isDark(true))
        assertTrue(AppearanceMode.DARK.isDark(false))
    }
}
