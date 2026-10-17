package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.LocalUpBadgeVisibility
import com.android.purebilibili.core.ui.UpBadgeVisibility
import com.android.purebilibili.core.ui.components.UpBadgeName
import com.android.purebilibili.feature.home.components.cards.VideoRecommendationBadge
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import kotlin.test.*

/** Offscreen renders of the original components, no native app/window, HTTP or account. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopHomeRecommendationBadgeTest {
    @Test fun `recommendation reason survives disabled UP certification badge in both styles`() {
        for (style in AppUiStyle.entries) withScene(style) {
            UpBadgeName(name = "作者", modifier = Modifier.width(460.dp),
                nameLeadingContent = { VideoRecommendationBadge("热门推荐") })
        }.use { h ->
            val reason = h.text("热门推荐")
            val name = h.text("作者")
            assertTrue(reason.boundsInRoot.right <= name.boundsInRoot.left)
            assertNull(reason.config.getOrNull(SemanticsActions.OnClick))
            assertFalse(h.nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "UP" } == true })
        }
    }

    @Test fun `new leading reason preserves existing leading inline trailing and metadata slots`() {
        for (style in AppUiStyle.entries) withScene(style) {
            UpBadgeName(name = "作者", modifier = Modifier.width(620.dp), metaText = "粉丝 100",
                nameLeadingContent = { VideoRecommendationBadge("新作推荐") },
                leadingContent = { Text("原头像槽") }, inlineTrailingContent = { Text("原尾部槽") })
        }.use { h ->
            for (text in listOf("新作推荐", "原头像槽", "作者", "原尾部槽", "粉丝 100")) h.text(text)
            assertTrue(h.text("新作推荐").boundsInRoot.right <= h.text("原头像槽").boundsInRoot.left)
            assertTrue(h.text("原头像槽").boundsInRoot.right <= h.text("作者").boundsInRoot.left)
            assertTrue(h.text("作者").boundsInRoot.right <= h.text("原尾部槽").boundsInRoot.left)
        }
    }

    @Test fun `empty reason has no badge layout or semantics node`() {
        for (style in AppUiStyle.entries) withScene(style) {
            Column {
                VideoRecommendationBadge("  ", Modifier.testTag("empty-reason"))
                Text("下一项")
            }
        }.use { h ->
            assertFalse(h.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "empty-reason" })
            assertTrue(h.text("下一项").boundsInRoot.top <= 1f)
        }
    }

    @Test fun `long recommendation reason remains one bounded line at larger desktop font scale`() {
        val reason = "这是一个很长的服务端推荐理由用来检查大字号布局"
        for (style in AppUiStyle.entries) withScene(style, fontScale = 1.75f) {
            UpBadgeName(name = "作者名称也会比较长但不能挤掉推荐理由", modifier = Modifier.width(620.dp),
                nameLeadingContent = { VideoRecommendationBadge(reason) })
        }.use { h ->
            val node = h.text(reason)
            assertTrue(node.boundsInRoot.width <= 100.5f)
            val results = mutableListOf<TextLayoutResult>()
            val action = assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
            assertTrue(action(results))
            assertEquals(1, results.single().lineCount)
        }
    }

    private fun withScene(style: AppUiStyle, fontScale: Float = 1f, content: @Composable () -> Unit): Scene {
        val scene = ImageComposeScene(700, 240, Density(1f, fontScale)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style)) {
                CompositionLocalProvider(LocalUpBadgeVisibility provides UpBadgeVisibility(false, false)) { content() }
            }
        }
        try {
            repeat(5) { scene.render((it+1)*40_000_000L).close() }
            return Scene(scene)
        } catch (failure: Throwable) { scene.close(); throw failure }
    }

    private class Scene(private val scene: ImageComposeScene) : AutoCloseable {
        fun nodes(): List<SemanticsNode> {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node)+node.children.flatMap(::flatten)
            return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        }
        fun text(value: String): SemanticsNode = nodes().single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        }
        override fun close() = scene.close()
    }
}
