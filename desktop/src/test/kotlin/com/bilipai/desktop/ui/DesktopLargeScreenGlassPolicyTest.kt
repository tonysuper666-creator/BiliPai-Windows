package com.bilipai.desktop.ui

import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.blur.BlurIntensity
import com.android.purebilibili.feature.home.components.HomeTopChromeRenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopLargeScreenGlassPolicyTest {
    @Test fun liquidTopBarUsesTheWindowsBackdropWhenTheProbeAndSourceAreReady() {
        val decision = resolve(DesktopLargeScreenGlassSurface.TOP_BAR, liquid = true, supported = true, backdrop = true)
        assertEquals(HomeTopChromeRenderMode.LIQUID_GLASS_BACKDROP, decision.renderMode)
        assertTrue(decision.passBackdrop)
        assertTrue(decision.liquidGlassEffectsEnabled)
        assertFalse(decision.passHaze)
        assertFalse(decision.suppressLiquidGlassPreference)
        assertEquals(0.10f, decision.backgroundAlpha, 0.0001f)
    }

    @Test fun liquidTopBarFallsBackToHazeWhenTheBackdropIsNotReady() {
        val decision = resolve(
            DesktopLargeScreenGlassSurface.DYNAMIC_TAB_STRIP,
            liquid = true,
            supported = true,
            backdrop = false,
            haze = true,
        )
        assertEquals(HomeTopChromeRenderMode.LIQUID_GLASS_HAZE, decision.renderMode)
        assertFalse(decision.passBackdrop)
        assertFalse(decision.liquidGlassEffectsEnabled)
        assertTrue(decision.passHaze)
        assertTrue(decision.suppressLiquidGlassPreference)
        assertEquals(0.10f, decision.backgroundAlpha, 0.0001f)
    }

    @Test fun unsupportedLiquidFallsBackToBlurThenPlain() {
        val blurred = resolve(DesktopLargeScreenGlassSurface.TOP_BAR, liquid = true, haze = true)
        assertEquals(HomeTopChromeRenderMode.BLUR, blurred.renderMode)
        assertTrue(blurred.passHaze)
        assertFalse(blurred.passBackdrop)
        assertFalse(blurred.liquidGlassEffectsEnabled)
        assertTrue(blurred.suppressLiquidGlassPreference)
        assertEquals(0.4f, blurred.backgroundAlpha, 0.0001f)

        val plain = resolve(DesktopLargeScreenGlassSurface.TOP_BAR, liquid = true)
        assertEquals(HomeTopChromeRenderMode.PLAIN, plain.renderMode)
        assertEquals(1f, plain.backgroundAlpha, 0.0001f)
        assertFalse(plain.passHaze)
        assertTrue(plain.suppressLiquidGlassPreference)
    }

    @Test fun headerBlurUsesBudgetedBlurIntensityAlphas() {
        val thin = resolve(DesktopLargeScreenGlassSurface.TOP_BAR, headerBlur = true, haze = true)
        assertEquals(HomeTopChromeRenderMode.BLUR, thin.renderMode)
        assertEquals(BlurIntensity.THIN, thin.effectiveBlurIntensity)
        assertEquals(0.4f, thin.backgroundAlpha, 0.0001f)

        val apple = resolve(
            DesktopLargeScreenGlassSurface.TOP_BAR,
            headerBlur = true,
            haze = true,
            intensity = BlurIntensity.APPLE_DOCK,
        )
        assertEquals(BlurIntensity.APPLE_DOCK, apple.effectiveBlurIntensity)
        assertEquals(0.6f, apple.backgroundAlpha, 0.0001f)

        val thick = resolve(
            DesktopLargeScreenGlassSurface.TOP_BAR,
            headerBlur = true,
            haze = true,
            intensity = BlurIntensity.THICK,
        )
        assertEquals(BlurIntensity.THICK, thick.effectiveBlurIntensity)
        assertEquals(0.15f, thick.backgroundAlpha, 0.0001f)
    }

    @Test fun dockBudgetsAppleDockDownToThinAndKeepsTheBottomBarMultiplier() {
        val decision = resolve(
            DesktopLargeScreenGlassSurface.DOCK,
            bottomBlur = true,
            haze = true,
            backdrop = true,
            intensity = BlurIntensity.APPLE_DOCK,
        )
        assertEquals(HomeTopChromeRenderMode.BLUR, decision.renderMode)
        assertEquals(BlurIntensity.THIN, decision.effectiveBlurIntensity)
        assertEquals(0.4f * 0.95f, decision.backgroundAlpha, 0.0001f)
        assertTrue(decision.passBackdrop)
        assertTrue(decision.passHaze)
        assertFalse(decision.liquidGlassEffectsEnabled)
    }

    @Test fun reducedMotionCapsTheHeaderAtThickAndScalesAlpha() {
        val decision = resolve(
            DesktopLargeScreenGlassSurface.TOP_BAR,
            headerBlur = true,
            haze = true,
            motionTier = MotionTier.Reduced,
        )
        assertEquals(BlurIntensity.THICK, decision.effectiveBlurIntensity)
        assertEquals(0.15f * 0.9f, decision.backgroundAlpha, 0.0001f)
    }

    @Test fun dockLensRequiresAReadyWindowsBackdropAndClearsTheFlagOtherwise() {
        val lens = resolve(
            DesktopLargeScreenGlassSurface.DOCK,
            liquid = true,
            supported = true,
            backdrop = true,
            bottomBlur = true,
            haze = true,
        )
        assertEquals(HomeTopChromeRenderMode.LIQUID_GLASS_BACKDROP, lens.renderMode)
        assertTrue(lens.liquidGlassEffectsEnabled)
        assertTrue(lens.passBackdrop)
        assertTrue(lens.passHaze)
        assertFalse(lens.suppressLiquidGlassPreference)

        val withheld = resolve(
            DesktopLargeScreenGlassSurface.DOCK,
            liquid = true,
            supported = true,
            haze = true,
            bottomBlur = true,
        )
        assertEquals(HomeTopChromeRenderMode.BLUR, withheld.renderMode)
        assertFalse(withheld.liquidGlassEffectsEnabled)
        assertFalse(withheld.passBackdrop)
        assertTrue(withheld.passHaze)
        assertTrue(withheld.suppressLiquidGlassPreference)

        val plain = resolve(DesktopLargeScreenGlassSurface.DOCK, liquid = true, supported = false)
        assertEquals(HomeTopChromeRenderMode.PLAIN, plain.renderMode)
        assertTrue(plain.suppressLiquidGlassPreference)
        assertFalse(plain.passBackdrop)
    }

    @Test fun forcedLowBudgetDropsTheDockLens() {
        val decision = resolve(
            DesktopLargeScreenGlassSurface.DOCK,
            liquid = true,
            supported = true,
            backdrop = true,
            haze = true,
            forceLow = true,
        )
        assertFalse(decision.liquidGlassEffectsEnabled)
        assertTrue(decision.suppressLiquidGlassPreference)
        assertEquals(HomeTopChromeRenderMode.PLAIN, decision.renderMode)
        assertFalse(decision.passBackdrop)
    }

    @Test fun sideRailBlursOnlyForBottomBarBlurAndNeverTakesABackdrop() {
        val headerOnly = resolve(
            DesktopLargeScreenGlassSurface.SIDE_RAIL,
            liquid = true,
            supported = true,
            backdrop = true,
            headerBlur = true,
            haze = true,
        )
        assertEquals(HomeTopChromeRenderMode.PLAIN, headerOnly.renderMode)
        assertFalse(headerOnly.passHaze)
        assertFalse(headerOnly.passBackdrop)
        assertFalse(headerOnly.liquidGlassEffectsEnabled)
        assertFalse(headerOnly.suppressLiquidGlassPreference)

        val bottom = resolve(
            DesktopLargeScreenGlassSurface.SIDE_RAIL,
            bottomBlur = true,
            haze = true,
            intensity = BlurIntensity.THICK,
        )
        assertEquals(HomeTopChromeRenderMode.BLUR, bottom.renderMode)
        assertTrue(bottom.passHaze)
        assertFalse(bottom.passBackdrop)
        assertEquals(0.15f * 0.95f, bottom.backgroundAlpha, 0.0001f)
    }

    @Test fun backdropReadyRequiresProbeAckOwnerWindowAndLayer() {
        assertTrue(desktopWindowsBackdropReady(rendererSupported = true, sourceReady = true, owns = true, sameWindow = true, hasBackdrop = true))
        assertFalse(desktopWindowsBackdropReady(rendererSupported = false, sourceReady = true, owns = true, sameWindow = true, hasBackdrop = true))
        assertFalse(desktopWindowsBackdropReady(rendererSupported = true, sourceReady = false, owns = true, sameWindow = true, hasBackdrop = true))
        assertFalse(desktopWindowsBackdropReady(rendererSupported = true, sourceReady = true, owns = false, sameWindow = true, hasBackdrop = true))
        assertFalse(desktopWindowsBackdropReady(rendererSupported = true, sourceReady = true, owns = true, sameWindow = false, hasBackdrop = true))
        assertFalse(desktopWindowsBackdropReady(rendererSupported = true, sourceReady = true, owns = true, sameWindow = true, hasBackdrop = false))
    }

    @Test fun hazeCaptureFollowsLiquidHeaderBottomAndProgressiveFlags() {
        assertFalse(shouldCaptureDesktopLargeScreenHaze(false, false, false, false))
        assertTrue(shouldCaptureDesktopLargeScreenHaze(true, false, false, false))
        assertTrue(shouldCaptureDesktopLargeScreenHaze(false, true, false, false))
        assertTrue(shouldCaptureDesktopLargeScreenHaze(false, false, true, false))
        assertTrue(shouldCaptureDesktopLargeScreenHaze(false, false, false, true))
    }

    @Test fun bottomSkinPaddingFollowsAPresentDecoration() {
        assertTrue(desktopLargeScreenReservesBottomSkinPadding(true))
        assertFalse(desktopLargeScreenReservesBottomSkinPadding(false))
    }

    @Test fun unsupportedWindowsProbeNeverEnablesTheLens() {
        listOf(
            DesktopLargeScreenGlassSurface.SIDE_RAIL,
            DesktopLargeScreenGlassSurface.TOP_BAR,
            DesktopLargeScreenGlassSurface.DOCK,
            DesktopLargeScreenGlassSurface.DYNAMIC_TAB_STRIP,
        ).forEach { surface ->
            val decision = resolve(
                surface,
                liquid = true,
                supported = false,
                backdrop = true,
                haze = true,
                headerBlur = true,
                bottomBlur = true,
            )
            assertFalse(decision.liquidGlassEffectsEnabled)
            assertNotEquals(HomeTopChromeRenderMode.LIQUID_GLASS_BACKDROP, decision.renderMode)
        }
    }

    private fun resolve(
        surface: DesktopLargeScreenGlassSurface,
        liquid: Boolean = false,
        headerBlur: Boolean = false,
        bottomBlur: Boolean = false,
        progressive: Boolean = false,
        supported: Boolean = false,
        backdrop: Boolean = false,
        haze: Boolean = false,
        intensity: BlurIntensity = BlurIntensity.THIN,
        forceLow: Boolean = false,
        motionTier: MotionTier = MotionTier.Normal,
    ) = resolveDesktopLargeScreenGlass(
        DesktopLargeScreenGlassRequest(
            surface = surface,
            liquidGlassRequested = liquid,
            headerBlurEnabled = headerBlur,
            bottomBarBlurEnabled = bottomBlur,
            progressiveTopBlurEnabled = progressive,
            blurIntensity = intensity,
            windowsRendererSupported = supported,
            backdropReady = backdrop,
            hazeAvailable = haze,
            forceLowBlurBudget = forceLow,
            motionTier = motionTier,
        ),
    )
}
