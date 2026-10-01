package com.android.purebilibili.feature.video.danmaku

internal data class DanmakuDisplayBand(
    val topRatio: Float,
    val bottomRatio: Float
) {
    val heightRatio: Float
        get() = (bottomRatio - topRatio).coerceAtLeast(0f)

    fun normalized(): DanmakuDisplayBand {
        val top = topRatio.coerceIn(0f, 1f)
        val bottom = bottomRatio.coerceIn(top, 1f)
        return DanmakuDisplayBand(topRatio = top, bottomRatio = bottom)
    }
}
