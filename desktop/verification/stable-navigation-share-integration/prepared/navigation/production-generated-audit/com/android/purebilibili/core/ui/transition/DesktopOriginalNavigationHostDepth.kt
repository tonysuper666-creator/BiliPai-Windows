// Source: app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionHostDepthLayer.kt
// Original LF SHA256: 2e5da7bef26c7aa839775a2a123b5b14f528db1b1e999748dccc924a6db6d02a
package com.android.purebilibili.core.ui.transition
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.bilipai.desktop.ui.LocalDesktopNavigationHostEnvironment
import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported

@Composable
internal fun VideoCardTransitionHostDepthLayer(
    enabled: Boolean,
    snapshotHandle: VideoCardTransitionSnapshotHandle,
    progressProvider: () -> Float,
    phaseProvider: () -> VideoCardTransitionBackgroundPhase,
    exposureProvider: () -> VideoCardTransitionExposure,
    isGestureRestoreInProgressProvider: () -> Boolean = { false },
    motionTierProvider: () -> MotionTier = { MotionTier.Normal },
    isLightBackgroundProvider: () -> Boolean = { false },
    realtimeBlurEnabledProvider: () -> Boolean = { false },
    scaleReductionProvider: () -> Float = { VIDEO_CARD_TRANSITION_BACKGROUND_SCALE_REDUCTION },
    sourceBoundsProvider: () -> Rect? = { null },
    modifier: Modifier = Modifier,
) {
    val contentLayer = snapshotHandle.contentLayer
    val snapshotState = snapshotHandle.state
    if (!enabled) {
        SideEffect { snapshotHandle.clearRenderEffect() }
        return
    }
    val platform = LocalDesktopNavigationHostEnvironment.current
    val platformDensity = LocalDensity.current
    var deviceCornerRadiusPx by remember { mutableFloatStateOf(0f) }
    SideEffect {
        deviceCornerRadiusPx = platform.cornerRadiusQuery()?.let { with(platformDensity) { it.toPx() } } ?: 0f
        if (!realtimeBlurEnabledProvider()) snapshotHandle.clearRenderEffect()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                // Host 层无子内容；只绘制冻结快照。
                val exposure = exposureProvider()
                val motionTier = motionTierProvider()
                val realtimeBlur = realtimeBlurEnabledProvider()
                if (
                    !shouldPaintHostOwnedDepthLayer(
                        exposure = exposure,
                        hasRecordedContent = snapshotState.hasRecordedContent,
                        displayListStale = snapshotState.displayListStale,
                        motionTier = motionTier,
                        realtimeBlurEnabled = realtimeBlur,
                    )
                ) {
                    return@drawWithContent
                }
                val phase = phaseProvider()
                // SettledHidden：详情盖住时仍保持满糊，手势首帧无「先清晰再糊」跳变。
                val progress = resolveHostOwnedDepthProgress(
                    exposure = exposure,
                    liveProgress = progressProvider(),
                )
                val frame = snapshotState.frameCache.resolve(
                    progress = progress,
                    phase = phase,
                    motionTier = motionTier,
                    isLightBackground = isLightBackgroundProvider(),
                    isGestureRestoreInProgress = isGestureRestoreInProgressProvider(),
                    density = density,
                    deviceCornerRadiusPx = deviceCornerRadiusPx,
                    scaleReduction = scaleReductionProvider(),
                )
                applyVideoCardTransitionSnapshotFrame(
                    contentLayer = contentLayer,
                    snapshotState = snapshotState,
                    frame = frame,
                    canvasSize = size,
                    sourceBounds = sourceBoundsProvider(),
                )
                if (shouldDrawVideoCardTransitionScaleGapFill(frame.contentScale)) {
                    drawRect(
                        resolveVideoCardTransitionScaleGapFillColor(
                            isLightBackground = frame.useLightScrimTint,
                            scrimAlpha = frame.scrimAlpha,
                        )
                    )
                }
                val frozenLayerAlpha = resolveVideoCardTransitionFrozenLayerAlpha(
                    exposure = exposure,
                    depthProgress = progress,
                )
                contentLayer.alpha = frozenLayerAlpha
                if (frozenLayerAlpha > 0.001f) {
                    drawLayer(contentLayer)
                    VideoCardTransitionDiagnostics.onSourceLayerDrawn()
                }
                if (frame.scrimAlpha > 0.001f) {
                    val scrimColor = if (frame.useLightScrimTint) {
                        VIDEO_CARD_TRANSITION_LIGHT_SCRIM_TINT
                    } else {
                        Color.Black
                    }
                    drawRect(scrimColor.copy(alpha = frame.scrimAlpha))
                }
            },
    )
}

/**
 * Host 层何时绘制：有**可用**冻结内容时。
 *
 * - stale / 无内容：永不 paint（防黑屏）。
 * - [SettledHidden]：详情下预热满糊。
 * - [BackPreview]/[Returning]/[Restoring]：drawable 时垫跟手/消糊景深；
 *   源 dispose 后 DL 失效时 stale=true，Host 不画，等源重录。
 */

internal fun shouldPaintHostOwnedDepthLayer(
    exposure: VideoCardTransitionExposure,
    hasRecordedContent: Boolean,
    displayListStale: Boolean = false,
    motionTier: MotionTier,
    realtimeBlurEnabled: Boolean,
    renderEffectSupported: Boolean = desktopDetailRenderEffectsSupported(),
): Boolean {
    if (
        !isVideoCardTransitionSnapshotDrawable(
            hasRecordedContent = hasRecordedContent,
            displayListStale = displayListStale,
        )
    ) {
        return false
    }
    if (motionTier == MotionTier.Reduced) return false
    if (!realtimeBlurEnabled) return false
    if (!renderEffectSupported) return false
    return when (exposure) {
        // SettledHidden：详情下预热（须 drawable）。
        // BackPreview/Returning：drawable 时 Host 在 NavDisplay 下垫一层跟手糊；
        // 源页重录后会在其上画同 layer。stale 时 Host 不画（防黑），源 live/重录接手。
        VideoCardTransitionExposure.SettledHidden,
        VideoCardTransitionExposure.BackPreview,
        VideoCardTransitionExposure.Restoring,
        VideoCardTransitionExposure.Returning,
        -> true
        VideoCardTransitionExposure.Opening,
        VideoCardTransitionExposure.Idle,
        -> false
    }
}

/** SettledHidden 强制满糊；其余跟 live progress（含预测手势 1→0）。 */

internal fun resolveHostOwnedDepthProgress(
    exposure: VideoCardTransitionExposure,
    liveProgress: Float,
): Float {
    return when (exposure) {
        VideoCardTransitionExposure.SettledHidden -> 1f
        else -> liveProgress.coerceIn(0f, 1f)
    }
}

/**
 * 来源 Scene 的 DisposableEffect 是否可销毁 Host 快照。
 * Host 共享 handle 时禁止 dispose 清层，否则预测返回无糊可用。
 */

internal fun shouldMarkDisplayListStaleOnHostOwnedSourceDispose(): Boolean = false

/**
 * 仅 IDLE 才释放 Host 快照 / BlurEffect；SettledHidden 必须保留满糊层。
 */

internal fun shouldReleaseHostOwnedDepthLayer(
    exposure: VideoCardTransitionExposure,
): Boolean = exposure == VideoCardTransitionExposure.Idle

