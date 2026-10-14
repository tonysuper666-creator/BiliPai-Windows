package com.android.purebilibili.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.store.ThemeModeRoleOverrides
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopThemeRoleSpecContractTest {
    @Test
    fun fixedLibraryDefaultExplainsBothOriginalLightControlFixtures() {
        for (hex in listOf("#FFF000", "#007AFF")) {
            val reference = dynamicColorScheme(
                seedColor = parseMd3CustomColorHex(hex), isDark = false,
                style = PaletteStyle.TonalSpot, specVersion = ColorSpec.SpecVersion.SPEC_2025,
            )
            val roles = ThemeRoleOverrides(enabled = true,
                light = ThemeModeRoleOverrides("#FFFFFF", "#111111", "#555555", hex))
            val actual = applyThemeRoleOverrides(lightColorScheme(), roles, false)
            assertEquals(reference.primary, actual.primary, hex)
            assertEquals(reference.onPrimary, actual.onPrimary, hex)
            assertTrue(calculateContrastRatio(actual.onPrimary, actual.primary) >= ACCESSIBLE_TEXT_MIN_CONTRAST, hex)
        }
    }

    @Test
    fun requestedStylesAndSpecificationsRetainCompleteAccentFamiliesAndReadablePairs() {
        for (hex in listOf("#007AFF", "#FF9500")) {
            for (style in PaletteStyle.entries) {
                for (spec in listOf(ColorSpec.SpecVersion.SPEC_2021, ColorSpec.SpecVersion.SPEC_2025)) {
                    for (dark in listOf(false, true)) {
                        val message = "$hex / $style / $spec / dark=$dark"
                        val reference = dynamicColorScheme(seedColor = parseMd3CustomColorHex(hex), isDark = dark,
                            style = style, specVersion = spec)
                        val roles = ThemeRoleOverrides(enabled = true,
                            light = ThemeModeRoleOverrides("#F4F0E8", "#201A17", "#655D57", hex),
                            dark = ThemeModeRoleOverrides("#121212", "#EEEEEE", "#AAAAAA", hex))
                        val actual = applyThemeRoleOverrides(
                            if (dark) darkColorScheme() else lightColorScheme(), roles, dark, style, spec)
                        assertEquals(accentFamily(reference), accentFamily(actual), message)
                        val expectedPairs = readablePairs(reference)
                        for ((index, pair) in readablePairs(actual).withIndex()) {
                            assertTrue(calculateContrastRatio(pair.second, pair.first) >= ACCESSIBLE_TEXT_MIN_CONTRAST,
                                "$message / pair=$index")
                            val original = expectedPairs[index]
                            if (calculateContrastRatio(original.second, original.first) >= ACCESSIBLE_TEXT_MIN_CONTRAST) {
                                assertEquals(original.second, pair.second, "$message / retained pair=$index")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun controlSeedsUseTheSeedOverloadWithoutForcingAnExplicitPrimaryColor() {
        val seed = parseMd3CustomColorHex("#007AFF")
        val reference = dynamicColorScheme(seedColor = seed, isDark = false,
            style = PaletteStyle.TonalSpot, specVersion = ColorSpec.SpecVersion.SPEC_2021)
        val explicitPrimary = dynamicColorScheme(primary = seed, isDark = false,
            style = PaletteStyle.TonalSpot, specVersion = ColorSpec.SpecVersion.SPEC_2021)
        val roles = ThemeRoleOverrides(enabled = true,
            light = ThemeModeRoleOverrides("#FFFFFF", "#111111", "#555555", "#007AFF"))
        val actual = applyThemeRoleOverrides(lightColorScheme(), roles, false,
            PaletteStyle.TonalSpot, ColorSpec.SpecVersion.SPEC_2021)
        assertNotEquals(explicitPrimary.primary, reference.primary)
        assertEquals(reference.primary, actual.primary)
    }

    private fun accentFamily(s: ColorScheme): List<Color> = listOf(
        s.primary, s.primaryContainer, s.secondary, s.secondaryContainer, s.tertiary, s.tertiaryContainer,
        s.primaryFixed, s.primaryFixedDim, s.onPrimaryFixed, s.onPrimaryFixedVariant,
        s.secondaryFixed, s.secondaryFixedDim, s.onSecondaryFixed, s.onSecondaryFixedVariant,
        s.tertiaryFixed, s.tertiaryFixedDim, s.onTertiaryFixed, s.onTertiaryFixedVariant,
        s.surfaceTint, s.inversePrimary,
    )

    private fun readablePairs(s: ColorScheme): List<Pair<Color, Color>> = listOf(
        s.primary to s.onPrimary, s.primaryContainer to s.onPrimaryContainer,
        s.secondary to s.onSecondary, s.secondaryContainer to s.onSecondaryContainer,
        s.tertiary to s.onTertiary, s.tertiaryContainer to s.onTertiaryContainer,
    )
}
