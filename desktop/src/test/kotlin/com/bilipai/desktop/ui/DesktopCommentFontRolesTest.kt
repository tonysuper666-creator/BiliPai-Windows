package com.bilipai.desktop.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.android.purebilibili.feature.video.ui.components.VideoCommentTextRole
import com.android.purebilibili.feature.video.ui.components.resolveVideoCommentFontSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The v0.2.7 comment font step, carried by the whole v0.4.6 VideoCommentAppearance file: the comment
 * body and the nested reply text follow the page body step instead of bodySmall. Pure data, no window,
 * no Compose runtime, no network.
 */
class DesktopCommentFontRolesTest {
    private val typography = Typography(
        bodySmall = TextStyle(fontSize = 12.sp),
        bodyMedium = TextStyle(fontSize = 14.sp),
        labelSmall = TextStyle(fontSize = 11.sp),
        labelMedium = TextStyle(fontSize = 13.sp),
    )

    @Test
    fun `comment body and nested reply follow the page body step`() {
        assertEquals(14.sp, resolveVideoCommentFontSize(typography, VideoCommentTextRole.BODY))
        assertEquals(14.sp, resolveVideoCommentFontSize(typography, VideoCommentTextRole.SUB_REPLY))
    }

    @Test
    fun `author metadata and action roles keep their own steps`() {
        assertEquals(12.sp, resolveVideoCommentFontSize(typography, VideoCommentTextRole.AUTHOR))
        assertEquals(11.sp, resolveVideoCommentFontSize(typography, VideoCommentTextRole.METADATA))
        assertEquals(11.sp, resolveVideoCommentFontSize(typography, VideoCommentTextRole.ACTION_COUNT))
        assertEquals(13.sp, resolveVideoCommentFontSize(typography, VideoCommentTextRole.ACTION))
    }

    @Test
    fun `every role resolves and the body is larger than the author line`() {
        val sizes = VideoCommentTextRole.entries.associateWith { resolveVideoCommentFontSize(typography, it) }
        assertEquals(6, sizes.size)
        assertNotEquals(
            sizes.getValue(VideoCommentTextRole.AUTHOR),
            sizes.getValue(VideoCommentTextRole.BODY),
        )
    }
}
