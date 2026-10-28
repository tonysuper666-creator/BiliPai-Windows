package com.android.purebilibili.feature.video.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import com.android.purebilibili.core.ui.transition.VideoCardSourceLayout
import com.android.purebilibili.core.ui.transition.VideoCardTransitionBackgroundPhase
import com.android.purebilibili.core.ui.transition.normalizeSharedElementSourceRoute
import com.android.purebilibili.core.util.CardPositionManager

internal fun resolveVideoDetailWholeCardReturnAlpha(
    depth: Float,
    returning: Boolean,
    followProgress: Boolean,
    committedIdle: Boolean,
): Float {
    if (committedIdle) return 1f
    if (!returning) return 0f
    val progress = 1f - depth.coerceIn(0f, 1f)
    if (!followProgress) return if (progress >= 1f) 1f else 0f
    // 前段保留实时画面；整卡在末段统一接管，最后一小段已经是原卡片的完整像素。
    val fraction = ((progress - 0.55f) / (0.98f - 0.55f)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

internal class VideoDetailWholeCardReturn(
    val modifier: Modifier,
)

/**
 * 手机、大屏及所有材质共用同一份点击时整卡像素快照。只做等比缩放，不重新布局封面和文字。
 * 录制资源按详情 entry 的视频与来源身份保留，直到 entry 退出；嵌套父页不得消费子页图层。
 */
@Composable
internal fun rememberVideoDetailWholeCardReturn(
    bvid: String,
    sourceRoute: String?,
    ownsTransition: Boolean,
    committedReturn: Boolean,
    keepLoadedContentForBackPreview: Boolean,
    sourceBounds: Rect?,
    sourceLayout: VideoCardSourceLayout,
    isNowPlayingBar: Boolean,
    depthProvider: () -> Float,
    phaseProvider: () -> VideoCardTransitionBackgroundPhase,
    gestureProvider: () -> Boolean,
    followProgressProvider: () -> Boolean,
): VideoDetailWholeCardReturn {
    val expectedKey = normalizeSharedElementSourceRoute(sourceRoute)?.let { "$it:${bvid.trim()}" }
    // Bind once per entry, before an asynchronous capture is ready. Mutable navigation keys
    // cannot grant an already retained parent ownership of a later child's pixels.
    val pixelOwner = remember(bvid, sourceRoute) {
        CardPositionManager.videoCardPixelOwnerFor(expectedKey)
    }
    var retained by remember(bvid, sourceRoute) { mutableStateOf<ImageBitmap?>(null) }
    val captured = CardPositionManager.lastClickedWholeCardReturnSnapshot
    val bitmap = captured?.bitmap
    val candidate: ImageBitmap? = if (ownsTransition && !isNowPlayingBar &&
        sourceLayout != VideoCardSourceLayout.COVER_ONLY && pixelOwner != null &&
        captured?.owner === pixelOwner &&
        bitmap != null && bitmap.width > 1 && bitmap.height > 1 &&
        sourceBounds != null && sourceBounds.width > 1f && sourceBounds.height > 1f
    ) bitmap else null
    SideEffect { if (retained == null && candidate != null) retained = candidate }
    val snapshot: ImageBitmap? = retained ?: candidate
    val active: () -> Boolean = {
        snapshot != null && !keepLoadedContentForBackPreview &&
            (ownsTransition || committedReturn) &&
            (committedReturn || gestureProvider() ||
                phaseProvider() == VideoCardTransitionBackgroundPhase.RETURNING)
    }
    val modifier = Modifier.videoDetailWholeCardReturnLayer(bitmap = snapshot) {
        if (!active()) 0f else resolveVideoDetailWholeCardReturnAlpha(
            depth = depthProvider(),
            returning = true,
            followProgress = followProgressProvider(),
            committedIdle = committedReturn && !gestureProvider() &&
                phaseProvider() == VideoCardTransitionBackgroundPhase.IDLE,
        )
    }
    return VideoDetailWholeCardReturn(modifier)
}

/** 整卡像素在同一透明度组中绘制，避免封面、角标和信息区各自裁切或各自叠化。 */
internal fun Modifier.videoDetailWholeCardReturnLayer(
    bitmap: ImageBitmap?,
    alphaProvider: () -> Float,
): Modifier {
    val paint = Paint()
    return drawWithContent {
        val alpha = alphaProvider().coerceIn(0f, 1f)
        if (alpha <= 0f || bitmap == null || bitmap.width <= 1 || bitmap.height <= 1) {
            drawContent()
        } else {
            // 原封面、播放器和来源文字继续正常交接，不能在快照尚透明时提前隐藏。
            // 两个完整内容组统一叠化；快照完全不透明后才停止绘制原内容。
            drawIntoCanvas { canvas ->
                if (alpha < 1f) {
                    paint.alpha = 1f - alpha
                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                    try { this@drawWithContent.drawContent() } finally { canvas.restore() }
                }
                paint.alpha = alpha
                canvas.saveLayer(Rect(Offset.Zero, size), paint)
                try {
                    // FillWidthTop 的导航补偿已统一两轴比例，整卡只按宽度缩放一次。
                    val viewportWidth = size.width
                    val sourcePixelScale = viewportWidth / bitmap.width
                    scale(sourcePixelScale, sourcePixelScale, pivot = Offset.Zero) {
                        drawImage(bitmap)
                    }
                } finally { canvas.restore() }
            }
        }
    }
}
