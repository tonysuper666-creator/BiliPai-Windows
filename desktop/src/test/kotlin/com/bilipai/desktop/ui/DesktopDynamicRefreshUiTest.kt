@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.DynamicFeedData
import com.android.purebilibili.data.model.response.DynamicFeedResponse
import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.DynamicScrollRequest
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TON-1026, the dynamic page on isolated data: the real DesktopDynamicTimelineFeed with an in-memory feed transport, no account, no
 * network, no window. The corner button, F5 / Ctrl+R (registry and the root scroll channel), the busy state, a failed refresh with its
 * toast and the recovery, and the end of the list clear of the button.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("coil3.SingletonImageLoader")
class DesktopDynamicRefreshUiTest {
    private class RecordingToaster : DesktopToaster {
        val messages = mutableListOf<String>()
        override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) { messages += message }
    }

    private class Fixture : AutoCloseable {
        val requests = mutableListOf<String>()
        var failNext = false
        var gate: CompletableDeferred<Unit>? = null
        val toaster = RecordingToaster()
        val rootScroll = Channel<DynamicScrollRequest>(Channel.CONFLATED)
        private val directory = Files.createTempDirectory("bilipai-dynamic-refresh-ui-")
        private val preferences = DesktopDynamicTimelinePreferences(DesktopPluginContext(DesktopPluginStore(directory)))
        val state = DesktopDynamicTimelineState("all", { _, offset, _ ->
            requests += offset.ifEmpty { "first-page" }
            gate?.await()
            if (failNext) { failNext = false; throw IllegalStateException("网络不可用") }
            DynamicFeedResponse(data = DynamicFeedData((1..12).map { fixtureDynamic("row$it", 1_000L - it) }, "", false, "", 0))
        })
        private var nanos = 0L
        private var eventMillis = 0L
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(520, 420, Density(1f)) {
                val actualWindow = LocalWindowInfo.current
                val focused = androidx.compose.runtime.remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused = true
                } }
                CompositionLocalProvider(
                    LocalWindowInfo provides focused,
                    LocalDesktopToaster provides toaster,
                    LocalDesktopRootDynamicScroll provides rootScroll,
                ) {
                    MaterialTheme {
                        DesktopDynamicTimelineFeed(state, preferences, onLogin = {}) { item ->
                            Text(item.id_str, Modifier.fillMaxWidth().height(110.dp))
                        }
                    }
                }
            }
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun button(name: String) = all().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true &&
                node.config.getOrNull(SemanticsProperties.Role) != null
        }
        fun text(value: String) = all().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        }
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun pumpFor(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            do { render(); delay(12) } while (System.nanoTime() < end)
        }
        suspend fun await(what: String = "condition", condition: () -> Boolean) {
            try {
                withTimeout(6_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
        }
        suspend fun awaitButton(name: String): SemanticsNode { await("button $name") { button(name) != null }; return assertNotNull(button(name)) }
        private fun time() = maxOf(System.nanoTime() / 1_000_000, eventMillis + 1).also { eventMillis = it }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time())
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(), buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons())
        }
        override fun close() { scene.close(); rootScroll.close(); directory.toFile().deleteRecursively() }
    }

    private suspend fun ready(f: Fixture): SemanticsNode {
        f.await("first page") { f.text("row1") != null }
        return f.awaitButton("刷新")
    }

    @Test fun theCornerButtonRefreshesTheFeedOnceIsBusyWhileItRunsAndFreesAgain() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val button = ready(f)
            assertEquals(1, f.requests.size)
            f.gate = CompletableDeferred()
            f.click(button.boundsInRoot.center)
            f.await("refresh request") { f.requests.size == 2 }
            f.awaitButton("正在刷新")
            f.click(f.awaitButton("正在刷新").boundsInRoot.center)
            f.pumpFor(200)
            assertEquals(2, f.requests.size, "a click during the refresh must not start another")
            f.gate!!.complete(Unit)
            f.awaitButton("刷新")
            assertTrue(f.toaster.messages.isEmpty())
        } finally { f.close() }
    }

    @Test fun f5ThroughTheRegistryAndTheRootScrollChannelRunTheSameRefreshAsTheClick() = runBlocking<Unit> {
        val f = Fixture()
        try {
            ready(f)
            assertTrue(DesktopRefreshRegistry.shared.refreshTopmost(DESKTOP_REFRESH_SHORTCUT_SOURCE))
            f.await("registry refresh") { f.requests.size == 2 }
            f.awaitButton("刷新")
            // Without a visible button the shell sends the scroll request: it must refresh from any scroll position.
            f.rootScroll.trySend(DynamicScrollRequest.SCROLL_TO_TOP_AND_REFRESH)
            f.await("channel refresh") { f.requests.size == 3 }
            f.awaitButton("刷新")
        } finally { f.close() }
    }

    @Test fun aFailedRefreshToastsTheReasonKeepsTheRowsAndTheButtonWorksAgain() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val button = ready(f)
            f.failNext = true
            f.click(button.boundsInRoot.center)
            f.await("failure toast") { f.toaster.messages.isNotEmpty() }
            assertEquals(listOf("刷新失败：网络不可用"), f.toaster.messages)
            assertNotNull(f.text("row1"), "the rows stay when a refresh fails")
            val again = f.awaitButton("刷新")
            f.click(again.boundsInRoot.center)
            f.await("recovered refresh") { f.requests.size == 3 }
            f.awaitButton("刷新")
            assertEquals(1, f.toaster.messages.size)
        } finally { f.close() }
    }

    @Test fun theEndOfTheFeedScrollsClearOfTheButton() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val button = ready(f)
            f.state.scroll.scrollToItem(f.state.page.items.size)
            f.pumpFor(300)
            val last = assertNotNull(f.text("row12"), "the last row is laid out after scrolling to the end").boundsInRoot
            assertFalse(last.overlaps(button.boundsInRoot), "last row $last must not sit under the button ${button.boundsInRoot}")
        } finally { f.close() }
    }

    private fun Rect.overlaps(other: Rect) = intersect(other).let { it.width > 0f && it.height > 0f }
}
