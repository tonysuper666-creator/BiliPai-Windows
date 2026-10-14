package com.android.purebilibili.feature.video.ui.overlay

import com.android.purebilibili.core.store.PlayerProgressPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class BottomControlBarLayoutPolicyTest {

    @Test
    fun timeLabelSpacingKeepsVisibleGapsNearFontSizeAcrossWidthBuckets() {
        listOf(393, 599, 600, 839, 840, 1599, 1600).forEach { widthDp ->
            val policy = resolveBottomControlBarLayoutPolicy(widthDp)
            val playGlyphInsetDp = (policy.playButtonSizeDp - policy.playIconSizeDp) / 2
            val danmakuGlyphInsetDp = (policy.playButtonSizeDp - policy.danmakuIconSizeDp) / 2

            assertEquals(
                policy.timeFontSp,
                policy.afterPlaySpacingDp + playGlyphInsetDp,
                "play-to-time visual gap at ${widthDp}dp",
            )
            assertEquals(
                policy.timeFontSp,
                policy.afterTimeSpacingDp + danmakuGlyphInsetDp,
                "time-to-danmaku visual gap at ${widthDp}dp",
            )
        }
    }

    @Test
    fun bottomEdgeProgress_removesBottomGapFromWholeControlBar() {
        assertEquals(
            0,
            resolveBottomControlBarBottomPaddingDp(
                defaultBottomPaddingDp = 12,
                progressPlacement = PlayerProgressPlacement.BOTTOM_EDGE
            )
        )
        assertEquals(
            12,
            resolveBottomControlBarBottomPaddingDp(
                defaultBottomPaddingDp = 12,
                progressPlacement = PlayerProgressPlacement.ABOVE_CONTROLS
            )
        )
    }

    @Test
    fun inlineDetailPlayer_pinsProgressToVideoBottomEdge() {
        assertEquals(
            PlayerProgressPlacement.BOTTOM_EDGE,
            resolveVideoDetailProgressPlacement(
                requestedPlacement = PlayerProgressPlacement.ABOVE_CONTROLS,
                isFullscreen = false
            )
        )
    }

    @Test
    fun fullscreen_keepsRequestedProgressPlacement() {
        assertEquals(
            PlayerProgressPlacement.ABOVE_CONTROLS,
            resolveVideoDetailProgressPlacement(
                requestedPlacement = PlayerProgressPlacement.ABOVE_CONTROLS,
                isFullscreen = true
            )
        )
    }
}
