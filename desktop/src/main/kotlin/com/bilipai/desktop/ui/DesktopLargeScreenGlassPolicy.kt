package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.core.ui.adaptive.MotionTier
import dev.chrisbanes.haze.HazeState
import com.android.purebilibili.core.ui.blur.BlurIntensity
import com.android.purebilibili.core.ui.blur.BlurStyles
import com.android.purebilibili.core.ui.blur.BlurSurfaceType
import com.android.purebilibili.core.ui.blur.resolveBlurBudget
import com.android.purebilibili.core.ui.blur.resolveBudgetedBlurIntensity
import com.android.purebilibili.feature.home.components.HomeTopChromeRenderMode
import com.android.purebilibili.feature.home.components.resolveHomeHeaderSurfaceAlpha
import com.android.purebilibili.feature.home.components.resolveHomeTopChromeMaterialMode
import com.android.purebilibili.feature.home.components.resolveHomeTopChromeRenderMode

internal data class DesktopLargeScreenChromeMaterial(
    val haze: HazeState?,
    val backdropReady: Boolean,
    val windowsRendererSupported: Boolean,
)

internal val LocalDesktopLargeScreenChromeMaterial =
    staticCompositionLocalOf<DesktopLargeScreenChromeMaterial?> { null }

/** Surfaces that share the original liquid-glass / blur / haze decision. */
internal enum class DesktopLargeScreenGlassSurface {
    SIDE_RAIL,
    TOP_BAR,
    DOCK,
    DYNAMIC_TAB_STRIP,
}

internal data class DesktopLargeScreenGlassRequest(
    val surface: DesktopLargeScreenGlassSurface,
    val liquidGlassRequested: Boolean,
    val headerBlurEnabled: Boolean,
    val bottomBarBlurEnabled: Boolean,
    val progressiveTopBlurEnabled: Boolean,
    val blurIntensity: BlurIntensity,
    val windowsRendererSupported: Boolean,
    val backdropReady: Boolean,
    val hazeAvailable: Boolean,
    val forceLowBlurBudget: Boolean = false,
    val motionTier: MotionTier = MotionTier.Normal,
    val isScrolling: Boolean = false,
    val isTransitionRunning: Boolean = false,
)

internal data class DesktopLargeScreenGlassDecision(
    val renderMode: HomeTopChromeRenderMode,
    val passBackdrop: Boolean,
    val passHaze: Boolean,
    val liquidGlassEffectsEnabled: Boolean,
    val backgroundAlpha: Float,
    val effectiveBlurIntensity: BlurIntensity,
    val suppressLiquidGlassPreference: Boolean,
)

/** Original AppNavigation captures a backdrop when liquid glass or bottom-bar blur is on. */
internal fun shouldCaptureDesktopLargeScreenHaze(
    liquidGlassRequested: Boolean,
    headerBlurEnabled: Boolean,
    bottomBarBlurEnabled: Boolean,
    progressiveTopBlurEnabled: Boolean,
): Boolean = liquidGlassRequested || headerBlurEnabled || bottomBarBlurEnabled || progressiveTopBlurEnabled

/** Same window, owner, ACK, and probe. Callers pass the pieces so the policy stays free of AWT. */
internal fun desktopWindowsBackdropReady(
    rendererSupported: Boolean,
    sourceReady: Boolean,
    owns: Boolean,
    sameWindow: Boolean,
    hasBackdrop: Boolean,
): Boolean = rendererSupported && sourceReady && owns && sameWindow && hasBackdrop

/** AppNavigation reserves the taller dock when a bottom-bar skin decoration exists. */
internal fun desktopLargeScreenReservesBottomSkinPadding(hasBottomBarSkinDecoration: Boolean): Boolean =
    hasBottomBarSkinDecoration

internal fun resolveDesktopLargeScreenGlass(
    request: DesktopLargeScreenGlassRequest,
): DesktopLargeScreenGlassDecision {
    val surfaceType = when (request.surface) {
        DesktopLargeScreenGlassSurface.SIDE_RAIL -> BlurSurfaceType.GENERIC
        DesktopLargeScreenGlassSurface.DOCK -> BlurSurfaceType.BOTTOM_BAR
        DesktopLargeScreenGlassSurface.TOP_BAR,
        DesktopLargeScreenGlassSurface.DYNAMIC_TAB_STRIP,
        -> BlurSurfaceType.HEADER
    }
    val budget = resolveBlurBudget(
        surfaceType = surfaceType,
        motionTier = request.motionTier,
        isScrolling = request.isScrolling,
        isTransitionRunning = request.isTransitionRunning,
        forceLowBudget = request.forceLowBlurBudget,
    )
    val effectiveIntensity = resolveBudgetedBlurIntensity(request.blurIntensity, budget)
    return when (request.surface) {
        DesktopLargeScreenGlassSurface.SIDE_RAIL -> resolveSideRailGlass(request, effectiveIntensity, budget.backgroundAlphaMultiplier)
        DesktopLargeScreenGlassSurface.DOCK -> resolveDockGlass(request, effectiveIntensity, budget.backgroundAlphaMultiplier)
        DesktopLargeScreenGlassSurface.TOP_BAR,
        DesktopLargeScreenGlassSurface.DYNAMIC_TAB_STRIP,
        -> resolveTopChromeGlass(request, effectiveIntensity, budget.backgroundAlphaMultiplier)
    }
}

private fun resolveSideRailGlass(
    request: DesktopLargeScreenGlassRequest,
    effectiveIntensity: BlurIntensity,
    alphaMultiplier: Float,
): DesktopLargeScreenGlassDecision {
    // FrostedSideBar has no backdrop slot. AppNavigation gives it haze only for bottom-bar blur.
    val passHaze = request.bottomBarBlurEnabled && request.hazeAvailable
    val alpha = if (passHaze) {
        (BlurStyles.getBackgroundAlpha(effectiveIntensity) * alphaMultiplier).coerceIn(0f, 1f)
    } else {
        1f
    }
    return DesktopLargeScreenGlassDecision(
        renderMode = if (passHaze) HomeTopChromeRenderMode.BLUR else HomeTopChromeRenderMode.PLAIN,
        passBackdrop = false,
        passHaze = passHaze,
        liquidGlassEffectsEnabled = false,
        backgroundAlpha = alpha,
        effectiveBlurIntensity = effectiveIntensity,
        suppressLiquidGlassPreference = false,
    )
}

private fun resolveDockGlass(
    request: DesktopLargeScreenGlassRequest,
    effectiveIntensity: BlurIntensity,
    alphaMultiplier: Float,
): DesktopLargeScreenGlassDecision {
    val lens = request.liquidGlassRequested &&
        request.windowsRendererSupported &&
        request.backdropReady &&
        !request.forceLowBlurBudget
    val passHaze = request.bottomBarBlurEnabled && request.hazeAvailable
    val passBackdrop = request.backdropReady && (lens || request.bottomBarBlurEnabled)
    val renderMode = when {
        lens -> HomeTopChromeRenderMode.LIQUID_GLASS_BACKDROP
        passHaze -> HomeTopChromeRenderMode.BLUR
        else -> HomeTopChromeRenderMode.PLAIN
    }
    val alpha = if (passHaze) {
        (BlurStyles.getBackgroundAlpha(effectiveIntensity) * alphaMultiplier).coerceIn(0f, 1f)
    } else {
        1f
    }
    return DesktopLargeScreenGlassDecision(
        renderMode = renderMode,
        passBackdrop = passBackdrop,
        passHaze = passHaze,
        liquidGlassEffectsEnabled = lens,
        backgroundAlpha = alpha,
        effectiveBlurIntensity = effectiveIntensity,
        // Leaving the flag on while the lens is withheld skips shouldUseAndroidNativeFloatingHazeBlur.
        suppressLiquidGlassPreference = request.liquidGlassRequested && !lens,
    )
}

private fun resolveTopChromeGlass(
    request: DesktopLargeScreenGlassRequest,
    effectiveIntensity: BlurIntensity,
    alphaMultiplier: Float,
): DesktopLargeScreenGlassDecision {
    // HomeScreen asks the header material mode with bottom-bar blur forced off.
    val material = resolveHomeTopChromeMaterialMode(
        isHeaderBlurEnabled = request.headerBlurEnabled,
        isBottomBarBlurEnabled = false,
        isLiquidGlassEnabled = request.liquidGlassRequested,
        isProgressiveTopBlurEnabled = request.progressiveTopBlurEnabled,
    )
    val glassSupported = request.windowsRendererSupported && !request.forceLowBlurBudget
    val renderMode = resolveHomeTopChromeRenderMode(
        materialMode = material,
        isGlassSupported = glassSupported,
        hasBackdrop = request.backdropReady,
        hasHazeState = request.hazeAvailable,
        allowHazeLiquidGlassFallback = glassSupported,
    )
    val liquidLens = renderMode == HomeTopChromeRenderMode.LIQUID_GLASS_BACKDROP
    val blurred = renderMode == HomeTopChromeRenderMode.BLUR ||
        renderMode == HomeTopChromeRenderMode.LIQUID_GLASS_HAZE
    val alpha = when (renderMode) {
        HomeTopChromeRenderMode.LIQUID_GLASS_BACKDROP,
        HomeTopChromeRenderMode.LIQUID_GLASS_HAZE,
        -> resolveHomeHeaderSurfaceAlpha(
            isGlassEnabled = true,
            blurEnabled = true,
            blurIntensity = effectiveIntensity,
        )
        HomeTopChromeRenderMode.BLUR ->
            (BlurStyles.getBackgroundAlpha(effectiveIntensity) * alphaMultiplier).coerceIn(0f, 1f)
        HomeTopChromeRenderMode.PLAIN -> resolveHomeHeaderSurfaceAlpha(
            isGlassEnabled = false,
            blurEnabled = false,
            blurIntensity = effectiveIntensity,
        )
    }
    return DesktopLargeScreenGlassDecision(
        renderMode = renderMode,
        passBackdrop = liquidLens,
        passHaze = blurred && request.hazeAvailable,
        // Haze liquid glass has no lens-on-backdrop path here. A null backdrop would
        // make the segmented control allocate a local layer that samples the bar.
        liquidGlassEffectsEnabled = liquidLens,
        backgroundAlpha = alpha,
        effectiveBlurIntensity = effectiveIntensity,
        suppressLiquidGlassPreference = request.liquidGlassRequested && !liquidLens,
    )
}
