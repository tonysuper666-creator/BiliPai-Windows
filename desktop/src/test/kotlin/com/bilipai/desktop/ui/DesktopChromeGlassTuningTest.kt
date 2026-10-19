package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.store.BottomBarLiquidGlassPreset
import com.android.purebilibili.core.store.LiquidGlassAdvancedSettings
import com.android.purebilibili.core.store.LiquidGlassReadabilityMode
import com.android.purebilibili.core.store.resolveLiquidGlassAdvancedSettings
import com.android.purebilibili.core.store.resolveStoredLiquidGlassProgress
import com.android.purebilibili.feature.home.components.LiquidGlassTuning
import com.android.purebilibili.feature.home.components.resolveBottomBarGlassMaterialContainerColor
import com.android.purebilibili.feature.home.components.resolveLiquidGlassIndicatorChromaticAberration
import com.android.purebilibili.feature.home.components.resolveLiquidGlassTuning
import com.bilipai.desktop.settings.DesktopLiquidTabHomePreferences
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Windows shell resolves its glass material through the original resolveLiquidGlassTuning and
 * resolveBottomBarGlassMaterialContainerColor. These cases pin the v0.4.6 values that reach the shell:
 * the linear readability curve with the 0.34 scrim, the constant indicator dispersion boost and the
 * container alpha without the preset-specific reduction. No Windows, GPU or network access is needed.
 */
class DesktopChromeGlassTuningTest {
    // The same arguments DesktopShell passes when it builds LiquidGlassRenderConfig.tuning.
    private fun shellTuning(progress: Float, settings: LiquidGlassAdvancedSettings): LiquidGlassTuning =
        resolveLiquidGlassTuning(
            progress = progress,
            advancedSettings = settings,
            readabilityMode = LiquidGlassReadabilityMode.STABLE,
        )

    private fun defaultSettings(): LiquidGlassAdvancedSettings = resolveLiquidGlassAdvancedSettings(
        presetValue = null,
        contentReadability = null,
        chromaticAberration = null,
        contentDistortion = null,
    )

    private fun defaultProgress(): Float = resolveStoredLiquidGlassProgress(
        progress = null,
        legacyModeValue = null,
        legacyStrength = null,
        legacyStyleValue = null,
    )

    @Test
    fun defaultShellGlassUsesTheLinearReadabilityCurveAndTheFixedScrimFactor() {
        val progress = defaultProgress()
        val settings = defaultSettings()
        assertEquals(0.62f, settings.contentReadability, 0.0001f)
        assertTrue(progress in 0.34f..0.66f, "the default material is the balanced band")

        val tuning = shellTuning(progress, settings)
        val fullProtection = shellTuning(progress, LiquidGlassAdvancedSettings(contentReadability = 1f))
        // Linear: 62% protection is exactly 62% of full protection. The former fourth-power curve gave about 15%.
        assertEquals(fullProtection.contentReadabilityBoost * 0.62f, tuning.contentReadabilityBoost, 0.00001f)
        assertEquals(tuning.contentReadabilityBoost * 0.34f, tuning.contentReadabilityScrimAlpha, 0.000001f)
        assertTrue(tuning.contentReadabilityScrimAlpha > 0.1f, "the default scrim must no longer be near invisible")
    }

    @Test
    fun balancedAnchorMatchesTheUpstreamReadabilityScrim() {
        val tuning = shellTuning(0.5f, LiquidGlassAdvancedSettings())
        assertEquals(0.372f, tuning.contentReadabilityBoost, 0.0001f)
        assertEquals(0.12648f, tuning.contentReadabilityScrimAlpha, 0.0001f)

        listOf(0f, 0.2f, 0.4f, 0.62f, 0.8f, 1f).forEach { value ->
            val sample = shellTuning(0.5f, LiquidGlassAdvancedSettings(contentReadability = value))
            assertEquals(0.6f * value, sample.contentReadabilityBoost, 0.00001f)
            assertEquals(sample.contentReadabilityBoost * 0.34f, sample.contentReadabilityScrimAlpha, 0.000001f)
        }
    }

    @Test
    fun indicatorDispersionBoostIsConstantAcrossGlassStrengths() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            val tuning = shellTuning(progress, defaultSettings())
            assertEquals(1f, tuning.indicatorChromaticBoost, 0.0001f)
            assertEquals(0.5f, resolveLiquidGlassIndicatorChromaticAberration(tuning), 0.0001f)
            assertEquals(0f, tuning.shellChromaticAberrationAmount, 0.0001f)
        }
    }

    @Test
    fun bottomBarContainerAlphaFollowsTheSliderWithoutPresetOrSurfaceReduction() {
        BottomBarLiquidGlassPreset.entries.forEach { preset ->
            listOf(Color.White, Color.Black).forEach { surface ->
                val balanced = resolveBottomBarGlassMaterialContainerColor(
                    surfaceColor = surface,
                    preset = preset,
                    glassEnabled = true,
                    fallbackAlpha = 1f,
                )
                assertEquals(0.40f, balanced.alpha, 0.005f)
                val frosted = resolveBottomBarGlassMaterialContainerColor(
                    surfaceColor = surface,
                    preset = preset,
                    glassEnabled = true,
                    fallbackAlpha = 1f,
                    liquidGlassTuning = resolveLiquidGlassTuning(progress = 1f),
                )
                assertEquals(0.54f, frosted.alpha, 0.005f)
                val off = resolveBottomBarGlassMaterialContainerColor(
                    surfaceColor = surface,
                    preset = preset,
                    glassEnabled = false,
                    fallbackAlpha = 0.7f,
                )
                assertEquals(0.7f, off.alpha, 0.005f)
            }
        }
    }

    @Test
    fun windowsPreferencesAlwaysResolveTheBiliPaiTunedPreset() {
        val preferences = DesktopLiquidTabHomePreferences(
            isBottomBarLiquidGlassEnabled = true,
            androidNativeLiquidGlassEnabled = true,
            liquidGlassProgress = defaultProgress(),
            liquidGlassAdvancedSettings = defaultSettings(),
            liquidGlassReadabilityMode = LiquidGlassReadabilityMode.STABLE,
            navigationIconCrossScaleEnabled = true,
        )
        // The previous 0.75 dark / 0.95 light reduction applied to exactly this preset.
        assertEquals(BottomBarLiquidGlassPreset.BILIPAI_TUNED, preferences.bottomBarLiquidGlassPreset)
    }
}
