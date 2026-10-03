package com.android.purebilibili.feature.video.danmaku

/**
 * Geometry shared by every video-danmaku layer; independent of the display-area setting.
 *
 * Text size deliberately ignores this box: it follows the user's scale on a
 * density-independent base so inline and fullscreen render identically.
 */
data class DanmakuViewport(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float
) {
    init {
        require(widthPx > 0 && heightPx > 0)
        require(density.isFinite() && density > 0f)
    }
}

fun resolveDanmakuViewport(
    widthPx: Int,
    heightPx: Int,
    density: Float
): DanmakuViewport? {
    if (widthPx <= 0 || heightPx <= 0 || !density.isFinite() || density <= 0f) return null
    return DanmakuViewport(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density
    )
}
