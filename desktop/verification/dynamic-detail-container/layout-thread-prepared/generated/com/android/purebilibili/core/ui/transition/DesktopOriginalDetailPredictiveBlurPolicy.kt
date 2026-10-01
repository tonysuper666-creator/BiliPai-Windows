// Original source app/src/main/java/com/android/purebilibili/core/ui/transition/PredictiveBackBackgroundPolicy.kt
// LF SHA256 91e865c640c91c5c4d83d0e033fac3a42cda407bdae6a83821e6df9049f3f632
package com.android.purebilibili.core.ui.transition
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported
import kotlin.math.roundToInt

private const val PREDICTIVE_BACK_MAX_BLUR_RADIUS_PX_DARK = 28f

private const val PREDICTIVE_BACK_MAX_BLUR_RADIUS_PX_LIGHT = 22f

private const val PREDICTIVE_BACK_BLUR_QUANTUM_PX = 4f

private const val PREDICTIVE_BACK_LIGHT_SEPARATION_TINT_ALPHA = 0.05f

/** 与 SCALE handler 提交退出动画时长对齐。 */

internal data class PredictiveBackBlurFrame(
    val blurRadiusPx: Float,
    val separationTintAlpha: Float = 0f,
    val useLightSeparationTint: Boolean = false,
)

internal fun resolvePredictiveBackMaxBlurRadiusPx(isLightBackground: Boolean): Float {
    return if (isLightBackground) {
        PREDICTIVE_BACK_MAX_BLUR_RADIUS_PX_LIGHT
    } else {
        PREDICTIVE_BACK_MAX_BLUR_RADIUS_PX_DARK
    }
}

internal fun resolvePredictiveBackSeparationTintAlpha(
    progress: Float,
    isLightBackground: Boolean,
): Float {
    if (!isLightBackground) return 0f
    return PREDICTIVE_BACK_LIGHT_SEPARATION_TINT_ALPHA * progress.coerceIn(0f, 1f)
}

internal fun resolvePredictiveBackBlurFrame(
    progress: Float,
    motionTier: MotionTier = MotionTier.Normal,
    isLightBackground: Boolean = false,
): PredictiveBackBlurFrame {
    val clamped = progress.coerceIn(0f, 1f)
    val maxBlurRadiusPx = resolvePredictiveBackMaxBlurRadiusPx(isLightBackground)
    val rawBlurRadiusPx = if (
        clamped > 0f &&
        motionTier != MotionTier.Reduced &&
        desktopDetailRenderEffectsSupported()
    ) {
        maxBlurRadiusPx * clamped
    } else {
        0f
    }
    return PredictiveBackBlurFrame(
        blurRadiusPx = quantizePredictiveBackBlurRadius(rawBlurRadiusPx, maxBlurRadiusPx),
        separationTintAlpha = resolvePredictiveBackSeparationTintAlpha(
            progress = clamped,
            isLightBackground = isLightBackground,
        ),
        useLightSeparationTint = isLightBackground,
    )
}

private fun quantizePredictiveBackBlurRadius(
    radiusPx: Float,
    maxBlurRadiusPx: Float,
): Float {
    if (radiusPx <= 0f) return 0f
    return ((radiusPx / PREDICTIVE_BACK_BLUR_QUANTUM_PX).roundToInt() *
        PREDICTIVE_BACK_BLUR_QUANTUM_PX)
        .coerceIn(0f, maxBlurRadiusPx)
}
