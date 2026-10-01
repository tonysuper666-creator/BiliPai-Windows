// Original source app/src/main/java/com/android/purebilibili/feature/home/components/BottomBar.kt
// OriginalLF_SHA256 7c5951e2518ecc12162faa34e3e066a61ce4ea58e4713e3c5c67ff60587dcf52
package com.android.purebilibili.feature.home.components

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow as ComposeShadow
import androidx.compose.ui.unit.*
import androidx.compose.ui.util.lerp
import com.android.purebilibili.core.store.BottomBarLiquidGlassPreset
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.blur.*
import com.android.purebilibili.feature.home.HomeVisualPalette
import com.android.purebilibili.feature.home.components.liquid.InnerShadow as MiuixInnerShadow
import com.android.purebilibili.feature.home.components.liquid.innerShadow as miuixInnerShadow
import com.android.purebilibili.feature.home.components.liquid.lens as miuixLens
import com.android.purebilibili.feature.home.components.liquid.vibrancy as miuixVibrancy
import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
import top.yukonga.miuix.kmp.blur.blur as miuixBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop as miuixDrawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.*
import top.yukonga.miuix.kmp.blur.highlight.Highlight as MiuixHighlight

private val iosIndicatorSpecular: MiuixHighlight = MiuixHighlight(
    width = AppSpacingTokens.Micro / 2,
    alpha = 1f,
    style = BloomStroke(
        color = OpticalContrastPalette.Highlight.copy(alpha = 0.12f),
        innerBlurRadius = AppSpacingTokens.Micro,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = OpticalContrastPalette.Highlight,
            intensity = 1f
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = OpticalContrastPalette.Highlight,
            intensity = 0.4f
        ),
        dualPeak = true
    )
)

/**
 * 底部导航项枚举。图标由当前主题的导航图标策略统一解析。
 */

internal data class AndroidNativeBottomBarTuning(
    val cornerRadiusDp: Float,
    val shellShadowElevationDp: Float,
    val shellBlurRadiusDp: Float,
    val shellSurfaceAlpha: Float,
    val outerHorizontalPaddingDp: Float,
    val innerHorizontalPaddingDp: Float,
    val indicatorHeightDp: Float,
    val indicatorLensRadiusDp: Float
)

internal fun resolveAndroidNativeBottomBarTuning(
    blurEnabled: Boolean,
    darkTheme: Boolean,
): AndroidNativeBottomBarTuning {
    return AndroidNativeBottomBarTuning(
        cornerRadiusDp = 32f,
        shellShadowElevationDp = if (darkTheme) 0.6f else 0.8f,
        shellBlurRadiusDp = if (blurEnabled) 12f else 0f,
        shellSurfaceAlpha = if (blurEnabled) 0.4f else 1f,
        outerHorizontalPaddingDp = 20f,
        innerHorizontalPaddingDp = 4f,
        indicatorHeightDp = 56f,
        indicatorLensRadiusDp = 24f
    )
}

internal fun resolveAndroidNativeBottomBarContainerColor(
    surfaceColor: Color,
    tuning: AndroidNativeBottomBarTuning,
    glassEnabled: Boolean,
    liquidGlassPreset: BottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f)
): Color {
    return resolveBottomBarGlassMaterialContainerColor(
        surfaceColor = surfaceColor,
        preset = liquidGlassPreset,
        glassEnabled = glassEnabled,
        fallbackAlpha = tuning.shellSurfaceAlpha,
        liquidGlassTuning = liquidGlassTuning
    )
}

internal fun resolveAndroidNativeFloatingBottomBarContainerColor(
    surfaceColor: Color,
    tuning: AndroidNativeBottomBarTuning,
    glassEnabled: Boolean,
    blurEnabled: Boolean,
    blurIntensity: com.android.purebilibili.core.ui.blur.BlurIntensity,
    liquidGlassPreset: BottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
    globalWallpaperVisible: Boolean = false
): Color {
    val resolvedColor = if (glassEnabled) {
        resolveAndroidNativeBottomBarContainerColor(
            surfaceColor = surfaceColor,
            tuning = tuning,
            glassEnabled = true,
            liquidGlassPreset = liquidGlassPreset,
            liquidGlassTuning = liquidGlassTuning
        )
    } else {
        resolveBottomBarSurfaceColor(
            surfaceColor = surfaceColor,
            blurEnabled = blurEnabled,
            blurIntensity = blurIntensity
        )
    }
    if (!globalWallpaperVisible || resolvedColor.alpha == 0f) return resolvedColor
    val protectiveColor = resolveGlobalWallpaperProtectiveColor(
        baseColor = surfaceColor,
        lightAlpha = 0.68f,
        darkAlpha = 0.74f
    )
    return resolvedColor.copy(alpha = maxOf(resolvedColor.alpha, protectiveColor.alpha))
}

internal fun resolveAndroidNativeBottomBarGlassEnabled(
    liquidGlassEnabled: Boolean,
    blurEnabled: Boolean,
): Boolean = liquidGlassEnabled && desktopDetailRenderEffectsSupported()

internal fun shouldUseAndroidNativeFloatingHazeBlur(
    blurEnabled: Boolean,
    glassEnabled: Boolean,
    hasHazeState: Boolean,
): Boolean = blurEnabled &&
    !glassEnabled &&
    hasHazeState &&
    desktopDetailRenderEffectsSupported()

internal fun shouldRenderBottomBarLiquidGlassEffects(
    glassEnabled: Boolean,
    forceLowBlurBudget: Boolean,
): Boolean = glassEnabled && !forceLowBlurBudget

internal fun Modifier.biliPaiMiuixFloatingDockSurface(
    shape: androidx.compose.ui.graphics.Shape,
    backdrop: MiuixBackdrop?,
    containerColor: Color,
    blurEnabled: Boolean,
    glassEnabled: Boolean,
    drawShellLens: Boolean = true,
    /** Scales shell lens refraction for short docks; 1f = full strength. */
    shellLensIntensity: Float = 1f,
    blurRadius: Dp,
    hazeState: HazeState?,
    motionTier: MotionTier,
    isTransitionRunning: Boolean,
    forceLowBlurBudget: Boolean,
    liquidGlassPreset: BottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,
    isScrolling: Boolean = false,
    materialScrollProgress: Float = 0f,
    materialMotionProgress: Float = 0f,
    materialPressProgress: Float = 0f,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f)
): Modifier = composed {
    // The global liquid-glass switch owns primary navigation chrome. Runtime jank
    // downgrades may trim secondary effects, but must not silently turn this surface solid.
    val effectiveForceLowBlurBudget = forceLowBlurBudget
    val isDarkTheme = resolveBottomBarDarkTheme(AppSurfaceTokens.background())
    val renderGlassEffects = shouldRenderBottomBarLiquidGlassEffects(
        glassEnabled = glassEnabled,
        forceLowBlurBudget = effectiveForceLowBlurBudget,
    )
    val useHazeBlur = shouldUseAndroidNativeFloatingHazeBlur(
        glassEnabled = renderGlassEffects,
        blurEnabled = blurEnabled,
        hasHazeState = hazeState != null
    )
    val materialSpec = resolveBottomBarGlassMaterialSpec(
        preset = liquidGlassPreset,
        isDarkTheme = isDarkTheme,
        isScrolling = isScrolling,
        scrollProgress = materialScrollProgress,
        glassEnabled = renderGlassEffects,
        motionProgress = materialMotionProgress,
        pressProgress = materialPressProgress,
        liquidGlassTuning = liquidGlassTuning
    )
    val baseHighlight = if (renderGlassEffects) {
        rememberBiliPaiGravityHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    } else {
        null
    }
    val effectiveShellLensIntensity = shellLensIntensity.coerceIn(0f, 1f)
    val hasBackdrop = backdrop != null && (renderGlassEffects || blurEnabled)
    val hasHazeBlur = !hasBackdrop && useHazeBlur && hazeState != null

    this
        .then(
            if (hasHazeBlur) {
                Modifier.unifiedBlur(
                    hazeState = hazeState,
                    shape = shape,
                    surfaceType = BlurSurfaceType.BOTTOM_BAR,
                    motionTier = motionTier,
                    isScrolling = false,
                    isTransitionRunning = isTransitionRunning,
                    forceLowBudget = effectiveForceLowBlurBudget
                )
            } else {
                Modifier
            }
        )
        .run {
            if (hasBackdrop) {
                this
                    .dropShadow(
                        shape = shape,
                        shadow = ComposeShadow(
                            radius = AppSpacingTokens.Small + AppSpacingTokens.Micro,
                            color = OpticalContrastPalette.Shadow,
                            alpha = (if (isDarkTheme) 0.2f else 0.1f) *
                                materialSpec.shadowAlphaScale
                        )
                    )
                    .miuixDrawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            if (renderGlassEffects) {
                                val resolvedShellRefractionAmountDp = if (drawShellLens) {
                                    materialSpec.shellRefractionAmountDp *
                                        effectiveShellLensIntensity
                                } else {
                                    0f
                                }
                                padding = maxOf(
                                    padding,
                                    resolveFloatingDockEffectPaddingDp(
                                        refractionAmountDp = resolvedShellRefractionAmountDp,
                                        pressBloomDp = AppSpacingTokens.Large.value,
                                    ).dp.toPx(),
                                )
                                if (materialSpec.vibrancy) {
                                    miuixVibrancy(liquidGlassTuning.saturation)
                                }
                                val resolvedBlurRadius =
                                    materialSpec.blurRadiusDp?.dp ?: AppSpacingTokens.ExtraSmall
                                miuixBlur(resolvedBlurRadius.toPx(), resolvedBlurRadius.toPx())
                                if (
                                    drawShellLens &&
                                    effectiveShellLensIntensity > 0f &&
                                    materialSpec.shellRefractionHeightDp > 0f &&
                                    materialSpec.shellRefractionAmountDp > 0f
                                ) {
                                    miuixLens(
                                        refractionHeight = (
                                            materialSpec.shellRefractionHeightDp *
                                                effectiveShellLensIntensity
                                            ).dp.toPx(),
                                        refractionAmount = (
                                            materialSpec.shellRefractionAmountDp *
                                                effectiveShellLensIntensity
                                            ).dp.toPx(),
                                        chromaticAberration = materialSpec.shellChromaticAberration
                                    )
                                }
                            } else if (blurEnabled) {
                                val resolvedBlurRadius = maxOf(blurRadius, 25.dp)
                                val radiusPx = resolvedBlurRadius.toPx()
                                miuixBlur(radiusPx, radiusPx)
                            }
                        },
                        highlight = {
                            baseHighlight?.value?.copy(
                                alpha = if (renderGlassEffects) {
                                    0.75f * materialSpec.highlightWidthScale *
                                        effectiveShellLensIntensity
                                } else {
                                    0f
                                }
                            )
                        },
                        layerBlock = if (renderGlassEffects) {
                            {
                                val width = size.width.coerceAtLeast(1f)
                                val s = lerp(1f, 1f + AppSpacingTokens.Large.toPx() / width, materialPressProgress)
                                scaleX = s
                                scaleY = s
                            }
                        } else null,
                        onDrawSurface = {
                            if (renderGlassEffects) {
                                drawRect(containerColor)
                                if (liquidGlassTuning.contentReadabilityScrimAlpha > 0f) {
                                    drawRect(
                                        (if (isDarkTheme) Color.Black else Color.White).copy(
                                            alpha = liquidGlassTuning.contentReadabilityScrimAlpha
                                        )
                                    )
                                }
                                if (materialSpec.foregroundTint.alpha > 0f) {
                                    drawRect(materialSpec.foregroundTint)
                                }
                            } else {
                                drawRect(containerColor.copy(alpha = 0.65f))
                            }
                        }
                    )
                    .run {
                        val innerRimGlow = materialSpec.innerRimGlow
                        if (renderGlassEffects && innerRimGlow != null) {
                            miuixInnerShadow(shape = shape) {
                                MiuixInnerShadow(
                                    radius = innerRimGlow.radiusDp.dp,
                                    color = if (isDarkTheme) {
                                        OpticalContrastPalette.Highlight
                                    } else {
                                        OpticalContrastPalette.Shadow
                                    },
                                    alpha = innerRimGlow.alpha
                                )
                            }
                        } else {
                            this
                        }
                    }
            } else if (hasHazeBlur) {
                this
                    .dropShadow(
                        shape = shape,
                        shadow = ComposeShadow(
                            radius = AppSpacingTokens.Small + AppSpacingTokens.Micro,
                            color = OpticalContrastPalette.Shadow,
                            alpha = (if (isDarkTheme) 0.2f else 0.1f) *
                                materialSpec.shadowAlphaScale
                        )
                    )
                    .background(containerColor.copy(alpha = 0.65f), shape)
            } else {
                this
                    .dropShadow(
                        shape = shape,
                        shadow = ComposeShadow(
                            radius = AppSpacingTokens.Small + AppSpacingTokens.Micro,
                            color = OpticalContrastPalette.Shadow,
                            alpha = if (isDarkTheme) 0.2f else 0.12f
                        )
                    )
                    .background(containerColor.copy(alpha = 1f), shape)
            }
        }
        .clip(shape)
}

internal fun resolveBiliPaiBottomBarContainerColor(
    darkTheme: Boolean,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f)
): Color {
    val surfaceContainer = if (darkTheme) {
        HomeVisualPalette.BiliPaiDarkSurface
    } else {
        OpticalContrastPalette.Highlight
    }
    return surfaceContainer.copy(alpha = liquidGlassTuning.surfaceAlpha)
}

internal fun resolveBottomBarDarkTheme(backgroundColor: Color): Boolean {
    return backgroundColor.luminance() < 0.5f
}

internal const val BOTTOM_BAR_INDICATOR_DRAG_SCALE_TARGET =
    com.android.purebilibili.core.ui.BottomBarReferencePressedScale

internal fun resolveBottomBarCaptureSafeInsetDp(
    indicatorWidthDp: Float,
    refractionHeightDp: Float,
    refractionAmountDp: Float,
    panelOffsetDp: Float,
    dragScaleTarget: Float = BOTTOM_BAR_INDICATOR_DRAG_SCALE_TARGET
): Float {
    val scaleOverflowDp = (
        indicatorWidthDp.coerceAtLeast(0f) *
            (dragScaleTarget.coerceAtLeast(1f) - 1f) /
            2f
        )
    return scaleOverflowDp +
        maxOf(refractionHeightDp, refractionAmountDp).coerceAtLeast(0f) +
        kotlin.math.abs(panelOffsetDp)
}

internal fun resolveBottomBarSurfaceColor(
    surfaceColor: Color,
    blurEnabled: Boolean,
    blurIntensity: com.android.purebilibili.core.ui.blur.BlurIntensity
): Color {
    val alpha = if (blurEnabled) {
        BlurStyles.getBackgroundAlpha(blurIntensity)
    } else {
        return surfaceColor
    }
    return surfaceColor.copy(alpha = alpha)
}
