// Source: app/src/main/java/com/android/purebilibili/core/ui/transition/PredictiveBackBackgroundPolicy.kt
// Original LF SHA256: 91e865c640c91c5c4d83d0e033fac3a42cda407bdae6a83821e6df9049f3f632
package com.android.purebilibili.core.ui.transition
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.BiliPaiNavRouteTransition
import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported
import kotlin.math.roundToInt

internal const val PREDICTIVE_BACK_BACKGROUND_COMMIT_DURATION_MS = 200

internal const val PREDICTIVE_BACK_BACKGROUND_CANCEL_DURATION_MS = 160

internal data class PredictiveBackBackgroundState(
    val progressProvider: () -> Float = { 0f },
    val targetKeyProvider: () -> BiliPaiNavKey? = { null },
    val motionTierProvider: () -> MotionTier = { MotionTier.Normal },
    val isLightBackgroundProvider: () -> Boolean = { false },
)

internal val LocalPredictiveBackBackgroundState = compositionLocalOf {
    PredictiveBackBackgroundState()
}

/**
 * 预测式返回手势进行中，把系统回退进度(0→1)映射为底层页模糊强度。
 *
 * 统一为随手势减弱(1→0)：底层页随返回露出来应逐渐清晰。
 * （旧版 CLASSIC_CARD 等用 0→1 增强模糊，体感与设置页相反，已统一。）
 */

internal fun resolvePredictiveBackGestureBlurProgress(
    backProgress: Float,
    routeTransition: BiliPaiNavRouteTransition? = null,
): Float {
    @Suppress("UNUSED_PARAMETER")
    val ignoredTransition = routeTransition
    val clamped = backProgress.coerceIn(0f, 1f)
    // 1:1 跟手线性渐变：手指拖出多少比例，底层模糊就线性退去多少；反向推回则等比恢复，手感丝滑连贯。
    return 1f - clamped
}

internal fun shouldApplyPredictiveBackGestureBlur(
    routeTransition: BiliPaiNavRouteTransition,
    predictiveBackEnabled: Boolean,
    gestureReturningVideoCard: Boolean,
    motionTier: MotionTier,
): Boolean {
    if (!predictiveBackEnabled) return false
    if (gestureReturningVideoCard) return false
    if (motionTier == MotionTier.Reduced) return false
    if (routeTransition == BiliPaiNavRouteTransition.NO_OP_SHARED_ELEMENT) return false
    // 设置 iOS 预测返回已靠横滑露出完整底层页；再叠满屏 GPU 模糊会起手发灰、跟手发沉。
    if (routeTransition == BiliPaiNavRouteTransition.SETTINGS_IOS_PUSH_POP) return false
    return routeTransition == BiliPaiNavRouteTransition.CLASSIC_CARD ||
        routeTransition == BiliPaiNavRouteTransition.BOTTOM_BAR_SIBLING_POP ||
        routeTransition == BiliPaiNavRouteTransition.LIGHT_SIBLING_POP
}

internal fun shouldApplyPredictiveBackBlurToRoute(
    entryKey: BiliPaiNavKey,
    targetBackKey: BiliPaiNavKey?,
    videoCardBackgroundApplied: Boolean = false,
): Boolean {
    return !videoCardBackgroundApplied && targetBackKey != null && entryKey == targetBackKey
}

internal fun resolvePredictiveBackCommitBlurDurationMs(startProgress: Float): Int {
    val clamped = startProgress.coerceIn(0f, 1f)
    if (clamped <= 0f) {
        return PREDICTIVE_BACK_BACKGROUND_CANCEL_DURATION_MS
    }
    return (PREDICTIVE_BACK_BACKGROUND_COMMIT_DURATION_MS * clamped)
        .roundToInt()
        .coerceIn(1, PREDICTIVE_BACK_BACKGROUND_COMMIT_DURATION_MS)
}

private class PredictiveBackBlurFrameCache {
    private var lastProgress = Float.NaN
    private var lastMotionTier: MotionTier? = null
    private var lastIsLightBackground: Boolean? = null
    private var cached = PredictiveBackBlurFrame(blurRadiusPx = 0f)

    fun resolve(
        progress: Float,
        motionTier: MotionTier,
        isLightBackground: Boolean,
    ): PredictiveBackBlurFrame {
        if (
            progress != lastProgress ||
            motionTier != lastMotionTier ||
            isLightBackground != lastIsLightBackground
        ) {
            lastProgress = progress
            lastMotionTier = motionTier
            lastIsLightBackground = isLightBackground
            cached = resolvePredictiveBackBlurFrame(
                progress = progress,
                motionTier = motionTier,
                isLightBackground = isLightBackground,
            )
        }
        return cached
    }
}

internal fun Modifier.predictiveBackBackgroundEffect(
    progressProvider: () -> Float,
    motionTierProvider: () -> MotionTier = { MotionTier.Normal },
    isLightBackgroundProvider: () -> Boolean = { false },
): Modifier {
    val frameCache = PredictiveBackBlurFrameCache()
    return graphicsLayer {
        val frame = frameCache.resolve(
            progressProvider(),
            motionTierProvider(),
            isLightBackgroundProvider(),
        )
        renderEffect = if (desktopDetailRenderEffectsSupported() && frame.blurRadiusPx > 0.01f) {
            BlurEffect(
                    frame.blurRadiusPx,
                    frame.blurRadiusPx,
                    TileMode.Clamp,
                )
        } else {
            null
        }
    }.drawWithContent {
        drawContent()
        val frame = frameCache.resolve(
            progressProvider(),
            motionTierProvider(),
            isLightBackgroundProvider(),
        )
        if (frame.separationTintAlpha > 0.001f) {
            val tintColor = if (frame.useLightSeparationTint) Color.White else Color.Black
            drawRect(tintColor.copy(alpha = frame.separationTintAlpha))
        }
    }
}

