package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.*

/** Actual production timeline modifier on a raster scene, with a competing
 * parent tap handler and real mouse events. No Window or native player is used. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopOfflinePointerInputTest {
    @Test fun primaryClickCommitsOnceWithoutTogglingTheParentControls() = scene { s ->
        s.press(25f)
        assertTrue(s.commits.isEmpty())
        s.release(25f)
        assertEquals(listOf(1 to 11_250L), s.commits)
        assertEquals(0, s.parentTaps)
        assertEquals(0, s.cancels)
    }

    @Test fun dragUsesOriginalClampingAndCommitsOnlyTheReleasePosition() = scene { s ->
        s.press(50f)
        s.move(-20f)
        s.move(125f)
        assertTrue(s.commits.isEmpty())
        assertEquals(1f, s.previews.last())
        s.release(125f)
        assertEquals(listOf(1 to 45_000L), s.commits)
        assertTrue(s.previews.contains(0f))
        assertEquals(0, s.parentTaps)
    }

    @Test fun sourceRetirementCancelsTheGestureWithoutSeekingAndAllowsTheNextGesture() = scene { s ->
        s.press(10f)
        s.current.value = false
        s.render()
        s.move(70f)
        s.release(70f)
        assertTrue(s.commits.isEmpty())
        assertEquals(1, s.cancels)
        s.current.value = true
        s.render()
        s.press(20f)
        s.release(20f)
        assertEquals(listOf(1 to 9_000L), s.commits)
    }

    @Test fun changingTheCapturedSourceCancelsRatherThanSeekingItsSuccessor() = scene { s ->
        s.press(10f)
        s.source.value = 2
        s.render()
        s.release(90f)
        assertTrue(s.commits.isEmpty())
        assertEquals(1, s.cancels)
        s.press(30f)
        s.release(30f)
        assertEquals(listOf(2 to 13_500L), s.commits)
    }

    @Test fun secondaryMouseButtonDoesNotPreviewOrCommitASeek() = scene { s ->
        s.event(PointerEventType.Press, 40f, secondary = true)
        s.event(PointerEventType.Release, 40f, secondary = true)
        assertTrue(s.commits.isEmpty())
        assertTrue(s.previews.isEmpty())
    }

    @Test fun unavailableDurationOrSourceDoesNotConsumeTheParentsClick() {
        for (missingSource in listOf(false, true)) scene { s ->
            if (missingSource) s.source.value = null else s.duration.longValue = 0
            s.render()
            s.press(25f)
            s.release(25f)
            assertTrue(s.commits.isEmpty())
            assertEquals(1, s.parentTaps)
        }
    }

    private class PointerScene : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        val source = mutableStateOf<Int?>(1)
        val duration = mutableLongStateOf(45_000L)
        val current = mutableStateOf(true)
        val commits = mutableListOf<Pair<Int?, Long>>()
        val previews = mutableListOf<Float>()
        var parentTaps = 0
        var cancels = 0
        private val scene = ImageComposeScene(100, 60, Density(1f), coroutineContext = scope.coroutineContext) {
            val identity = source.value
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { parentTaps++ })
            }) {
                Box(Modifier.fillMaxSize().desktopOfflineSeekPointerInput(identity, duration.longValue,
                    { current.value }, { previews += it }, { commits += identity to it }, { cancels++ }))
            }
        }
        init { render() }
        fun render() {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), "Unexpected Compose/pointer errors: $errors")
        }
        fun press(x: Float) = event(PointerEventType.Press, x)
        fun move(x: Float) = event(PointerEventType.Move, x)
        fun release(x: Float) = event(PointerEventType.Release, x)
        fun event(type: PointerEventType, x: Float, secondary: Boolean = false) {
            scheduler.advanceTimeBy(20)
            scheduler.runCurrent()
            val held = type != PointerEventType.Release
            scene.sendPointerEvent(type, Offset(x, 30f), timeMillis = scheduler.currentTime,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = held && !secondary, isSecondaryPressed = held && secondary),
                button = if (type == PointerEventType.Move) null else if (secondary) PointerButton.Secondary else PointerButton.Primary)
            render()
        }
        override fun close() {
            try { scene.close() } finally { scope.cancel(); scheduler.runCurrent() }
            assertTrue(errors.isEmpty(), "Unexpected close errors: $errors")
        }
    }

    private fun scene(block: (PointerScene) -> Unit) { PointerScene().use(block) }
}
