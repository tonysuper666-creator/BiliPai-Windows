package com.android.purebilibili.feature.video.danmaku

import com.android.purebilibili.danmaku.parser.resolveBilibiliDanmakuFontScale

import com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_BOTTOM
import com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_SCROLL
import com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_TOP
import com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_REVERSE
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class DesktopDanmakuViewportGeometryTest {
    @Test
    fun `viewport geometry alone does not imply fullscreen presentation`() {
        val inline = requireNotNull(resolveDanmakuViewport(1080, 608, 3f))
        val fullscreen = requireNotNull(resolveDanmakuViewport(2392, 1080, 3f))
        assertEquals(
            resolveDanmakuTextSizePx(inline.density, 1f, isFullscreen = false),
            resolveDanmakuTextSizePx(fullscreen.density, 1f, isFullscreen = false),
            0f
        )
    }

}
