package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.video.ui.overlay.CenterPlaybackControl
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopCenterPlaybackCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    private fun verify(style: AppUiStyle) {
        val playing = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val inset = mutableStateOf(0.dp)
        var clicks = 0
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        val scene = ImageComposeScene(640, 360, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppUiStyle provides style, LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false)) {
                    CenterPlaybackControl(visible.value, playing.value, { clicks++ }, 56.dp, viewportTopInset = inset.value)
                }
            }
        }
        fun frames(count: Int = 3) {
            repeat(count) {
                Snapshot.sendApplyNotifications(); scheduler.runCurrent()
                scene.render(scheduler.currentTime * 1_000_000L).close()
                scheduler.advanceTimeBy(16); scheduler.runCurrent()
            }
            assertTrue(errors.isEmpty(), errors.toString())
        }
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
            frames()
        }
        fun glyph(label: String) = all().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label) }
        try {
            frames()
            assertEquals(Offset(320.5f, 180.5f), glyph("开始播放").boundsInRoot.center)
            click(Offset(320f, 180f)); assertEquals(1, clicks)
            playing.value = true; frames()
            assertEquals(Offset(320.5f, 180.5f), glyph("暂停播放").boundsInRoot.center)
            inset.value = 40.dp; frames()
            val button = all().single { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(56f, button.boundsInRoot.width)
            assertEquals(56f, button.boundsInRoot.height)
            assertEquals(Offset(320f, 200f), button.boundsInRoot.center)
            // 56dp * 0.48 rounds to an odd 27px glyph; integer centering adds half a pixel.
            assertEquals(27f, glyph("暂停播放").boundsInRoot.width)
            assertEquals(27f, glyph("暂停播放").boundsInRoot.height)
            assertEquals(button.boundsInRoot.center + Offset(0.5f, 0.5f), glyph("暂停播放").boundsInRoot.center)
            click(Offset(20f, 20f)); assertEquals(1, clicks)
            click(button.boundsInRoot.center); assertEquals(2, clicks)
            visible.value = false; frames(30)
            assertTrue(all().none { it.config.getOrNull(SemanticsActions.OnClick) != null })
            click(Offset(320f, 200f)); assertEquals(2, clicks)
        } finally { scene.close(); scope.cancel(); scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString()) }
    }

    @Test fun materialCenterControlTracksViewportAndVisibility() = verify(AppUiStyle.MATERIAL3)
    @Test fun miuixCenterControlTracksViewportAndVisibility() = verify(AppUiStyle.MIUIX)
}
