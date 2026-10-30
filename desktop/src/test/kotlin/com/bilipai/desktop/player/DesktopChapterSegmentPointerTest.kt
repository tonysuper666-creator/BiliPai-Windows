package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.ViewPoint
import com.android.purebilibili.feature.video.ui.overlay.ViewPointSegmentBar
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopChapterSegmentPointerTest {
    @Test fun minimumWidthShortChapterSeeksItsOwnStartAndUsesLatestCallback() {
        val events = mutableListOf<String>()
        val epoch = mutableIntStateOf(1)
        val interactive = mutableStateOf(true)
        val points = listOf(ViewPoint(from = 0, to = 2, content = "短章"), ViewPoint(from = 2, to = 100, content = "后续"))
        val scene = ImageComposeScene(400, 18, Density(1f)) {
            val captured = epoch.intValue
            ViewPointSegmentBar(points, 100_000, 0, { events += "$captured:$it" }, interactive = interactive.value)
        }
        try {
            scene.render().close()
            assertTrue(events.isEmpty())
            // The 2-second chapter is displayed at 44px, not its proportional 8px.
            click(scene, 30f, 100)
            assertEquals(listOf("1:0"), events)
            epoch.intValue = 2
            scene.render().close()
            click(scene, 200f, 200)
            assertEquals(listOf("1:0", "2:2000"), events)
            interactive.value = false
            scene.render().close()
            click(scene, 30f, 300)
            assertEquals(listOf("1:0", "2:2000"), events)
        } finally { scene.close() }
    }

    @Test fun denseChaptersKeepTheirDisplayedTargetsAndEmptyInputDoesNotSeek() {
        val events = mutableListOf<Long>()
        val points = mutableStateOf((0 until 10).map { ViewPoint(from = it * 10, to = (it + 1) * 10, content = "章$it") })
        val scene = ImageComposeScene(200, 18, Density(1f)) {
            ViewPointSegmentBar(points.value, 100_000, 0, { events += it })
        }
        try {
            scene.render().close()
            // Too many short chips: the policy falls back to proportional 20px allocations.
            click(scene, 110f, 100)
            assertEquals(listOf(50_000L), events)
            points.value = emptyList()
            scene.render().close()
            click(scene, 110f, 200)
            assertEquals(listOf(50_000L), events)
        } finally { scene.close() }
    }

    private fun click(scene: ImageComposeScene, x: Float, time: Long) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, 9f), timeMillis = time,
            buttons = PointerButtons(isPrimaryPressed = true))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, 9f), timeMillis = time + 40,
            buttons = PointerButtons())
        scene.render().close()
    }
}
