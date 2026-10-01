// Original source app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarMatchedLiquidChrome.kt
// OriginalLF_SHA256 a952a41fc91d694bdc0410f3bba89806acf0b2f33272c071aca97500bcbeb5ad
package com.android.purebilibili.feature.home.components

import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.store.BottomBarLiquidGlassPreset
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.motion.AppMotionEasing
import com.android.purebilibili.core.ui.blur.currentUnifiedBlurIntensity
import com.android.purebilibili.feature.home.components.liquid.rememberCombinedBackdrop
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.*

@Immutable
internal data class LiquidGlassRenderConfig(
    val tuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
    val preset: BottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,
)

internal val LocalLiquidGlassRenderConfig = staticCompositionLocalOf {
    LiquidGlassRenderConfig()
}

internal fun Modifier.bottomBarMatchedCaptureOverflow(inset: Dp): Modifier =
    bottomBarMatchedCaptureOverflow(
        horizontalInset = inset,
        verticalInset = inset,
    )

internal fun Modifier.bottomBarMatchedCaptureOverflow(
    horizontalInset: Dp,
    verticalInset: Dp,
): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    } else {
        val horizontalInsetPx = horizontalInset.roundToPx().coerceAtLeast(0)
        val verticalInsetPx = verticalInset.roundToPx().coerceAtLeast(0)
        val expandedWidth = (constraints.maxWidth.toLong() + horizontalInsetPx.toLong() * 2L)
            .coerceAtMost(Constraints.Infinity.toLong())
            .toInt()
        val expandedHeight = (constraints.maxHeight.toLong() + verticalInsetPx.toLong() * 2L)
            .coerceAtMost(Constraints.Infinity.toLong())
            .toInt()
        val placeable = measurable.measure(
            Constraints.fixed(
                width = expandedWidth,
                height = expandedHeight
            )
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeRelative(-horizontalInsetPx, -verticalInsetPx)
        }
    }
}

/**
 * UI-only interaction state shared by the home bottom bar and every opted-in liquid Chrome.
 * Business selection remains owned by the caller.
 */

@Composable
internal fun BottomBarMatchedLiquidDock(
    backdrop: Backdrop?,
    containerColor: Color,
    shape: Shape,
    blurEnabled: Boolean,
    glassEnabled: Boolean,
    drawShellLens: Boolean = true,
    shellLensIntensity: Float = 1f,
    blurRadius: Dp,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    motionTier: MotionTier = MotionTier.Normal,
    isTransitionRunning: Boolean = false,
    forceLowBlurBudget: Boolean = false,
    liquidGlassPreset: BottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
    isScrollInProgressProvider: () -> Boolean = { false },
    materialScrollProgressOverride: Float? = null,
    materialMotionProgress: Float = 0f,
    materialPressProgress: Float = 0f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .bottomBarMatchedLiquidDockSurface(
                    shape = shape,
                    backdrop = backdrop,
                    containerColor = containerColor,
                    blurEnabled = blurEnabled,
                    glassEnabled = glassEnabled,
                    drawShellLens = drawShellLens,
                    shellLensIntensity = shellLensIntensity,
                    blurRadius = blurRadius,
                    hazeState = hazeState,
                    motionTier = motionTier,
                    isTransitionRunning = isTransitionRunning,
                    forceLowBlurBudget = forceLowBlurBudget,
                    liquidGlassPreset = liquidGlassPreset,
                    liquidGlassTuning = liquidGlassTuning,
                    isScrollInProgressProvider = isScrollInProgressProvider,
                    materialScrollProgressOverride = materialScrollProgressOverride,
                    materialMotionProgress = materialMotionProgress,
                    materialPressProgress = materialPressProgress
                )
        )
        content()
    }
}

@Composable
internal fun Modifier.bottomBarMatchedLiquidDockSurface(
    backdrop: Backdrop?,
    containerColor: Color,
    shape: Shape,
    blurEnabled: Boolean,
    glassEnabled: Boolean,
    blurRadius: Dp,
    hazeState: HazeState? = null,
    motionTier: MotionTier = MotionTier.Normal,
    isTransitionRunning: Boolean = false,
    forceLowBlurBudget: Boolean = false,
    liquidGlassPreset: BottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
    isScrollInProgressProvider: () -> Boolean = { false },
    materialScrollProgressOverride: Float? = null,
    materialMotionProgress: Float = 0f,
    materialPressProgress: Float = 0f,
    drawShellLens: Boolean = true,
    shellLensIntensity: Float = 1f
): Modifier = composed {
    val isScrolling = isScrollInProgressProvider()
    val animatedScrollProgress by animateFloatAsState(
        targetValue = if (isScrolling) 1f else 0f,
        animationSpec = tween(
            durationMillis = resolveBottomBarMaterialScrollAnimationDurationMillis(isScrolling),
            easing = AppMotionEasing.Continuity
        ),
        label = "bottomBarMatchedMaterialScrollProgress"
    )
    val materialScrollProgress = materialScrollProgressOverride ?: animatedScrollProgress
    // Miuix-only: no legacy fallback. Null backdrop degrades inside biliPaiMiuixFloatingDockSurface.
    biliPaiMiuixFloatingDockSurface(
        shape = shape,
        backdrop = backdrop,
        containerColor = containerColor,
        blurEnabled = blurEnabled,
        glassEnabled = glassEnabled,
        drawShellLens = drawShellLens,
        shellLensIntensity = shellLensIntensity,
        blurRadius = blurRadius,
        hazeState = hazeState,
        motionTier = motionTier,
        isTransitionRunning = isTransitionRunning,
        forceLowBlurBudget = forceLowBlurBudget,
        liquidGlassPreset = liquidGlassPreset,
        liquidGlassTuning = liquidGlassTuning,
        isScrolling = isScrolling,
        materialScrollProgress = materialScrollProgress,
        materialMotionProgress = materialMotionProgress,
        materialPressProgress = materialPressProgress
    )
}

/**
 * Content-slot entry point for search fields, comment/action bars, and other inline chrome.
 * When global reuse is disabled, [content] is emitted unchanged.
 *
 * 黑虾线防回归规则：
 * 1. 黑/亮细线来自短胶囊使用 64dp 底栏的满强度折射，导致上下 refraction 在中线相撞，
 *    或来自一个液态壳内部再次采样、折射自身的嵌套 lens。
 * 2. 多个视觉上独立的胶囊必须各自拥有 backdrop 和 lens；不要为了消线把它们合成一个长壳。
 * 3. 独立短胶囊不要直接关闭 lens，否则会丢失液态玻璃折射。应传入
 *    `resolveFloatingDockGeometryScale(actualHeightDp)`，按实际高度相对 64dp 基准缩放。
 * 4. 只有已经位于液态外壳内部、且不应再次折射的内容层才使用 [drawShellLens] = false。
 * 5. lens 开启时必须保留 capture overflow / safe inset，避免折射采样越界产生黑边。
 *
 * @param drawShellLens 独立液态表面应保留 lens；只有确实嵌套在另一个液态壳内的内容层才关闭。
 * @param shellLensIntensity 矮 dock 应按实际高度相对 64dp 基准缩放 lens，避免上下折射边沿相撞成虾线。
 */

@Composable
internal fun BottomBarMatchedReusableLiquidDock(
    shape: Shape,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    liquidGlassEffectsEnabled: Boolean = true,
    /**
     * Allowlisted callers only: home search field, comment [BottomInputBar], and dynamic
     * composer/detail chrome that explicitly follows the same floating-dock contract.
     * Other chrome must leave this false.
     */
    reuseEnabled: Boolean = false,
    useNeutralLiquidContainer: Boolean = false,
    drawShellLens: Boolean = true,
    shellLensIntensity: Float = 1f,
    isScrollInProgressProvider: () -> Boolean = { false },
    content: @Composable BoxScope.(liquidChromeActive: Boolean) -> Unit
) {
    val reuseAllowed = LocalAppThemeConfig.current.liquidGlassEnabled
    if (!reuseEnabled || !reuseAllowed || !liquidGlassEffectsEnabled) {
        Box(modifier = modifier) {
            content(false)
        }
        return
    }

    val renderConfig = LocalLiquidGlassRenderConfig.current
    val glassEnabled = resolveAndroidNativeBottomBarGlassEnabled(
        liquidGlassEnabled = reuseEnabled && reuseAllowed,
        blurEnabled = true
    )
    val localBackdrop = rememberLayerBackdrop()
    val effectiveBackdrop = if (backdrop != null) {
        rememberCombinedBackdrop(localBackdrop, backdrop)
    } else {
        localBackdrop
    }
    val isDarkTheme = isSystemInDarkTheme()
    val blurIntensity = currentUnifiedBlurIntensity()
    val tuning = resolveAndroidNativeBottomBarTuning(
        blurEnabled = true,
        darkTheme = isDarkTheme
    )
    val liquidGlassTuning = renderConfig.tuning
    val containerColor = if (useNeutralLiquidContainer && glassEnabled) {
        resolveBiliPaiBottomBarContainerColor(
            darkTheme = isDarkTheme,
            liquidGlassTuning = liquidGlassTuning,
        )
    } else {
        resolveAndroidNativeFloatingBottomBarContainerColor(
            surfaceColor = AppSurfaceTokens.cardContainer(),
            tuning = tuning,
            glassEnabled = glassEnabled,
            blurEnabled = true,
            blurIntensity = blurIntensity,
            liquidGlassPreset = renderConfig.preset,
            liquidGlassTuning = liquidGlassTuning,
        )
    }
    // lens 的 refraction 会读取壳体边界外像素；开启时必须预留安全采样区。
    // 若调用者是短胶囊，应缩放 shellLensIntensity，而不是通过关闭 lens 跳过这里。
    val captureSafeInset = if (drawShellLens) {
        resolveBottomBarCaptureSafeInsetDp(
            indicatorWidthDp = 0f,
            refractionHeightDp = liquidGlassTuning.refractionHeight,
            refractionAmountDp = liquidGlassTuning.refractionAmount,
            panelOffsetDp = 0f
        ).dp
    } else {
        AppSpacingTokens.None
    }

    Box(modifier = modifier) {
        if (drawShellLens) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .bottomBarMatchedCaptureOverflow(captureSafeInset)
                    .alpha(0f)
                    .layerBackdrop(localBackdrop)
                    .background(AppSurfaceTokens.background())
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0f)
                    .layerBackdrop(localBackdrop)
                    .background(AppSurfaceTokens.background())
            )
        }
        BottomBarMatchedLiquidDock(
            backdrop = effectiveBackdrop,
            containerColor = containerColor,
            shape = shape,
            blurEnabled = true,
            glassEnabled = glassEnabled,
            drawShellLens = drawShellLens,
            shellLensIntensity = shellLensIntensity,
            blurRadius = tuning.shellBlurRadiusDp.dp,
            modifier = Modifier.matchParentSize(),
            liquidGlassPreset = renderConfig.preset,
            liquidGlassTuning = liquidGlassTuning,
            isScrollInProgressProvider = isScrollInProgressProvider
        ) {}
        content(true)
    }
}

/**
 * Exact moving indicator used by the home floating bottom bar. Orientation only swaps axes.
 */
