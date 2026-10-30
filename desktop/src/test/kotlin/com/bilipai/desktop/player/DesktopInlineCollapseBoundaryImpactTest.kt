package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.*
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
class DesktopInlineCollapseBoundaryImpactTest {
    private class Fixture(val nested: Boolean = false, val horizontal: Boolean = false, initialIndex: Int = 0) : AutoCloseable {
        val collapse = InlinePortraitPlayerCollapseState(-100f)
        val outer = LazyListState()
        val target = LazyListState(initialIndex)
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val source = Canvas()
        val scene = ImageComposeScene(400, 400, Density(1f), coroutineContext = scope.coroutineContext) {
            val connection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource) = Offset(0f,
                        collapse.consumeScroll(reduceVideoDetailPreScroll(collapse.offsetPx, available.y, -300f, true, false, minUpdateDeltaPx = 0f)))
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f,
                        collapse.consumeScroll(reduceVideoDetailPostScroll(collapse.offsetPx, available.y, -300f, true, false, minUpdateDeltaPx = 0f)))
                }
            }
            Box(Modifier.fillMaxSize().desktopInlineCollapseScrollBoundary(connection) {
                !outer.canScrollBackward && (horizontal || !target.canScrollBackward)
            }.nestedScroll(connection)) {
                if (nested || horizontal) {
                    LazyColumn(Modifier.fillMaxSize(), state = outer) {
                        item {
                            if (horizontal) LazyRow(Modifier.height(300.dp).fillMaxWidth(), state = target) {
                                items(30) { Box(Modifier.width(40.dp).fillMaxHeight()) }
                            } else LazyColumn(Modifier.height(300.dp).fillMaxWidth(), state = target) {
                                items(30) { Box(Modifier.height(40.dp).fillMaxWidth()) }
                            }
                        }
                        items(20) { Box(Modifier.height(40.dp).fillMaxWidth()) }
                    }
                } else LazyColumn(Modifier.fillMaxSize(), state = target) {
                    items(30) { Box(Modifier.height(40.dp).fillMaxWidth()) }
                }
            }
        }
        fun frame() { Snapshot.sendApplyNotifications(); scheduler.advanceTimeBy(16); scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000).close(); scheduler.runCurrent() }
        fun settle() { repeat(40) { frame() } }
        fun send(delta: Offset) {
            val rotation = if (delta.y != 0f) delta.y else delta.x
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 150f), scrollDelta = delta,
                timeMillis = scheduler.currentTime,
                nativeEvent = MouseWheelEvent(source, MouseWheelEvent.MOUSE_WHEEL, scheduler.currentTime, 0,
                    200, 150, 200, 150, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation.toInt(), rotation.toDouble()))
            java.awt.EventQueue.invokeAndWait {}; Thread.sleep(2); frame()
        }
        fun position() = target.firstVisibleItemIndex * 40 + target.firstVisibleItemScrollOffset
        override fun close() { scene.close(); scope.cancel(); scheduler.runCurrent() }
    }
    @Test fun atTopOneReverseEventIsConsumedExactlyOnce() {
        Fixture().use { f ->
            f.settle()
            // Calibrate against normal Compose dispatch, not a hard-coded OS wheel multiplier.
            f.send(Offset(0f, 0.06f))
            f.settle()
            assertTrue(f.collapse.offsetPx < -100f)
            f.send(Offset(0f, -0.06f))
            assertEquals(-100f, f.collapse.offsetPx, 0.0001f)
            f.settle()
            assertEquals(-100f, f.collapse.offsetPx, 0.0001f)
            assertEquals(0, f.position())
        }
    }
    @Test fun innerVerticalListReturnsBeforeParentExpands() {
        for (nested in listOf(false, true)) Fixture(nested = nested, initialIndex = 5).use { f ->
            f.settle()
            val before = f.position()
            repeat(5) { f.send(Offset(0f, -0.2f)) }
            f.settle()
            assertTrue(f.position() < before)
            assertTrue(f.position() > 0)
            assertEquals(-100f, f.collapse.offsetPx)
            assertEquals(0, f.outer.firstVisibleItemIndex)
        }
    }
    @Test fun horizontalNestedListKeepsItsOwnWheelInput() {
        Fixture(horizontal = true, initialIndex = 5).use { f ->
            f.settle()
            val before = f.position()
            repeat(5) { f.send(Offset(-0.2f, 0f)) }
            f.settle()
            assertTrue(f.position() < before)
            assertEquals(-100f, f.collapse.offsetPx)
            assertEquals(0, f.outer.firstVisibleItemIndex)
        }
    }
}
