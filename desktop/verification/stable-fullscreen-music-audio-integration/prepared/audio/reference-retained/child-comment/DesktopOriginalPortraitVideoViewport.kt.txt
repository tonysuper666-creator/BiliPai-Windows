package com.android.purebilibili.feature.video.ui.pager
import kotlin.math.roundToInt
internal data class PortraitVideoViewportSize(
    val width: Int,
    val height: Int
)

internal fun resolvePortraitVideoViewportSize(
    containerWidth: Int,
    containerHeight: Int,
    currentVideoAspect: Float,
    fillContainer: Boolean
): PortraitVideoViewportSize {
    val safeWidth = containerWidth.coerceAtLeast(1)
    val safeHeight = containerHeight.coerceAtLeast(1)
    if (fillContainer) {
        return PortraitVideoViewportSize(width = safeWidth, height = safeHeight)
    }
    val safeAspect = currentVideoAspect.coerceAtLeast(0.1f)
    val containerAspect = safeWidth.toFloat() / safeHeight.toFloat()
    return if (safeAspect > containerAspect) {
        PortraitVideoViewportSize(
            width = safeWidth,
            height = (safeWidth / safeAspect).roundToInt().coerceIn(1, safeHeight)
        )
    } else {
        PortraitVideoViewportSize(
            width = (safeHeight * safeAspect).roundToInt().coerceIn(1, safeWidth),
            height = safeHeight
        )
    }
}
