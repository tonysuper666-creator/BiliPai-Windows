package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.video.ui.components.CommentSortHeader
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopCommentSortCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    private fun verify(width: Int) {
        val mode = mutableStateOf(CommentSortMode.HOT)
        val selections = mutableListOf<CommentSortMode>()
        var searches = 0
        val scene = ImageComposeScene(width, 240, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppUiStyle provides AppUiStyle.MATERIAL3) {
                    CommentSortHeader(count = 42, sortMode = mode.value,
                        onSortModeChange = { selections += it; mode.value = it },
                        onSearchClick = { searches++ })
                }
            }
        }
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun click(description: String) {
            var node = all().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
            while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = checkNotNull(node.parent)
            assertTrue(node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= width)
            assertTrue(node.boundsInRoot.top >= 0f && node.boundsInRoot.bottom <= 240f)
            scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center, buttons = PointerButtons())
            scene.render().close()
        }
        try {
            scene.render().close()
            assertTrue(selections.isEmpty()); assertEquals(0, searches)
            val title = all().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "${CommentSortMode.HOT.label}评论" } == true }
            assertTrue(title.boundsInRoot.left >= 0f && title.boundsInRoot.right <= width)
            val sortIcon = all().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("切换评论排序") == true }
            if (width == 240) assertTrue(sortIcon.boundsInRoot.top >= title.boundsInRoot.bottom,
                "narrow header must wrap the controls below its title")
            else assertTrue(sortIcon.boundsInRoot.top < title.boundsInRoot.bottom,
                "wide header keeps controls on the title row")
            click("切换评论排序")
            assertEquals(listOf(CommentSortMode.NEWEST), selections)
            click("搜索评论")
            assertEquals(1, searches); assertEquals(1, selections.size)
            click("切换评论排序")
            assertEquals(listOf(CommentSortMode.NEWEST, CommentSortMode.HOT), selections)
            assertEquals(1, searches)
        } finally { scene.close() }
    }
    @Test fun narrowHeaderWrapsAndKeepsSortAndSearchIndependent() = verify(240)
    @Test fun wideHeaderKeepsSortAndSearchIndependent() = verify(800)
}
