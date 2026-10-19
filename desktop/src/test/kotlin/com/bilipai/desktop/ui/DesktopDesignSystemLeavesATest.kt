package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.AppEntryIconPalette
import com.android.purebilibili.core.ui.AppIconStyle
import com.android.purebilibili.core.ui.AppPopupSurfaceType
import com.android.purebilibili.core.ui.AppSemanticAccentPalette
import com.android.purebilibili.core.ui.AppSemanticIconFamily
import com.android.purebilibili.core.ui.MediaContrastPalette
import com.android.purebilibili.core.ui.resolveAppEntryIconColor
import com.android.purebilibili.core.ui.resolveAppIconStyle
import com.android.purebilibili.core.ui.resolveAppIconStylePreference
import com.android.purebilibili.core.ui.resolveAppSemanticVisualPolicy
import com.android.purebilibili.core.ui.resolveCompactCapsuleChromeSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Compile and behaviour check of the v0.4.6 design-system leaf files carried whole by the
 * v046-design-system-leaves-a archive (MediaContrastPalette, AppPopupSurface, AppSemanticVisualPolicy,
 * AppEntryIconPalette, AppChromeSizeTokens). Pure values and policies: no window, no GPU, no network.
 */
class DesktopDesignSystemLeavesATest {
    @Test fun mediaContrastPaletteCarriesTheContentRolesThatCarriedPlayerCodeReads() {
        assertEquals(Color.White, MediaContrastPalette.Foreground)
        assertEquals(Color.Black, MediaContrastPalette.Scrim)
        assertEquals(Color.White.copy(alpha = 0.72f), MediaContrastPalette.SecondaryForeground)
        assertEquals(Color(0xFF242424), MediaContrastPalette.ControlSurface)
        assertEquals(Color.Black.copy(alpha = 0.48f), MediaContrastPalette.ControlScrim)
        assertEquals(Color.White.copy(alpha = 0.24f), MediaContrastPalette.Track)
        assertEquals(Color(0xFF81C784), MediaContrastPalette.Success)
        assertEquals(Color(0xFFFFCC80), MediaContrastPalette.Warning)
        assertEquals(Color(0xFFFFB4AB), MediaContrastPalette.Error)
        assertEquals(MediaContrastPalette.Warning, MediaContrastPalette.Rating)
    }

    @Test fun chromeSizeTokensCarryTheLiquidSearchFieldHeightAndKeepTheNativeMetrics() {
        assertEquals(40, AppChromeSizeTokens.LiquidSearchFieldHeightDp)
        assertEquals(48.dp, AppChromeSizeTokens.MinimumTouchTarget)
        assertEquals(40.dp, AppChromeSizeTokens.TopBarIconVisualSize)
        assertEquals(44, AppChromeSizeTokens.CompactControlHeightDp)
        assertEquals(56, resolveCompactCapsuleChromeSpec(AppUiStyle.MATERIAL3).primaryHeightDp)
        assertEquals(48, resolveCompactCapsuleChromeSpec(AppUiStyle.MIUIX).primaryHeightDp)
    }

    @Test fun semanticVisualPolicyKeepsTheNativeIconFamilyAndGroupingPerUiStyle() {
        val palette = AppSemanticAccentPalette(
            primary = Color(0xFF112233),
            secondary = Color(0xFF223344),
            tertiary = Color(0xFF334455),
            error = Color(0xFF445566),
        )
        val material = resolveAppSemanticVisualPolicy(AppUiStyle.MATERIAL3, palette)
        val miuix = resolveAppSemanticVisualPolicy(AppUiStyle.MIUIX, palette)
        assertEquals(AppSemanticIconFamily.MATERIAL, material.iconFamily)
        assertEquals(AppSemanticIconFamily.MIUIX, miuix.iconFamily)
        assertEquals(false, material.prefersGroupedListCards)
        assertEquals(true, miuix.prefersGroupedListCards)
        assertEquals(palette, miuix.accentPalette)
        val standard = resolveAppSemanticVisualPolicy(AppUiStyle.MIUIX, palette, AppIconStyle.MD3_STANDARD)
        assertEquals(AppSemanticIconFamily.MATERIAL, standard.effectiveIconFamily)
        assertEquals(AppSemanticIconFamily.MIUIX, standard.iconFamily)
    }

    @Test fun iconStyleResolutionFollowsTheRuntimeThemeOnlyForAuto() {
        assertEquals(AppIconStyle.AUTO, resolveAppIconStyle(AppIconStyle.AUTO, AppUiStyle.MIUIX))
        assertEquals(AppIconStyle.MD3_STANDARD, resolveAppIconStyle(AppIconStyle.AUTO, AppUiStyle.MATERIAL3))
        assertEquals(AppIconStyle.THEME_CONTAINER, resolveAppIconStyle(AppIconStyle.THEME_CONTAINER, AppUiStyle.MIUIX))
        assertEquals(AppIconStyle.THEME_CONTAINER, resolveAppIconStylePreference("THEME_CONTAINER"))
        assertEquals(AppIconStyle.AUTO, resolveAppIconStylePreference("UNKNOWN_VALUE"))
        assertEquals(AppIconStyle.AUTO, resolveAppIconStylePreference(null))
    }

    @Test fun entryIconPaletteHasSixteenDistinctColoursAndTheIndexResolverWrapsAround() {
        assertEquals(16, AppEntryIconPalette.size)
        assertEquals(16, AppEntryIconPalette.distinct().size)
        assertEquals(AppEntryIconPalette[0], resolveAppEntryIconColor(0))
        assertEquals(AppEntryIconPalette[0], resolveAppEntryIconColor(16))
        assertEquals(AppEntryIconPalette[3], resolveAppEntryIconColor(19))
        assertFailsWith<IllegalArgumentException> { resolveAppEntryIconColor(-1) }
        assertEquals(listOf("SHEET", "DIALOG", "MENU"), AppPopupSurfaceType.entries.map { it.name })
    }
}
