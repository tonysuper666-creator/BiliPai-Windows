package com.android.purebilibili.feature.video.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandDanmakuOverlayPolicyTest {

    @Test
    fun `triple action never implicitly follows the author`() {
        for (isFollowing in listOf(false, true)) {
            val action = resolveAttentionCommandClickAction(
                attentionType = 2,
                action = AttentionCommandAction.TRIPLE,
                isFollowing = isFollowing,
            )

            assertEquals(false, action.shouldFollow)
            assertEquals(true, action.shouldTriple)
        }
    }

    @Test
    fun `follow action is independent and ignores existing followers`() {
        for (isFollowing in listOf(false, true)) {
            val action = resolveAttentionCommandClickAction(
                attentionType = 2,
                action = AttentionCommandAction.FOLLOW,
                isFollowing = isFollowing,
            )

            assertEquals(!isFollowing, action.shouldFollow)
            assertEquals(false, action.shouldTriple)
        }
    }

    @Test
    fun `follow only command cannot trigger triple action`() {
        val action = resolveAttentionCommandClickAction(
            attentionType = 0,
            action = AttentionCommandAction.TRIPLE,
            isFollowing = false,
        )

        assertEquals(false, action.shouldFollow)
        assertEquals(false, action.shouldTriple)
    }

    @Test
    fun `triple only command cannot trigger follow action`() {
        val action = resolveAttentionCommandClickAction(
            attentionType = 1,
            action = AttentionCommandAction.FOLLOW,
            isFollowing = false,
        )

        assertEquals(false, action.shouldFollow)
        assertEquals(false, action.shouldTriple)
    }

    @Test
    fun `command card horizontal offset is clamped inside player bounds`() {
        val containerWidthPx = 1080
        val cardWidthPx = 588

        assertEquals(492, resolveCommandDanmakuHorizontalOffsetPx(containerWidthPx, cardWidthPx, 0.82f))
        assertEquals(0, resolveCommandDanmakuHorizontalOffsetPx(containerWidthPx, cardWidthPx, -0.2f))
    }

    @Test
    fun `command card width is capped by a narrow player viewport`() {
        assertEquals(320, resolveCommandDanmakuCardWidthPx(320, 420))
        assertEquals(0, resolveCommandDanmakuCardWidthPx(320, -1))
    }

    @Test
    fun `command card vertical offset is clamped by measured card height`() {
        assertEquals(192, resolveCommandDanmakuVerticalOffsetPx(320, 128, 0.8f))
        assertEquals(0, resolveCommandDanmakuVerticalOffsetPx(320, 400, 0.8f))
        assertEquals(0, resolveCommandDanmakuVerticalOffsetPx(320, 128, -0.2f))
    }
}
