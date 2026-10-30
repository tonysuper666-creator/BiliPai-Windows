package com.bilipai.desktop.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.screen.InlinePortraitPlayerCollapseState
import com.android.purebilibili.feature.video.policy.reduceVideoDetailPreScroll
import com.android.purebilibili.feature.video.policy.reduceVideoDetailPostScroll
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.awt.Canvas
import java.awt.event.MouseWheelEvent
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopInlineCollapseScrollTest {
    private class Fixture(initial: Float = 0f) : AutoCloseable {
        val state = InlinePortraitPlayerCollapseState(initial)
        val range = mutableFloatStateOf(300f)
        var rendered = initial
        var measuredY = 0f
        var listPosition = 0
        val postDeltas = mutableListOf<Float>()
        val preDeltas = mutableListOf<Float>()
        val eventSource = Canvas()
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val scene = ImageComposeScene(400, 400, Density(1f), coroutineContext = scope.coroutineContext) {
            LaunchedEffect(range.floatValue) { state.reconcileLayout(true, range.floatValue) }
            val value by animateFloatAsState(state.offsetPx,
                if (state.layoutReconciled || state.restoreRequested) tween(200) else snap())
            rendered = value
            val connection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { preDeltas += available.y; return Offset(0f,
                        state.consumeScroll(reduceVideoDetailPreScroll(state.offsetPx, available.y,
                            -range.floatValue, true, false, minUpdateDeltaPx = 0f))) }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset { postDeltas += available.y; return Offset(0f,
                        state.consumeScroll(reduceVideoDetailPostScroll(state.offsetPx, available.y,
                            -range.floatValue, true, false, minUpdateDeltaPx = 0f))) }
                }
            }
            val scroll = rememberLazyListState()
            Box(Modifier.fillMaxSize().desktopInlineCollapseScrollBoundary(connection) { !scroll.canScrollBackward }.nestedScroll(connection)) {
                listPosition = scroll.firstVisibleItemIndex * 40 + scroll.firstVisibleItemScrollOffset
                LazyColumn(Modifier.fillMaxSize(), state = scroll) {
                    items(20) { Box(Modifier.height(40.dp).fillMaxWidth()) }
                }
                Box(Modifier.offset { IntOffset(0, value.roundToInt()) }.size(1.dp)
                    .onGloballyPositioned { measuredY = it.positionInRoot().y })
            }
        }
        fun frame() { Snapshot.sendApplyNotifications(); scheduler.advanceTimeBy(16); scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000).close(); scheduler.runCurrent() }
        fun settle() { repeat(40) { frame() } }
        fun wheel(delta: Float, frames: Int = 40) {
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 200f), scrollDelta = Offset(0f, delta), timeMillis = scheduler.currentTime,
                nativeEvent = MouseWheelEvent(eventSource, MouseWheelEvent.MOUSE_WHEEL, scheduler.currentTime, 0,
                    200, 200, 200, 200, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, delta.toInt(), delta.toDouble()))
            var previous = state.offsetPx
            repeat(frames) {
                // AWT wheel dispatch also uses the platform event loop, outside the animation test clock.
                if (frames == 1) { java.awt.EventQueue.invokeAndWait {}; Thread.sleep(2) }
                frame()
                assertTrue(state.offsetPx in -range.floatValue..0f)
                assertTrue(if (delta > 0) state.offsetPx <= previous else state.offsetPx >= previous,
                    "collapse oscillated during one scroll event: $previous -> ${state.offsetPx}")
                previous = state.offsetPx
            }
        }
        override fun close() { scene.close(); scope.cancel(); scheduler.runCurrent() }
    }

    @Test fun wheelNotchesAndContinuousTrackpadDeltasRemainBoundedAcrossReversalsAndListTop() {
        for (delta in listOf(12f, 0.2f)) Fixture().use { f ->
            f.settle()
            repeat(160) {
                val before = f.state.offsetPx
                f.wheel(delta)
                assertTrue(f.state.offsetPx <= before, "upward collapse must be monotonic")
                assertTrue(f.state.offsetPx in -300f..0f)
            }
            assertEquals(-300f, f.state.offsetPx)
            repeat(400) {
                val before = f.state.offsetPx
                f.wheel(-delta)
                assertTrue(f.state.offsetPx >= before, "return to list top must not collapse again")
                assertTrue(f.state.offsetPx in -300f..0f)
            }
            assertEquals(0f, f.state.offsetPx, "delta=$delta after return list=${f.listPosition} post=${f.postDeltas.takeLast(15)}")
            repeat(8) {
                f.wheel(delta)
                assertTrue(f.state.offsetPx < 0f)
                f.wheel(-delta)
                assertEquals(0f, f.state.offsetPx)
            }
        }
    }

    @Test fun continuousSmallDeltasReverseAtTheListTopWithoutLeavingTheCollapseRange() {
        Fixture().use { f ->
            f.settle()
            repeat(200) { f.wheel(0.2f, 1) }
            f.settle()
            assertEquals(-300f, f.state.offsetPx)
            repeat(600) { f.wheel(-0.2f, 1) }
            f.settle()
            assertEquals(0f, f.state.offsetPx, "list=${f.listPosition} pre=${f.preDeltas.takeLast(15)} post=${f.postDeltas.takeLast(15)}")
            assertEquals(0, f.listPosition)
            repeat(4) {
                repeat(40) { f.wheel(0.2f, 1) }
                f.settle()
                assertTrue(f.state.offsetPx < 0f)
                repeat(160) { f.wheel(-0.2f, 1) }
                f.settle()
                assertEquals(0f, f.state.offsetPx)
            }
        }
    }

    @Test fun preciseSubPixelDeltasAreNotDiscarded() {
        Fixture(-20f).use { f ->
            f.settle()
            repeat(20) { f.wheel(0.06f, 1) }
            f.settle()
            assertTrue(f.state.offsetPx < -20f)
            val collapsed = f.state.offsetPx
            repeat(20) { f.wheel(-0.06f, 1) }
            f.settle()
            assertTrue(f.state.offsetPx > collapsed)
        }
    }

    @Test fun tabRangeCorrectionEndsAtTheExactDirectlySetValue() {
        Fixture(-280f).use { f ->
            f.settle()
            f.range.floatValue = 123f
            f.settle()
            assertEquals(-123f, f.state.offsetPx)
            assertEquals(-123f, f.rendered)
            assertEquals(-123f, f.measuredY)
            assertTrue(f.state.layoutReconciled)
            Fixture(-123f).use { direct ->
                direct.settle()
                assertEquals(direct.state.offsetPx, f.state.offsetPx)
                assertEquals(direct.rendered, f.rendered)
                assertEquals(direct.measuredY, f.measuredY)
                assertEquals(direct.state.restoreRequested, f.state.restoreRequested)
            }
            f.range.floatValue = 300f
            f.settle()
            assertEquals(-123f, f.state.offsetPx)
            assertEquals(-123f, f.rendered)
            assertEquals(-123f, f.measuredY)
        }
    }
}
