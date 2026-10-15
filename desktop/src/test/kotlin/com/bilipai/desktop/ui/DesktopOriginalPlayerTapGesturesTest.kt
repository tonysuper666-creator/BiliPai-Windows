package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import com.android.purebilibili.feature.video.ui.gesture.detectPlayerTapGestures
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Actual original detector on a raster Compose scene; no window, OS input, player, GPU or audio. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopOriginalPlayerTapGesturesTest {
    @Test
    fun aShortMousePressWaitsForTheDoubleClickWindowThenReportsOneTap() = withScene {
        click(10, 30, Offset(20f, 20f))
        assertTrue(events.isEmpty())
        advanceTo(329)
        assertTrue(events.isEmpty())
        advanceTo(331)
        assertEquals(listOf(Click(0, false, Offset(20f, 20f))), events)
    }

    @Test
    fun nearbyShortMouseClicksReportOneDoubleClick() = withScene {
        click(10, 30, Offset(20f, 20f))
        click(100, 120, Offset(24f, 20f))
        advanceTo(700)
        assertEquals(listOf(Click(0, true, Offset(24f, 20f))), events)
    }

    @Test
    fun aDistantSecondTapReportsTheFirstAndCanStartANewDoubleClick() = withScene {
        click(10, 30, Offset(20f, 20f))
        click(100, 120, Offset(100f, 20f))
        click(190, 210, Offset(104f, 20f))
        advanceTo(700)
        assertEquals(listOf(Click(0, false, Offset(20f, 20f)), Click(0, true, Offset(104f, 20f))), events)
    }

    @Test
    fun aLongPressReleaseDoesNotSuppressAnImmediateIndependentShortClick() = withScene {
        press(10, Offset(20f, 20f))
        advanceTo(600)
        release(620, Offset(20f, 20f))
        click(640, 670, Offset(24f, 20f))
        advanceTo(1000)
        assertEquals(listOf(Click(0, false, Offset(24f, 20f))), events)
    }

    @Test
    fun aLongSecondPressReportsOnlyTheCompletedFirstTap() = withScene {
        click(10, 30, Offset(20f, 20f))
        press(100, Offset(24f, 20f))
        advanceTo(650)
        release(670, Offset(24f, 20f))
        advanceTo(1100)
        assertEquals(listOf(Click(0, false, Offset(20f, 20f))), events)
    }

    @Test
    fun replacingTheSourceCancelsItsPendingTapBeforeNewSourceEvents() = withScene {
        click(10, 30, Offset(20f, 20f))
        advanceTo(100)
        source.intValue++
        render()
        click(150, 180, Offset(24f, 20f))
        advanceTo(500)
        assertEquals(listOf(Click(1, false, Offset(24f, 20f))), events)
    }

    @Test
    fun closingTheSceneCancelsItsPendingTapWithoutCallingTheRetiredOwner() = withScene {
        click(10, 30, Offset(20f, 20f))
        advanceTo(100)
        closeScene()
        advanceTo(600)
        assertTrue(events.isEmpty())
    }

    @Test
    fun touchInputUsesTheSameOriginalShortPressAndDistanceRules() = withScene(PointerType.Touch) {
        click(10, 30, Offset(20f, 20f))
        click(100, 120, Offset(24f, 20f))
        advanceTo(700)
        assertEquals(listOf(Click(0, true, Offset(24f, 20f))), events)
    }

    private data class Click(val source: Int, val double: Boolean, val position: Offset)

    private fun withScene(type: PointerType = PointerType.Mouse, block: PointerScene.() -> Unit) {
        val fixture = PointerScene(type)
        try { fixture.block() } finally { fixture.close() }
    }

    private class PointerScene(private val type: PointerType) {
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        val events = mutableListOf<Click>()
        val source = mutableIntStateOf(0)
        private var closed = false
        private val configuration = object : ViewConfiguration {
            override val longPressTimeoutMillis = 500L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }
        private val scene = ImageComposeScene(width = 256, height = 128,
            coroutineContext = scope.coroutineContext) {
            CompositionLocalProvider(LocalViewConfiguration provides configuration) {
                val owner = source.intValue
                Box(Modifier.fillMaxSize().pointerInput(owner) {
                    detectPlayerTapGestures(
                        onTap = { events += Click(owner, false, it) },
                        onDoubleTap = { events += Click(owner, true, it) },
                    )
                })
            }
        }
        init { render() }

        fun click(down: Long, up: Long, position: Offset) {
            press(down, position)
            release(up, position)
        }
        fun press(time: Long, position: Offset) = event(PointerEventType.Press, time, position)
        fun release(time: Long, position: Offset) = event(PointerEventType.Release, time, position)
        private fun event(eventType: PointerEventType, time: Long, position: Offset) {
            advanceTo(time)
            scene.sendPointerEvent(eventType = eventType, position = position, timeMillis = time,
                type = type, buttons = PointerButtons(isPrimaryPressed = eventType == PointerEventType.Press),
                button = if (type == PointerType.Mouse) PointerButton.Primary else null)
            scheduler.runCurrent()
            render()
        }
        fun advanceTo(time: Long) {
            require(time >= scheduler.currentTime)
            scheduler.advanceTimeBy(time - scheduler.currentTime)
            scheduler.runCurrent()
            if (!closed) render()
        }
        fun render() {
            check(!closed)
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.runCurrent()
        }
        fun closeScene() {
            if (closed) return
            scene.close()
            closed = true
            scheduler.runCurrent()
        }
        fun close() {
            try { closeScene() } finally {
                scope.cancel()
                scheduler.runCurrent()
            }
            assertTrue(errors.isEmpty(), "Unexpected Compose/coroutine errors: $errors")
        }
    }
}
