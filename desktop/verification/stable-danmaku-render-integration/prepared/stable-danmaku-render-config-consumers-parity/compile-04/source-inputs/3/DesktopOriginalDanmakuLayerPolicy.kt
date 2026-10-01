package com.android.purebilibili.feature.video.danmaku
import com.android.purebilibili.danmaku.engine.*

internal fun resolveDanmakuRenderLayerType(
    type: Int,
    staticDanmakuToScroll: Boolean
): Int {
    if (staticDanmakuToScroll && (type == 4 || type == 5)) {
        return DANMAKU_LAYER_SCROLL
    }
    return when (type) {
        4 -> DANMAKU_LAYER_BOTTOM
        5 -> DANMAKU_LAYER_TOP
        6 -> DANMAKU_LAYER_REVERSE
        else -> DANMAKU_LAYER_SCROLL
    }
}

internal fun mapLayerTypeToDanmakuType(layerType: Int): Int = when (layerType) {
        DANMAKU_LAYER_BOTTOM -> 4
        DANMAKU_LAYER_TOP -> 5
        DANMAKU_LAYER_REVERSE -> 6
        else -> 1
    }
