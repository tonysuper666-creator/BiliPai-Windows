@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox
import java.awt.GraphicsEnvironment
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
 * TON-1026. The pure click bookkeeping of the desktop refresh button, and the real patched upstream AdaptivePullToRefreshBox in
 * the existing CPU scene for both renderers it dispatches on: the corner button exists and is big enough, a click refreshes once,
 * a second click during the refresh is ignored, a disabled box has no button, and a mouse drag from the top does not refresh.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("coil3.SingletonImageLoader")
class DesktopPageRefreshButtonTest {
    // ---- the pure model -----------------------------------------------------------------------------------------------

    @Test fun anIdleClickStartsOneRefreshAndHoldsTheButtonBusy() {
        val (held, start) = DesktopRefreshButtonState().click(refreshing = false, nowMs = 1_000)
        assertTrue(start)
        assertTrue(held.busy(refreshing = false, nowMs = 1_000))
        assertTrue(held.busy(refreshing = false, nowMs = 1_000 + DESKTOP_REFRESH_PENDING_TIMEOUT_MS - 1))
    }

    @Test fun aSecondClickBeforeTheOwnerReportsRefreshingIsIgnored() {
        val (held, _) = DesktopRefreshButtonState().click(refreshing = false, nowMs = 1_000)
        val (again, start) = held.click(refreshing = false, nowMs = 1_050)
        assertFalse(start)
        assertEquals(held, again)
    }

    @Test fun aClickWhileTheOwnerIsRefreshingIsIgnored() {
        val (state, start) = DesktopRefreshButtonState().click(refreshing = true, nowMs = 1_000)
        assertFalse(start)
        assertNull(state.pendingSinceMs)
    }

    @Test fun theHoldEndsWhenTheOwnerAnswersAndWhenTheTimeoutRunsOut() {
        val (held, _) = DesktopRefreshButtonState().click(refreshing = false, nowMs = 1_000)
        val taken = held.onRefreshing(true)
        assertNull(taken.pendingSinceMs)
        assertTrue(taken.busy(refreshing = true, nowMs = 1_100))
        assertFalse(taken.busy(refreshing = false, nowMs = 9_000))
        // An owner that declines the refresh never reports isRefreshing: the button frees itself and a new click works.
        val expiredAt = 1_000 + DESKTOP_REFRESH_PENDING_TIMEOUT_MS
        assertFalse(held.busy(refreshing = false, nowMs = expiredAt))
        assertTrue(held.click(refreshing = false, nowMs = expiredAt).second)
        // The owner finishing without the button being asked leaves a clean state alone.
        assertEquals(DesktopRefreshButtonState(), DesktopRefreshButtonState().onRefreshing(false))
    }

    @Test fun theDesktopNeverMountsThePullGestureAndTheTargetIsBigEnough() {
        assertFalse(desktopPullGestureEnabled(true))
        assertFalse(desktopPullGestureEnabled(false))
        assertTrue(DESKTOP_REFRESH_BUTTON_SIZE_DP >= 40)
        // The back-to-top button rises by one button height and gap, so the two never overlap in the shared corner.
        assertTrue(DESKTOP_REFRESH_STACK_LIFT_DP >= DESKTOP_REFRESH_BUTTON_SIZE_DP)
        assertEquals("刷新", desktopRefreshButtonName(busy = false))
        assertEquals("F5", DESKTOP_REFRESH_SHORTCUT)
    }

    @Test fun theRefreshLogLinesNameTheSourceAndTheDuration() {
        assertEquals("刷新开始：来源=按钮", desktopRefreshStartLog("按钮"))
        assertEquals("刷新开始：来源=F5/Ctrl+R，页面=HOME", desktopRefreshShortcutStartLog("HOME"))
        assertEquals("刷新结束：用时=420ms", desktopRefreshEndLog(1_000, 1_420))
        assertEquals("刷新结束：用时=0ms", desktopRefreshEndLog(2_000, 1_000))
        assertEquals("DesktopRefresh", DESKTOP_REFRESH_LOG_TAG)
    }

    @Test fun aFailedRefreshIsAnnouncedWithItsFirstReasonLine() {
        assertEquals("刷新失败，请稍后重试", desktopRefreshFailureMessage(null))
        assertEquals("刷新失败，请稍后重试", desktopRefreshFailureMessage(IllegalStateException("")))
        assertEquals("刷新失败：网络不可用", desktopRefreshFailureMessage(IllegalStateException("网络不可用\n    at x.y(Z.kt:1)")))
        assertTrue(desktopRefreshFailureMessage(IllegalStateException("x".repeat(500))).length < 80)
    }

    // ---- the real box in the CPU scene --------------------------------------------------------------------------------------

    @Test fun materialBoxHasACornerButtonThatRefreshesOnce() = runBlocking { cornerButtonRefreshesOnce(AppUiStyle.MATERIAL3) }
    @Test fun miuixBoxHasACornerButtonThatRefreshesOnce() = runBlocking { cornerButtonRefreshesOnce(AppUiStyle.MIUIX) }

    private suspend fun cornerButtonRefreshesOnce(style: AppUiStyle) {
        val f = Scene(style)
        try {
            val button = f.awaitButton("刷新")
            val bounds = button.boundsInRoot
            assertTrue(bounds.width >= 40f && bounds.height >= 40f, "click target ${bounds.width}x${bounds.height} dp")
            assertTrue(bounds.right > Scene.WIDTH - 40f && bounds.bottom > Scene.HEIGHT - 40f, "bottom right corner: $bounds")
            f.click(bounds.center)
            f.await { f.refreshes == 1 }
            // The owner has not answered yet (isRefreshing is still false): a double click must not start a second refresh.
            f.click(bounds.center)
            f.pumpFor(150)
            assertEquals(1, f.refreshes)
            assertEquals(listOf("prelude", "refresh"), f.order)
            // The owner reports the refresh: the button says so and a click is ignored for the whole refresh.
            f.refreshing = true
            f.awaitButton("正在刷新")
            f.click(f.awaitButton("正在刷新").boundsInRoot.center)
            f.pumpFor(150)
            assertEquals(1, f.refreshes)
            // The refresh ends: the button is ready again and the next click refreshes again.
            f.refreshing = false
            val ready = f.awaitButton("刷新")
            f.click(ready.boundsInRoot.center)
            f.await { f.refreshes == 2 }
        } finally { f.close() }
    }

    @Test fun aBoxThatDisabledRefreshHasNoButton() = runBlocking {
        val f = Scene(AppUiStyle.MATERIAL3, enabled = false)
        try {
            f.pumpFor(300)
            assertNull(f.button("刷新"))
            assertNull(f.button("正在刷新"))
        } finally { f.close() }
    }

    /**
     * A guard, not a proof of the cause: the CPU scene sends mouse events, and Compose's pull needs touch overscroll, so this drag
     * does not start a refresh with the gesture gate open either (checked by opening the gate once). What it does keep honest is that
     * nothing starts a refresh from a drag, no indicator is left half way, and the button still answers afterwards.
     * The switch itself is pinned by theDesktopNeverMountsThePullGesture... above.
     */
    @Test fun aMouseDragDownFromTheTopOfTheListDoesNotRefresh() = runBlocking {
        for (style in AppUiStyle.entries) {
            val f = Scene(style)
            try {
                f.awaitButton("刷新")
                f.drag(Offset(200f, 40f), Offset(200f, 380f))
                f.pumpFor(400)
                assertEquals(0, f.refreshes, style.name)
                // No half-way indicator is left behind and the button still answers.
                f.click(f.awaitButton("刷新").boundsInRoot.center)
                f.await { f.refreshes == 1 }
            } finally { f.close() }
        }
    }

    private class Scene(style: AppUiStyle, enabled: Boolean = true) : AutoCloseable {
        companion object { const val WIDTH = 520f; const val HEIGHT = 420f }
        var refreshing by mutableStateOf(false)
        var refreshes = 0
        val order = mutableListOf<String>()
        private var nanos = 0L
        private var eventMillis = 0L
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(WIDTH.toInt(), HEIGHT.toInt(), Density(1f)) {
                val actualWindow = LocalWindowInfo.current
                val focused = androidx.compose.runtime.remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused = true
                } }
                CompositionLocalProvider(
                    LocalWindowInfo provides focused,
                    LocalAppUiStyle provides style,
                    LocalDesktopRefreshPrelude provides { order += "prelude" },
                ) {
                    MaterialTheme {
                        AdaptivePullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = { refreshes++; order += "refresh" },
                            modifier = Modifier.fillMaxSize(),
                            enabled = enabled,
                        ) {
                            LazyColumn(Modifier.fillMaxSize()) { items(60) { Text("row $it") } }
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
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun pumpFor(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            do { render(); delay(12) } while (System.nanoTime() < end)
        }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(5_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) }
        }
        suspend fun awaitButton(name: String): SemanticsNode {
            await { button(name) != null }
            return assertNotNull(button(name))
        }
        private fun time() = maxOf(System.nanoTime() / 1_000_000, eventMillis + 1).also { eventMillis = it }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time())
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(), buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons())
        }
        fun drag(from: Offset, to: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = time(), buttons = PointerButtons(isPrimaryPressed = true))
            for (step in 1..12) {
                val point = Offset(from.x + (to.x - from.x) * step / 12f, from.y + (to.y - from.y) * step / 12f)
                scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time(), buttons = PointerButtons(isPrimaryPressed = true))
                render()
            }
            scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = time(), buttons = PointerButtons())
        }
        override fun close() = scene.close()
    }
}
