package com.bilipai.desktop.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.android.purebilibili.feature.video.ui.gesture.GestureLevelOverlayHost
import com.android.purebilibili.feature.video.ui.section.VideoGestureMode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopGestureOverlayCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    @Test fun glassFallbackKeepsCompactGeometryAndConsumesFeedbackTaps() {
        val compact = mutableStateOf(false)
        val percent = mutableFloatStateOf(0.5f)
        val visible = mutableStateOf(true)
        var backgroundClicks = 0
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        val scene = ImageComposeScene(800, 600, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = true)) {
                    Box(Modifier.fillMaxSize().clickable { backgroundClicks++ }) {
                        GestureLevelOverlayHost(visible.value, VideoGestureMode.Volume, percent.floatValue,
                            compact = compact.value, glassEnabled = true, glassBackdrop = null,
                            glassTopInset = 23.dp, glassApplyTopSystemInsets = false)
                    }
                }
            }
        }
        fun settle() { repeat(40) {
            Snapshot.sendApplyNotifications(); scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.advanceTimeBy(16)
        }; assertTrue(errors.isEmpty(), errors.toString()) }
        fun overlay() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .filter { it.config.getOrNull(SemanticsProperties.StateDescription) != null }
            .maxBy { it.boundsInRoot.width }
        fun click(p: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons())
            settle()
        }
        try {
            settle(); assertEquals(0, backgroundClicks)
            val wide = overlay().boundsInRoot
            // Semantics follows inner padding, so the full row is 28px wider and 12px taller.
            assertEquals(332f, wide.width); assertEquals(32f, wide.height)
            assertEquals(29f, wide.top)
            assertEquals("50%", overlay().config[SemanticsProperties.StateDescription])
            click(wide.center); assertEquals(0, backgroundClicks)
            compact.value = true; percent.floatValue = 1.5f; settle()
            assertEquals(212f, overlay().boundsInRoot.width)
            assertEquals("100%", overlay().config[SemanticsProperties.StateDescription])
            click(overlay().boundsInRoot.center); assertEquals(0, backgroundClicks)
            click(Offset(700f, 500f)); assertEquals(1, backgroundClicks)
            visible.value = false; settle()
            assertTrue(scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .none { it.config.getOrNull(SemanticsProperties.StateDescription) != null })
        } finally { scene.close(); scope.cancel(); scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString()) }
    }
}
