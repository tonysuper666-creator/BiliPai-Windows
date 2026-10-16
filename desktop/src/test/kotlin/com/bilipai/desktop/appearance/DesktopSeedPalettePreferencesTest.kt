package com.bilipai.desktop.appearance

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.store.ThemeModeRoleOverrides
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import com.bilipai.desktop.appearance.assertDesktopPaletteEquals as assertEquals

class DesktopSeedPalettePreferencesTest {
    @TempDir lateinit var directory: Path

    @Test fun absentAndUnknownEnumsPreserveTheExistingStandardPipeline() {
        val initial = decodeDesktopThemeSettings(JsonObject(emptyMap()))
        assertEquals(SeedPaletteStyle.Standard, initial.seedPaletteStyle)
        assertEquals(Contrast.Default, initial.colorContrast)
        assertEquals(PaletteStyle.TonalSpot, initial.colorStyle)
        assertEquals(ColorSpec.SpecVersion.SPEC_2025, initial.colorSpec)
        val unknown = decodeDesktopThemeSettings(buildJsonObject {
            put("seed_palette_style", "future-preset")
            put("theme_color_contrast", "future-contrast")
            put("theme_color_style", "Expressive")
        })
        assertEquals(SeedPaletteStyle.Standard, unknown.seedPaletteStyle)
        assertEquals(Contrast.Default, unknown.colorContrast)
        assertEquals(PaletteStyle.Expressive, unknown.colorStyle)
    }

    @Test fun persistedPresetAndContrastKeepLegacyStylesRolesAndGlassMigration() = runBlocking {
        val store = DesktopPluginStore(directory)
        val prefs = DesktopThemePrefs(store)
        prefs.setColorStyle(PaletteStyle.Content)
        prefs.setColorSpec(ColorSpec.SpecVersion.SPEC_2025)
        prefs.setRoleOverrides(ThemeRoleOverrides(enabled = true))
        prefs.setSeedPaletteStyle(SeedPaletteStyle.CandyPop)
        prefs.setColorContrast(Contrast.High)
        prefs.ensureMigrated()
        val restored = DesktopThemePrefs(DesktopPluginStore(directory)).initialSettings()
        assertEquals(SeedPaletteStyle.CandyPop, restored.seedPaletteStyle)
        assertEquals(Contrast.High, restored.colorContrast)
        assertEquals(PaletteStyle.Content, restored.colorStyle)
        assertEquals(ColorSpec.SpecVersion.SPEC_2025, restored.colorSpec)
        assertTrue(restored.themeRoleOverrides.enabled)
        assertTrue(restored.liquidGlassEnabled)
        prefs.setLiquidGlassEnabled(false)
        prefs.setSeedPaletteStyle(SeedPaletteStyle.Standard)
        prefs.ensureMigrated()
        assertFalse(prefs.initialSettings().liquidGlassEnabled)
        assertTrue(prefs.initialSettings().themeRoleOverrides.enabled)
        assertEquals(PaletteStyle.Content, prefs.initialSettings().colorStyle)
    }

    @Test fun extendedPaletteUsesTheOriginalSharedEndpointAcrossRenderersAndGlass() {
        for (style in listOf(SeedPaletteStyle.CandyPop, SeedPaletteStyle.Vibrant)) {
            for (ui in AppUiStyle.entries) for (dark in listOf(false, true)) for (glass in listOf(false, true)) {
                val settings = DesktopThemeSettings(uiStyle = ui,
                    themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT,
                    md3ColorSource = Md3ColorSource.CUSTOM, md3CustomColorHex = "#5CB67B",
                    seedPaletteStyle = style, liquidGlassEnabled = glass,
                    themeRoleOverrides = ThemeRoleOverrides(enabled = true,
                        light = ThemeModeRoleOverrides("#112233", "#FFFFFF", "#FFFFFF", "#FF0000")))
                val original = createSeedColorScheme(Color(0xFF5CB67B), dark, style,
                    colorSpec = settings.colorSpec)
                val endpoint = resolveAppThemeEndpointPalette(original, ui, Md3ColorSource.CUSTOM,
                    dark, preserveGeneratedPalette = true, liquidGlassEnabled = glass)
                val actual = buildDesktopAppearancePalette(settings, systemDark = false)
                assertEquals(endpoint.material, actual.material, "$style $ui dark=$dark glass=$glass")
                assertEquals(endpoint.miuix, if (dark) actual.miuixDark else actual.miuixLight)
            }
        }
    }

    @Test fun incompatibleSpecFallsBackWithoutOverwritingTheRequestedPreference() = runBlocking {
        val prefs = DesktopThemePrefs(DesktopPluginStore(directory))
        prefs.setColorStyle(PaletteStyle.Content)
        prefs.setColorSpec(ColorSpec.SpecVersion.SPEC_2025)
        var settings = prefs.initialSettings()
        assertEquals(ColorSpec.SpecVersion.SPEC_2021,
            resolveSelectedColorSpec(settings.colorSpec, settings.colorStyle, settings.seedPaletteStyle))
        assertEquals(listOf(ColorSpec.SpecVersion.SPEC_2021),
            resolveColorSpecOptions(settings.colorStyle, settings.seedPaletteStyle).map { it.value })
        prefs.setSeedPaletteStyle(SeedPaletteStyle.Vibrant)
        settings = DesktopThemePrefs(DesktopPluginStore(directory)).initialSettings()
        assertEquals(ColorSpec.SpecVersion.SPEC_2025, settings.colorSpec)
        assertEquals(ColorSpec.SpecVersion.SPEC_2025,
            resolveSelectedColorSpec(settings.colorSpec, settings.colorStyle, settings.seedPaletteStyle))
    }

    @Test fun returningToStandardRestoresTheStoredManualRoleValues() = runBlocking {
        val prefs = DesktopThemePrefs(DesktopPluginStore(directory))
        prefs.setCustomColor("#5CB67B")
        prefs.setThemeMode(AppThemeMode.LIGHT)
        prefs.setRoleOverrides(ThemeRoleOverrides(enabled = true,
            light = ThemeModeRoleOverrides("#112233", "#FFFFFF", "#FFFFFF", "#FF0000")))
        prefs.setSeedPaletteStyle(SeedPaletteStyle.CandyPop)
        assertNotEquals(Color(0xFF112233), buildDesktopAppearancePalette(prefs.initialSettings(), false).material.background)
        prefs.setSeedPaletteStyle(SeedPaletteStyle.Standard)
        val restored = DesktopThemePrefs(DesktopPluginStore(directory)).initialSettings()
        assertEquals("#112233", restored.themeRoleOverrides.light.backgroundHex)
        assertEquals(Color(0xFF112233), buildDesktopAppearancePalette(restored, false).material.background)
    }
}
