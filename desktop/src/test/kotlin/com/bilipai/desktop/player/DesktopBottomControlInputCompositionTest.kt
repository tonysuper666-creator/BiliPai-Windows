package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBottomControlInputCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    @Test fun actualInlineControlBarGatesInputAt720AndKeepsIndependentActions() {
        for (width in listOf(719, 720, 800, 1280)) {
            val loggedIn = mutableStateOf(false)
            val calls = mutableListOf<String>()
            val scene = ImageComposeScene(width, 240, Density(1f)) {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false)) {
                        val current = loggedIn.value
                        BottomControlBar(
                            isPlaying = false, isFullscreen = false,
                            viewportWidthDpOverride = width,
                            onPlayPauseClick = { calls += "play" },
                            onSeek = { error("Unexpected seek") },
                            onToggleFullscreen = { calls += "fullscreen" },
                            isLoggedIn = current,
                            onDanmakuInputClick = { calls += "input:$current" },
                        )
                    }
                }
            }
            fun settle() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun text(value: String) = all().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true
            }
            fun clickable(n: SemanticsNode): SemanticsNode {
                var current = n
                while (!current.config.contains(SemanticsActions.OnClick)) current = checkNotNull(current.parent)
                return current
            }
            fun click(point: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
                settle()
            }
            try {
                settle(); assertTrue(calls.isEmpty())
                if (width == 719) {
                    assertTrue(text("登录发弹幕").isEmpty())
                } else {
                    val input = clickable(text("登录发弹幕").single())
                    assertEquals(48f, input.boundsInRoot.height)
                    assertTrue(input.boundsInRoot.left >= 0f && input.boundsInRoot.right <= width)
                    assertTrue(input.boundsInRoot.top >= 0f && input.boundsInRoot.bottom <= 240f)
                    val fullscreen = all().single {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "player_fullscreen_toggle"
                    }
                    assertFalse(input.boundsInRoot.overlaps(fullscreen.boundsInRoot))
                    click(input.boundsInRoot.center)
                    assertEquals(listOf("input:false"), calls)
                    loggedIn.value = true; settle()
                    click(clickable(text("发弹幕").single()).boundsInRoot.center)
                    assertEquals(listOf("input:false", "input:true"), calls)
                    click(fullscreen.boundsInRoot.center)
                    assertEquals(listOf("input:false", "input:true", "fullscreen"), calls)
                }
            } finally { scene.close() }
        }
    }
}
