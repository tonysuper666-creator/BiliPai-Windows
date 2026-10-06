@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.live.LiveDanmakuItem
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.*

/** Actual production list on the existing CPU Compose scene. Message inputs are synthetic
 * memory data; gestures are real scene pointer events. No OS window, socket or account. */
class DesktopLiveChatMessagesUiTest {
    @Test fun full200RowRingFollowsDuringContinuousArrivalAndKeepsReplyIdentity(): Unit = runBlocking {
        val f = Scene()
        try {
            f.await { f.latestVisible() }
            val initialTail = f.lastSequence
            var followedDuringBurst = false
            repeat(36) { index ->
                f.append()
                assertEquals(200, f.messages.size)
                f.pumpFor(60)
                if (index < 35 && f.visibleRows().any { it.number > initialTail + 3 }) followedDuringBurst = true
            }
            assertTrue(followedDuringBurst, "Continuous arrivals must not postpone following until the burst ends")
            f.await { f.latestVisible() }
            val last = f.messages.last()
            f.click(assertNotNull(f.visibleRows().find { it.text == last.text }).bounds.center)
            f.await { f.replies.isNotEmpty() }
            assertSame(last, f.replies.single(), "The relocated production row must forward its original message instance")
        } finally { f.close() }
    }

    @Test fun actualWheelReadingPositionSurvivesAppendAtThe200MessageLimit(): Unit = runBlocking {
        val f = Scene()
        try {
            f.await { f.latestVisible() }
            f.readOlderRows()
            val anchor = f.visibleRows().first { it.bounds.top >= 1f && it.bounds.bottom < Scene.HEIGHT - 1f }
            repeat(12) { f.append(); f.pumpFor(60) }
            f.pumpFor(450)
            val after = assertNotNull(f.visibleRows().find { it.text == anchor.text }, "The retained reading anchor disappeared")
            assertTrue(abs(after.bounds.top - anchor.bounds.top) <= 1f, "A new message moved the visible reading anchor")
            assertFalse(f.latestVisible(), "A wheel user must not be pulled to the latest message")
            assertNotNull(f.returnButton())
        } finally { f.close() }
    }

    @Test fun lostFocusWithoutReleaseDoesNotLeaveReturnToBottomBlocked(): Unit = runBlocking {
        val f = Scene()
        try {
            f.await { f.latestVisible() }
            f.readOlderRows()
            val anchor = f.visibleRows().first { it.bounds.top >= 1f && it.bounds.bottom < Scene.HEIGHT - 1f }
            f.press(anchor.bounds.center)
            f.pumpFor(80)
            repeat(12) { f.append(); f.pumpFor(30) }
            assertFalse(f.latestVisible())
            f.focused = false
            f.pumpFor(100)
            f.focused = true
            f.pumpFor(100)
            // The held press deliberately gets no Release. A new, unpressed mouse move
            // models the returning host pointer, but cannot invoke a Release-only cleanup.
            f.moveUnpressed(Offset(8f, 8f))
            f.await { f.returnButton() != null }
            f.click(assertNotNull(f.returnButton()).boundsInRoot.center)
            f.await { f.latestVisible() }
        } finally { f.close() }
    }

    private data class VisibleRow(val text: String, val number: Int, val bounds: Rect)
    private class Scene : AutoCloseable {
        companion object { const val HEIGHT = 380 }
        private var serial = 0
        private fun message(): LiveDanmakuItem {
            val number = serial++
            return LiveDanmakuItem("live-row-$number", uid = 7L, uname = "Fixture", idStr = "message-$number")
        }
        var messages by mutableStateOf(List(200) { message() })
            private set
        val lastSequence: Int get() = serial - 1
        var focused by mutableStateOf(true)
        private val roomKey = Any()
        val replies = mutableListOf<LiveDanmakuItem>()
        private var nanos = 0L
        private var eventMillis = 0L
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless()) { "Live list verification uses the existing CPU test JVM" }
            scene = ImageComposeScene(420, HEIGHT, Density(1f)) {
                val actualWindow = LocalWindowInfo.current
                val focusWindow = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused: Boolean get() = focused
                } }
                CompositionLocalProvider(LocalWindowInfo provides focusWindow) {
                    MaterialTheme {
                        DesktopLiveChatMessages(roomKey, messages, true, { replies += it }, Modifier.fillMaxSize())
                    }
                }
            }
        }
        fun append() { messages = (messages + message()).takeLast(200) }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun visibleRows(): List<VisibleRow> = all().mapNotNull { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text ?: return@mapNotNull null
            if (!text.startsWith("live-row-")) return@mapNotNull null
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f || bounds.bottom <= 0f || bounds.top >= HEIGHT) return@mapNotNull null
            VisibleRow(text, text.removePrefix("live-row-").toInt(), bounds)
        }.sortedBy { it.bounds.top }
        fun latestVisible() = visibleRows().any { it.text == messages.last().text }
        fun returnButton() = all().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "回到底部" } == true
            }
        }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun pumpFor(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            do { render(); delay(12) } while (System.nanoTime() < end)
        }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(5_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) }
        }
        private fun time() = maxOf(System.nanoTime() / 1_000_000, eventMillis + 1).also { eventMillis = it }
        fun press(point: Offset) = scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(),
            buttons = PointerButtons(isPrimaryPressed = true))
        fun click(point: Offset) {
            press(point)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons())
        }
        fun moveUnpressed(point: Offset) = scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time(), buttons = PointerButtons())
        suspend fun readOlderRows() {
            repeat(3) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(210f, 170f), scrollDelta = Offset(0f, -6f),
                    timeMillis = time(), buttons = PointerButtons())
                pumpFor(180)
            }
            await { !latestVisible() && returnButton() != null }
            // Let the real wheel animation/input-settle jobs finish before measuring an anchor.
            pumpFor(400)
        }
        override fun close() = scene.close()
    }
}
