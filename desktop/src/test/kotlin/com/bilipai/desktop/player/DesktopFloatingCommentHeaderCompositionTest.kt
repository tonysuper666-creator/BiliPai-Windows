package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.video.screen.videoContentAdditionalTopPadding
import com.android.purebilibili.feature.video.ui.components.VideoCommentFloatingHeader
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import com.bilipai.desktop.ui.*
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopFloatingCommentHeaderCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    private fun verify(densityScale: Float) {
        val directory = Files.createTempDirectory("floating-comment")
        val settings = DesktopLiquidTabSettings(DesktopPluginStore(directory))
        val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
        }
        val platform = DesktopHomePlatform(false, true, false, false, false, false, background, 0f, false)
        val density = Density(densityScale)
        val width = mutableStateOf(240.dp)
        val measured = mutableFloatStateOf(0f)
        val collapse = mutableFloatStateOf(0f)
        val mode = mutableStateOf(CommentSortMode.HOT)
        var firstRowY = -1f
        var searches = 0
        val selections = mutableListOf<CommentSortMode>()
        val scene = ImageComposeScene((800 * densityScale).toInt(), (400 * densityScale).toInt(), density) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopLiquidTabSettings provides settings, LocalDesktopHomePlatform provides platform, LocalAppUiStyle provides AppUiStyle.MATERIAL3, LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false)) {
                    val padding = remember { videoContentAdditionalTopPadding(PaddingValues(), { measured.floatValue }, density) }
                    Box(Modifier.width(width.value).fillMaxHeight()) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                            item { Box(Modifier.height(30.dp).fillMaxWidth().onGloballyPositioned { firstRowY = it.positionInRoot().y }) }
                        }
                        VideoCommentFloatingHeader(
                            count = 42, sortMode = mode.value,
                            onSortModeChange = { selections += it; mode.value = it },
                            onSearchClick = { searches++ }, backdrop = null, liquidGlassEnabled = false,
                            compactTitleTypography = true, titleCollapseProgress = { collapse.floatValue },
                            modifier = Modifier.onSizeChanged { measured.floatValue = it.height.toFloat() },
                        )
                    }
                }
            }
        }
        fun render() { Snapshot.sendApplyNotifications(); repeat(4) { scene.render().close() } }
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun click(description: String) {
            var n = all().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true || it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == description } == true }
            while (n.config.getOrNull(SemanticsActions.OnClick) == null) n = checkNotNull(n.parent)
            scene.sendPointerEvent(PointerEventType.Press, n.boundsInRoot.center, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, n.boundsInRoot.center, buttons = PointerButtons())
            render()
        }
        try {
            render()
            val narrowHeight = measured.floatValue
            assertTrue(narrowHeight > 46 * densityScale, "wrapped header must exceed the old fixed inset")
            assertEquals(narrowHeight, firstRowY)
            assertTrue(selections.isEmpty()); assertEquals(0, searches)
            click(CommentSortMode.NEWEST.label)
            assertEquals(listOf(CommentSortMode.NEWEST), selections)
            collapse.floatValue = 1f
            render()
            assertEquals(narrowHeight, measured.floatValue)
            assertEquals(narrowHeight, firstRowY)
            click("搜索评论")
            assertEquals(1, searches)
            width.value = 800.dp
            render()
            val wideHeight = measured.floatValue
            assertTrue(wideHeight < narrowHeight)
            assertEquals(wideHeight, firstRowY)
            click(CommentSortMode.HOT.label)
            assertEquals(listOf(CommentSortMode.NEWEST, CommentSortMode.HOT), selections)
            width.value = 240.dp
            collapse.floatValue = 0f
            render()
            assertEquals(narrowHeight, measured.floatValue)
            assertEquals(narrowHeight, firstRowY)
        } finally { scene.close(); directory.toFile().deleteRecursively() }
    }
    @Test fun wrappedHeaderReservesMeasuredHeightAndRetainsActions() = verify(1f)
    @Test fun measuredHeaderPaddingTracksDensityAndResize() = verify(2f)
}
