package com.bilipai.desktop.appearance

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopWindowsLiquidGlassMigrationTest {
    @TempDir lateinit var root: Path

    @Test fun firstWindowsMigrationEnablesOriginalGlassAndPersistsItsMarkTogether(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        val prefs = DesktopThemePrefs(store)
        prefs.ensureMigrated()
        assertTrue(prefs.initialSettings().liquidGlassEnabled)
        val document = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        val canonical = document.getValue("settings").jsonObject
        assertEquals(JsonPrimitive(true), canonical["android_native_liquid_glass_enabled"])
        assertEquals(JsonPrimitive(true), canonical["windows_liquid_glass_default_v1"])
    }

    @Test fun manualOffAfterMigrationSurvivesNewPreferenceOwnerAndRepeatedStartup(): Unit = runBlocking {
        val prefs = DesktopThemePrefs(DesktopPluginStore(root))
        prefs.ensureMigrated()
        prefs.setLiquidGlassEnabled(false)
        val before = Files.readString(root.resolve("plugin-settings.json"))
        val restarted = DesktopThemePrefs(DesktopPluginStore(root))
        restarted.ensureMigrated()
        assertFalse(restarted.initialSettings().liquidGlassEnabled)
        assertEquals(before, Files.readString(root.resolve("plugin-settings.json")))
        val persisted = Json.parseToJsonElement(before).jsonObject.getValue("settings").jsonObject
        assertEquals(JsonPrimitive(false), persisted["android_native_liquid_glass_enabled"])
        assertEquals(JsonPrimitive(true), persisted["windows_liquid_glass_default_v1"])
    }

    @Test fun existingThemeFontWallpaperAndCustomTuningArePreservedDuringOneAuthorizedMigration(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        val original = mapOf(
            "theme_selection_v1" to JsonPrimitive("MIUIX"),
            "theme_mode_v2" to JsonPrimitive(2),
            "dark_theme_style_v1" to JsonPrimitive(1),
            "app_language_v1" to JsonPrimitive(3),
            "app_font_size_preset" to JsonPrimitive(4),
            "md3_custom_color_hex" to JsonPrimitive("#A1B2C3"),
            "home_wallpaper_uri" to JsonPrimitive("file:///private-fixture/image.png"),
            "liquid_glass_strength" to JsonPrimitive(0.72),
            "future_original_key" to JsonPrimitive("preserve🙂"),
        )
        store.update("settings", original + ("android_native_liquid_glass_enabled" to JsonPrimitive(false)))
        val prefs = DesktopThemePrefs(store)
        prefs.ensureMigrated()
        assertTrue(prefs.initialSettings().liquidGlassEnabled)
        val after = store.preferences("settings")
        original.forEach { (key, value) -> assertEquals(value, after[key], "Existing preference $key") }
        prefs.setLiquidGlassEnabled(false)
        prefs.ensureMigrated()
        assertFalse(prefs.initialSettings().liquidGlassEnabled)
        original.forEach { (key, value) -> assertEquals(value, store.preferences("settings")[key]) }
    }
}
