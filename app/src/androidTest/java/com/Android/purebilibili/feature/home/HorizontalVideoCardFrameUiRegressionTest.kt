package com.android.purebilibili.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.feature.home.components.cards.HorizontalVideoCardFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HorizontalVideoCardFrameUiRegressionTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun singleAndTwoLineTitlesKeepCoverAndInfoAligned() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    card("short", "短标题")
                    card("long", "这是一个会换行的视频标题，用于检查搜索和相关推荐的封面大小")
                }
            }
        }
        val shortCover = bounds("short_cover")
        val longCover = bounds("long_cover")
        assertEquals(shortCover.width, longCover.width)
        assertEquals(shortCover.height, longCover.height)
        assertEquals(bounds("short_info").left, bounds("long_info").left)
        assertEquals(bounds("short").height, bounds("long").height)
    }

    @Test
    fun metadataUpdatesDoNotResizeCover() {
        val metadata = mutableStateOf("UP主")
        rule.setContent {
            MaterialTheme { card("card", "视频标题", metadata = metadata.value) }
        }
        val initial = bounds("card_cover")
        rule.runOnIdle { metadata.value = "昨天 18:00\n已关注的UP主\n播放 13.3万\n弹幕 19" }
        val updated = bounds("card_cover")
        assertEquals(initial.width, updated.width)
        assertEquals(initial.height, updated.height)
    }

    @Test
    fun largeFontAndFullTitleCanGrowInfoWithoutChangingCoverOrClippingContent() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    card(
                        "card",
                        "完整标题在大字体和窄窗口下仍应全部显示，不能通过固定卡片高度裁掉文字",
                        maxLines = Int.MAX_VALUE,
                        widthDp = 260,
                        ratio = 4f / 3f,
                    )
                }
            }
        }
        val cover = bounds("card_cover")
        val info = bounds("card_info")
        val card = bounds("card")
        assertEquals(4f / 3f, cover.width / cover.height, 0.02f)
        assertTrue(info.left >= cover.right)
        assertTrue(info.height > cover.height)
        assertTrue(card.bottom >= info.bottom)
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @androidx.compose.runtime.Composable
    private fun card(
        tag: String,
        title: String,
        metadata: String = "10-29 UP主\n播放 13.3万 弹幕 19",
        maxLines: Int = 2,
        widthDp: Int = 320,
        ratio: Float = 16f / 10f,
    ) {
        HorizontalVideoCardFrame(
            modifier = Modifier.width(widthDp.dp).testTag(tag),
            coverModifier = Modifier.testTag("${tag}_cover"),
            infoModifier = Modifier.testTag("${tag}_info"),
            coverAspectRatio = ratio,
            fillInfoHeight = true,
            coverContent = { Box(Modifier.fillMaxSize()) },
            infoContent = {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(metadata, fontSize = 12.sp, lineHeight = 16.sp)
            },
        )
    }
}
