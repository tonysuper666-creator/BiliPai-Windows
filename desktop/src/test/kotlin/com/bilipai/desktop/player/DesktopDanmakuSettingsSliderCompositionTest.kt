package com.bilipai.desktop.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.ui.components.DanmakuSettingsSlider
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDanmakuSettingsSliderCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    private inner class Fixture : AutoCloseable {
        val visible = mutableStateOf(true)
        val value = mutableFloatStateOf(0.25f)
        val version = mutableIntStateOf(1)
        val previews = mutableListOf<Float>()
        val commits = mutableListOf<Pair<Int, Float>>()
        val scene = ImageComposeScene(400, 160, Density(1f)) {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    if (visible.value) {
                        val callbackVersion = version.intValue
                        DanmakuSettingsSlider("透明度", { "${(it * 100).toInt()}%" }, value.floatValue,
                            0f..1f, 0, 0.5f, { previews += it },
                            { commits += callbackVersion to it }, commitOnDispose = true)
                    }
                }
            }
        }
        fun settle() { repeat(3) { Snapshot.sendApplyNotifications(); scene.render().close() } }
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun slider() = all().single { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
        fun send(type: PointerEventType, point: Offset, down: Boolean) {
            scene.sendPointerEvent(type, point, buttons = PointerButtons(isPrimaryPressed = down)); settle()
        }
        fun beginDrag(): Offset {
            val bounds = slider().boundsInRoot
            val start = Offset(bounds.left + bounds.width * 0.25f, bounds.center.y)
            val end = Offset(bounds.left + bounds.width * 0.75f, bounds.center.y)
            send(PointerEventType.Press, start, true)
            send(PointerEventType.Move, end, true)
            return end
        }
        override fun close() { scene.close() }
    }

    @Test fun dragPreviewsWithoutSavingUntilReleaseAndResetUsesExactDefault() {
        Fixture().use { f ->
            f.settle()
            assertTrue(f.previews.isEmpty()); assertTrue(f.commits.isEmpty())
            val end = f.beginDrag()
            assertTrue(f.previews.isNotEmpty())
            assertTrue(f.commits.isEmpty(), "A held pointer only previews")
            val preview = f.previews.last()
            assertTrue(preview > 0.5f && preview <= 1f)
            f.send(PointerEventType.Release, end, false)
            assertEquals(listOf(1 to preview), f.commits)
            val reset = f.all().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("恢复透明度默认值") } == true }
            val point = reset.boundsInRoot.center
            f.send(PointerEventType.Press, point, true)
            f.send(PointerEventType.Release, point, false)
            assertEquals(0.5f, f.previews.last())
            assertEquals(listOf(1 to preview, 1 to 0.5f), f.commits)
            assertEquals(0.5f, f.slider().config[SemanticsProperties.ProgressBarRangeInfo].current)
        }
    }

    @Test fun removingHeldSliderCommitsLatestPreviewToLatestCallbackOnlyOnce() {
        Fixture().use { f ->
            f.settle()
            f.beginDrag()
            val preview = f.previews.last()
            f.version.intValue = 2
            f.value.floatValue = 0.1f
            f.settle()
            assertEquals(preview, f.slider().config[SemanticsProperties.ProgressBarRangeInfo].current,
                "External state cannot overwrite an active drag")
            assertTrue(f.commits.isEmpty())
            f.visible.value = false
            f.settle()
            assertEquals(listOf(2 to preview), f.commits)
            f.settle()
            assertEquals(listOf(2 to preview), f.commits)
            assertTrue(f.all().none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        }
    }
}
