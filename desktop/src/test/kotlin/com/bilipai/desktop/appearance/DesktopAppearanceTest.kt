package com.bilipai.desktop.appearance

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.android.purebilibili.core.store.ThemeModeRoleOverrides
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopAppearanceTest {
    @TempDir lateinit var root: Path
    private fun values(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*entries))

    @Test fun absentKeysKeepOriginalNewUserMaterialAndSystemDefaults() {
        val settings = decodeDesktopThemeSettings(JsonObject(emptyMap()))
        assertEquals(AppUiStyle.MATERIAL3, settings.uiStyle)
        assertEquals(AppThemeMode.FOLLOW_SYSTEM, settings.themeMode)
        assertEquals(AppLanguage.FOLLOW_SYSTEM, settings.appLanguage)
        assertEquals(PaletteStyle.TonalSpot, settings.colorStyle)
        assertEquals(ColorSpec.SpecVersion.SPEC_2025, settings.colorSpec)
    }
    @Test fun legacyIosAndRemovedVariantFollowExactOriginalMigrationPolicy() {
        assertEquals(AppUiStyle.MIUIX, decodeDesktopThemeSettings(values("ui_preset" to JsonPrimitive(0), "android_native_variant_v1" to JsonPrimitive(0))).uiStyle)
        assertEquals(AppUiStyle.MATERIAL3, decodeDesktopThemeSettings(values("ui_preset" to JsonPrimitive(1), "android_native_variant_v1" to JsonPrimitive(2))).uiStyle)
        assertEquals(AppUiStyle.MIUIX, decodeDesktopThemeSettings(values("ui_preset" to JsonPrimitive(400))).uiStyle)
    }
    @Test fun canonicalStyleWinsAndInvalidCanonicalFallsBackToLegacy() {
        assertEquals(AppUiStyle.MATERIAL3, decodeDesktopThemeSettings(values("theme_selection_v1" to JsonPrimitive("MATERIAL3"), "ui_preset" to JsonPrimitive(0))).uiStyle)
        assertEquals(AppUiStyle.MIUIX, decodeDesktopThemeSettings(values("theme_selection_v1" to JsonPrimitive("IOS"), "ui_preset" to JsonPrimitive(0))).uiStyle)
    }
    @Test fun legacyAmoledSeparatesModeAndStyleButExplicitStyleWins() {
        val legacy = decodeDesktopThemeSettings(values("theme_mode_v2" to JsonPrimitive(3)))
        assertEquals(AppThemeMode.DARK, legacy.themeMode); assertEquals(DarkThemeStyle.AMOLED, legacy.darkThemeStyle)
        assertEquals(DarkThemeStyle.DEFAULT, decodeDesktopThemeSettings(values("theme_mode_v2" to JsonPrimitive(3), "dark_theme_style_v1" to JsonPrimitive(0))).darkThemeStyle)
    }
    @Test fun oldWindowsDarkOnlyMigratesWhenActuallyPresent() {
        assertEquals(AppThemeMode.DARK, decodeDesktopThemeSettings(JsonObject(emptyMap()), legacyWindowsDark = true).themeMode)
        assertEquals(AppThemeMode.LIGHT, decodeDesktopThemeSettings(JsonObject(emptyMap()), legacyWindowsDark = false).themeMode)
        assertEquals(AppThemeMode.FOLLOW_SYSTEM, decodeDesktopThemeSettings(JsonObject(emptyMap()), legacyWindowsDark = null).themeMode)
    }
    @Test fun canonicalSettingsWinOverStartupCacheAndWindowsMigration() {
        val canonical = values("theme_mode_v2" to JsonPrimitive(0), "app_language_v1" to JsonPrimitive(3))
        val cache = values("theme_mode" to JsonPrimitive(2), "app_language" to JsonPrimitive(1))
        val state = decodeDesktopThemeSettings(canonical, cache, true)
        assertEquals(AppThemeMode.FOLLOW_SYSTEM, state.themeMode); assertEquals(AppLanguage.ENGLISH, state.appLanguage)
    }
    @Test fun migrationRemovesOldKeysInOneWriteAndRetainsUnknownFields(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        store.update("settings", mapOf("ui_preset" to JsonPrimitive(0), "android_native_variant_v1" to JsonPrimitive(1), "future_key" to JsonPrimitive("原文🙂")))
        val prefs = DesktopThemePrefs(store); prefs.ensureMigrated()
        val current = store.preferences("settings")
        assertEquals("MIUIX", current["theme_selection_v1"]?.jsonPrimitive?.content)
        assertFalse("ui_preset" in current); assertFalse("android_native_variant_v1" in current)
        assertEquals("原文🙂", current["future_key"]?.jsonPrimitive?.content)
        val first = Files.readString(root.resolve("plugin-settings.json")); prefs.ensureMigrated()
        assertEquals(first, Files.readString(root.resolve("plugin-settings.json")))
    }
    @Test fun themeModeSwitchPreservesLegacyAmoledPreference(): Unit = runBlocking {
        val store = DesktopPluginStore(root); store.update("settings", mapOf("theme_mode_v2" to JsonPrimitive(3)))
        val prefs = DesktopThemePrefs(store); prefs.setThemeMode(AppThemeMode.LIGHT)
        assertEquals(DarkThemeStyle.AMOLED, prefs.initialSettings().darkThemeStyle)
        assertEquals(AppThemeMode.LIGHT, prefs.initialSettings().themeMode)
    }
    @Test fun sharedFacadeChangeRefreshesAppearanceAndPersistsOtherNamespaces(): Unit = runBlocking {
        val store = DesktopPluginStore(root); val prefs = DesktopThemePrefs(store)
        DesktopPluginStore(root).update("search_privacy", mapOf("privacy_mode" to JsonPrimitive(true)))
        DesktopThemePrefs(DesktopPluginStore(root)).setAppLanguage(AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, prefs.settings.first().appLanguage)
        prefs.setUiStyle(AppUiStyle.MIUIX)
        assertEquals(true, DesktopPluginStore(root).preferences("search_privacy")["privacy_mode"]?.jsonPrimitive?.boolean)
    }
    @Test fun customSeedAndDpiUseOriginalNormalization(): Unit = runBlocking {
        val prefs = DesktopThemePrefs(DesktopPluginStore(root)); prefs.setCustomColor(" 00abEf "); prefs.setDpiOverride(400)
        assertEquals("#00ABEF", prefs.initialSettings().md3CustomColorHex)
        assertEquals(Md3ColorSource.CUSTOM, prefs.initialSettings().md3ColorSource)
        assertEquals(115, prefs.initialSettings().appDpiOverridePercent)
        prefs.setDpiOverride(0); assertEquals(0, prefs.initialSettings().appDpiOverridePercent)
    }
    @Test fun invalidStoredTypesAndPaletteEnumsFallbackWithoutCrashing() {
        val state = decodeDesktopThemeSettings(values("theme_mode_v2" to JsonPrimitive("2"), "theme_color_style" to JsonPrimitive("gone"), "theme_color_spec" to JsonPrimitive("gone"), "md3_custom_color_hex" to JsonPrimitive("#GGGGGG")))
        assertEquals(AppThemeMode.FOLLOW_SYSTEM, state.themeMode); assertEquals("#007AFF", state.md3CustomColorHex)
        assertEquals(PaletteStyle.TonalSpot, state.colorStyle); assertEquals(ColorSpec.SpecVersion.SPEC_2025, state.colorSpec)
    }
    @Test fun darkModeTracksSystemAndExplicitLightOverridesIt() {
        assertFalse(buildDesktopAppearancePalette(DesktopThemeSettings(), false).dark)
        assertTrue(buildDesktopAppearancePalette(DesktopThemeSettings(), true).dark)
        assertFalse(buildDesktopAppearancePalette(DesktopThemeSettings(themeMode = AppThemeMode.LIGHT), true).dark)
    }
    @Test fun materialAmoledSurfacesActuallyBecomeBlack() {
        val scheme = buildDesktopAppearancePalette(DesktopThemeSettings(themeMode = AppThemeMode.DARK, darkThemeStyle = DarkThemeStyle.AMOLED), false).material
        assertEquals(Color.Black, scheme.background); assertEquals(Color.Black, scheme.surface)
        assertTrue(scheme.onSurface.luminance() > 0.5f)
    }
    @Test fun everyExposedMaterialStyleAndSpecBuildsWithReadableBodyText() {
        for (style in resolveColorStyleOptions().map { it.value }) for (spec in resolveColorSpecOptions().map { it.value }) for (dark in listOf(false, true)) {
            val scheme = buildDesktopAppearancePalette(DesktopThemeSettings(md3ColorSource = Md3ColorSource.CUSTOM,
                md3CustomColorHex = "#88FF11", colorStyle = style, colorSpec = spec), dark).material
            assertTrue(calculateContrastRatio(scheme.onSurface, scheme.surface) >= ACCESSIBLE_TEXT_MIN_CONTRAST,
                "Body text contrast for $style/$spec/dark=$dark")
        }
    }
    @Test fun nativeMiuixNeutralsAndMaterialCanvasMatchExactUpstreamRoles() {
        val settings = DesktopThemeSettings(uiStyle = AppUiStyle.MIUIX, themeMode = AppThemeMode.LIGHT, md3ColorSource = Md3ColorSource.CUSTOM, md3CustomColorHex = "#FF0088")
        val palette = buildDesktopAppearancePalette(settings, false)
        val original = top.yukonga.miuix.kmp.theme.lightColorScheme()
        assertEquals(original.background, palette.miuixLight.background)
        assertEquals(original.surface, palette.miuixLight.surface)
        assertEquals(palette.miuixLight.surface, palette.material.background)
        assertEquals(palette.miuixLight.surfaceContainer, palette.material.surfaceContainer)
        assertEquals(palette.miuixLight.primary, palette.material.primary)
    }
    @Test fun customRolesApplyOnlyToOriginalMaterialCustomColorBranch() {
        val overrides = ThemeRoleOverrides(enabled = true, light = ThemeModeRoleOverrides("#112233", "#EEEEEE", "#CCCCCC", "#FF0000"))
        val settings = DesktopThemeSettings(themeMode = AppThemeMode.LIGHT, md3ColorSource = Md3ColorSource.CUSTOM, themeRoleOverrides = overrides)
        assertEquals(Color(0xFF112233), buildDesktopAppearancePalette(settings, false).material.background)
        assertNotEquals(Color(0xFF112233), buildDesktopAppearancePalette(settings.copy(md3ColorSource = Md3ColorSource.FOLLOW_WALLPAPER), false).material.background)
        assertNotEquals(Color(0xFF112233), buildDesktopAppearancePalette(settings.copy(uiStyle = AppUiStyle.MIUIX), false).material.background)
    }
    @Test fun fontAndScaleTransformActualThemeTypographyAndDensity() {
        val base = resolveMaterialTypography(AppUiStyle.MATERIAL3)
        val scaled = base.scaled(AppFontSizePreset.EXTRA_EXTRA_LARGE.multiplier).withFontWeight(AppFontWeightPreset.BOLD.fontWeight)
        assertEquals(base.bodyLarge.fontSize * 1.25f, scaled.bodyLarge.fontSize)
        assertEquals(AppFontWeightPreset.BOLD.fontWeight, scaled.bodyLarge.fontWeight)
        assertEquals(0.9f, resolveEffectiveDensityMultiplier(AppUiScalePreset.LARGE, 90))
        assertEquals(1.08f, resolveEffectiveDensityMultiplier(AppUiScalePreset.LARGE, null))
    }
    @Test fun explicitLanguagesUseRealOriginalResources() {
        assertEquals("外观设置", DesktopStrings.forLanguage(AppLanguage.SIMPLIFIED_CHINESE)["appearance_settings_title"])
        assertEquals("外觀設定", DesktopStrings.forLanguage(AppLanguage.TRADITIONAL_CHINESE_TAIWAN)["appearance_settings_title"])
        assertEquals("Appearance Settings", DesktopStrings.forLanguage(AppLanguage.ENGLISH)["appearance_settings_title"])
        assertEquals("Me", DesktopStrings.forLanguage(AppLanguage.ENGLISH)["bottom_nav_profile"])
    }
    @Test fun systemLanguageChoosesSupportedUiLanguageNotRegionOrFirstUnsupportedTag() {
        assertEquals("zh-TW", resolveDesktopResourceLanguage(AppLanguage.FOLLOW_SYSTEM, listOf("de-DE", "zh-Hant-HK", "en-US")))
        assertEquals("en", resolveDesktopResourceLanguage(AppLanguage.FOLLOW_SYSTEM, listOf("fr-FR", "en-GB")))
        assertEquals("zh-CN", resolveDesktopResourceLanguage(AppLanguage.FOLLOW_SYSTEM, listOf("ja-JP")))
        assertEquals("en", resolveDesktopResourceLanguage(AppLanguage.ENGLISH, listOf("zh-CN")))
    }
    @Test fun missingEnglishResourceFallsBackToOriginalDefault() {
        val english = DesktopStrings.forLanguage(AppLanguage.ENGLISH)
        val chinese = DesktopStrings.forLanguage(AppLanguage.SIMPLIFIED_CHINESE)
        // These source keys have no values-en translation in this pinned upstream tree.
        val englishXml = javaClass.getResourceAsStream("/bilipai-strings/en/strings.xml")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val chineseXml = javaClass.getResourceAsStream("/bilipai-strings/zh-CN/strings.xml")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val pattern = Regex("<string name=\"([^\"]+)\"")
        val missing = pattern.findAll(chineseXml).map { it.groupValues[1] }.toSet() - pattern.findAll(englishXml).map { it.groupValues[1] }.toSet()
        assertEquals(2, missing.size); missing.forEach { assertEquals(chinese[it], english[it]) }
        assertFailsWith<IllegalStateException> { english["unknown_resource"] }
    }
    @Test fun languageRestartPersistsAndAppliesBeforeRestart(): Unit = runBlocking {
        val order = mutableListOf<String>()
        persistAndApplyAppLanguageBeforeRestart(AppLanguage.ENGLISH,
            persist = { order += "persist" }, apply = { order += "apply" }, restart = { order += "restart" })
        assertEquals(listOf("persist", "apply", "restart"), order)
    }
    @Test fun failedLanguagePersistenceDoesNotApplyOrRestart(): Unit = runBlocking {
        var applied = false; var restarted = false
        assertFailsWith<IllegalStateException> {
            persistAndApplyAppLanguageBeforeRestart(AppLanguage.ENGLISH, persist = { error("disk unavailable") },
                apply = { applied = true }, restart = { restarted = true })
        }
        assertFalse(applied); assertFalse(restarted)
    }
    @Test fun androidXmlEscapesKeepTextAndNoRegionalFormatMutation() {
        assertEquals("one\ntwo\t\"quoted\"\\end", decodeAndroidString("one\\ntwo\\t\\\"quoted\\\"\\\\end"))
        assertEquals(listOf("zh-TW"), resolveAppLanguageLocaleTags(AppLanguage.TRADITIONAL_CHINESE_TAIWAN))
    }
}
