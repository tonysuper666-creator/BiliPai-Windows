package com.android.purebilibili.feature.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.BottomBarLiquidGlassPreset
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.animation.DampedDragAnimationState
import com.android.purebilibili.core.ui.animation.DampedDragTrackingMode
import com.android.purebilibili.core.ui.animation.rememberDampedDragAnimationState
import com.android.purebilibili.core.ui.motion.AppMotionEasing
import com.android.purebilibili.core.ui.motion.BottomBarMotionSpec
import com.android.purebilibili.core.ui.motion.emphasizedEnterTween
import com.android.purebilibili.core.ui.motion.emphasizedExitTween
import com.android.purebilibili.core.ui.motion.softLandingSpring
import com.android.purebilibili.feature.home.components.liquid.rememberCombinedBackdrop
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.android.purebilibili.core.ui.blur.currentUnifiedBlurIntensity

internal enum class BottomBarLiquidOrientation {
    HORIZONTAL,
    VERTICAL
}


@Composable
internal fun BoxScope.BottomBarMatchedLiquidIndicator(
    visible: Boolean,
    dockContentAlpha: Float,
    indicatorTranslationXPx: Float,
    indicatorTranslationYPx: Float = 0f,
    indicatorPanelOffsetPx: Float,
    indicatorPanelOffsetYPx: Float = 0f,
    indicatorWidth: Dp,
    indicatorHeight: Dp,
    shellShape: Shape,
    liquidGlassPreset: BottomBarLiquidGlassPreset,
    contentBackdrop: Backdrop?,
    backdrop: Backdrop?,
    indicatorLensSpec: BottomBarBackdropPresetLensSpec,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
    effectivePressProgress: Float,
    indicatorIdleSurfaceColor: Color,
    glassEnabled: Boolean,
    indicatorEffectsEnabled: Boolean = glassEnabled,
    motionProgress: Float,
    velocityItemsPerSecond: Float,
    isDragging: Boolean,
    indicatorLayerScaleProgress: Float,
    indicatorLayerScaleTransform: BottomBarIndicatorLayerTransform? = null,
    dragScaleTarget: Float = BOTTOM_BAR_INDICATOR_DRAG_SCALE_TARGET,
    bottomBarMotionSpec: BottomBarMotionSpec,
    isDarkTheme: Boolean,
    indicatorSettleReboundTransform: BottomBarClickPulseTransform =
        BottomBarClickPulseTransform(scaleX = 1f),
    orientation: BottomBarLiquidOrientation = BottomBarLiquidOrientation.HORIZONTAL,
    indicatorAlignment: Alignment = Alignment.CenterStart,
    interactionModifier: Modifier = Modifier
) {
    // Miuix-only indicator path. Null backdrop degrades to solid surface inside the layer.
    BiliPaiMiuixBottomBarIndicatorLayer(
        visible = visible,
        dockContentAlpha = dockContentAlpha,
        indicatorTranslationXPx = indicatorTranslationXPx,
        indicatorTranslationYPx = indicatorTranslationYPx,
        indicatorPanelOffsetPx = indicatorPanelOffsetPx,
        indicatorPanelOffsetYPx = indicatorPanelOffsetYPx,
        indicatorWidth = indicatorWidth,
        indicatorHeight = indicatorHeight,
        shellShape = shellShape,
        liquidGlassPreset = liquidGlassPreset,
        contentBackdrop = contentBackdrop,
        backdrop = backdrop,
        indicatorLensSpec = indicatorLensSpec,
        liquidGlassTuning = liquidGlassTuning,
        effectivePressProgress = effectivePressProgress,
        indicatorIdleSurfaceColor = indicatorIdleSurfaceColor,
        glassEnabled = glassEnabled,
        indicatorEffectsEnabled = indicatorEffectsEnabled,
        motionProgress = motionProgress,
        velocityItemsPerSecond = velocityItemsPerSecond,
        isDragging = isDragging,
        indicatorLayerScaleProgress = indicatorLayerScaleProgress,
        indicatorLayerScaleTransform = indicatorLayerScaleTransform,
        dragScaleTarget = dragScaleTarget,
        bottomBarMotionSpec = bottomBarMotionSpec,
        isDarkTheme = isDarkTheme,
        swapMotionAxes = orientation == BottomBarLiquidOrientation.VERTICAL,
        indicatorAlignment = indicatorAlignment,
        interactionModifier = interactionModifier
    )
}
