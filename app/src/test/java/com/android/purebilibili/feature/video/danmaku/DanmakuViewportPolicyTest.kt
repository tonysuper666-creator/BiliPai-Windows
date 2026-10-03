package com.android.purebilibili.feature.video.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuViewportPolicyTest {
    @Test
    fun `invalid geometry does not produce a placeholder viewport`() {
        assertNull(resolveDanmakuViewport(0, 608, 3f))
        assertNull(resolveDanmakuViewport(1080, 0, 3f))
        assertNull(resolveDanmakuViewport(1080, 608, 0f))
        assertNull(resolveDanmakuViewport(1080, 608, Float.NaN))
    }

    @Test
    fun `viewport carries the measured box and density only`() {
        val viewport = requireNotNull(resolveDanmakuViewport(1080, 608, 3f))
        assertEquals(1080, viewport.widthPx)
        assertEquals(608, viewport.heightPx)
        assertEquals(3f, viewport.density, 0f)
    }

    @Test
    fun `inline and fullscreen surfaces resolve the same text size`() {
        val inline = requireNotNull(resolveDanmakuViewport(1080, 608, 3f))
        val fullscreen = requireNotNull(resolveDanmakuViewport(2392, 1080, 3f))
        assertEquals(
            resolveDanmakuTextSizePx(fullscreen.density, 1.5f),
            resolveDanmakuTextSizePx(inline.density, 1.5f),
            0f
        )
    }

    @Test
    fun `a band shorter than one row budgets no lines`() {
        val rowHeight = resolveDanmakuLayerLineHeightPx(fontSize = 45f, lineHeightMultiplier = 1.6f)
        assertEquals(
            0,
            resolveDanmakuVisibleLineCount(
                visibleHeightPx = rowHeight - 1f,
                areaRatioHint = 0.5f,
                fontSize = 45f,
                strokeWidth = 2f,
                strokeEnabled = true,
                lineHeight = 1.6f,
                massiveMode = false
            )
        )
    }

    @Test
    fun `a taller band never budgets fewer rows`() {
        fun rows(visibleHeightPx: Float) = resolveDanmakuVisibleLineCount(
            visibleHeightPx = visibleHeightPx,
            areaRatioHint = 0.5f,
            fontSize = 45f,
            strokeWidth = 2f,
            strokeEnabled = true,
            lineHeight = 1.6f,
            massiveMode = false
        )
        val rowHeight = resolveDanmakuLayerLineHeightPx(fontSize = 45f, lineHeightMultiplier = 1.6f)
        val tall = rows(rowHeight * 8f)
        val short = rows(rowHeight * 3f)
        assertTrue(tall >= short)
        assertTrue(short > 0)
    }
}
