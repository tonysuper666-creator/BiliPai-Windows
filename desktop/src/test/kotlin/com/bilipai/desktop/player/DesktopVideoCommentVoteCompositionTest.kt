package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.data.model.response.ReplyVoteCard
import com.android.purebilibili.data.model.response.ReplyVoteCardOption
import com.android.purebilibili.feature.video.ui.components.VideoCommentVoteCard
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopVideoCommentVoteCompositionTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    private fun verify(style: AppUiStyle) {
        val longLabel = "长投票选项".repeat(30)
        val card = mutableStateOf(ReplyVoteCard(123, "投票标题", 4,
            listOf(ReplyVoteCardOption(42, longLabel, 3), ReplyVoteCardOption(91, "另一个选项", 1)), 42))
        val scene = ImageComposeScene(640, 900, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppUiStyle provides style) { VideoCommentVoteCard(card.value) }
            }
        }
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun texts() = all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
        try {
            scene.render().close()
            assertTrue(texts().containsAll(listOf("投票标题", "4 人参与 · 已投票", "✓ $longLabel", "75%", "25%")))
            val options = all().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(2, options.size)
            options.forEach {
                assertTrue(it.boundsInRoot.width in 144f..240f, "option width ${it.boundsInRoot.width}")
                assertTrue(it.boundsInRoot.height >= 72f)
            }
            card.value = card.value.copy(myVoteOption = 91, options = card.value.options.map { it.copy(count = 0) })
            scene.render().close()
            assertTrue("✓ 另一个选项" in texts())
            assertTrue("✓ $longLabel" !in texts())
            assertEquals(2, texts().count { it == "0%" })
            card.value = card.value.copy(voteId = 0)
            scene.render().close()
            assertTrue(texts().isEmpty())
            card.value = card.value.copy(voteId = 123, options = emptyList())
            scene.render().close()
            assertTrue(texts().isEmpty())
        } finally { scene.close() }
    }

    @Test fun materialVoteCardBoundsAndSelectionFollowCurrentCard() = verify(AppUiStyle.MATERIAL3)
    @Test fun miuixVoteCardBoundsAndSelectionFollowCurrentCard() = verify(AppUiStyle.MIUIX)
}
