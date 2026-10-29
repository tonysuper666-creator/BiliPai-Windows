@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppLiquidGlassBackToTopButton
import kotlinx.coroutines.flow.flowOf
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

    // ---- F5 / Ctrl+R reach the buttons ----------------------------------------------------------------------------------

    private fun target(visible: Boolean, calls: MutableList<String>, name: String, answer: Boolean = true) =
        DesktopRefreshTarget { source -> calls += "$name:$source"; answer }.also { it.visible = visible }

    @Test fun theNewestVisibleButtonTakesTheKeyAndAHiddenOneNeverDoes() {
        val calls = mutableListOf<String>()
        val registry = DesktopRefreshTargets()
        assertFalse(registry.refreshTopmost("F5"))
        val page = target(true, calls, "page")
        val sheet = target(true, calls, "sheet")
        val offscreen = target(false, calls, "pager-page-off-screen")
        listOf(page, sheet, offscreen).forEach(registry::register)
        assertTrue(registry.refreshTopmost("F5"))
        assertEquals(listOf("sheet:F5"), calls)
        registry.unregister(sheet)
        assertTrue(registry.refreshTopmost("F5/Ctrl+R"))
        assertEquals(listOf("sheet:F5", "page:F5/Ctrl+R"), calls)
        page.visible = false
        assertFalse(registry.refreshTopmost("F5"))
        assertEquals(2, calls.size)
    }

    @Test fun aBusyButtonStillConsumesTheKeySoNothingStartsTwice() {
        val calls = mutableListOf<String>()
        val registry = DesktopRefreshTargets()
        registry.register(target(true, calls, "busy", answer = false))
        assertTrue(registry.refreshTopmost("F5"))
        assertEquals(listOf("busy:F5"), calls)
    }

    @Test fun onlyAPlainF5OrCtrlRIsTheRefreshChordAndOnlyListPagesPromiseIt() {
        fun key(name: String, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, down: Boolean = true) =
            DesktopShellKeyData(name, ctrl = ctrl, alt = alt, shift = shift, keyDown = down)
        assertTrue(desktopRefreshChord(key("F5")))
        assertTrue(desktopRefreshChord(key("R", ctrl = true)))
        assertFalse(desktopRefreshChord(key("R")))
        assertFalse(desktopRefreshChord(key("F5", ctrl = true)))
        assertFalse(desktopRefreshChord(key("F5", shift = true)))
        assertFalse(desktopRefreshChord(key("F5", alt = true)))
        assertFalse(desktopRefreshChord(key("F5", down = false)))
        assertTrue(desktopRefreshShortcutPage(DesktopShellPage.HOME))
        assertTrue(desktopRefreshShortcutPage(DesktopShellPage.DYNAMIC))
        assertTrue(desktopRefreshShortcutPage(DesktopShellPage.OTHER))
        assertFalse(desktopRefreshShortcutPage(DesktopShellPage.PLAYER))
        assertFalse(desktopRefreshShortcutPage(DesktopShellPage.SEARCH))
        assertFalse(desktopRefreshShortcutPage(null))
    }

    private class HostRig(page: DesktopShellPage?) {
        val calls = mutableListOf<String>()
        var editor = false
        var shownPage = page
        val targets = DesktopRefreshTargets()
        val host = DesktopShellShortcutHost({ true }, { editor }, { 0L }, targets)
        init {
            host.bindRoot(DesktopShellRootCommands(owner = this, page = { shownPage }, canGoBack = { true }, openSearch = { true },
                refresh = { shown -> calls += "channel:$shown"; true }, goTo = { true }, openHelp = { true }))
        }
        fun down(key: String, ctrl: Boolean = false) = host.handleKeyOrRefreshFallback(DesktopShellKeyData(key, ctrl = ctrl)) { }
        fun up(key: String) = host.handleKeyOrRefreshFallback(DesktopShellKeyData(key, keyDown = false)) { }
    }

    @Test fun f5AndCtrlRRefreshTheButtonOfAnyListPageNotOnlyHomeAndDynamic() {
        for ((key, ctrl) in listOf("F5" to false, "R" to true)) {
            val rig = HostRig(DesktopShellPage.OTHER)
            rig.targets.register(target(true, rig.calls, "button"))
            assertTrue(rig.down(key, ctrl), key)
            assertEquals(listOf("button:$DESKTOP_REFRESH_SHORTCUT_SOURCE"), rig.calls)
            // A held key asks the button again; the button's own busy gate (DesktopRefreshButtonState) keeps it to one refresh.
            assertTrue(rig.down(key, ctrl))
            assertEquals(2, rig.calls.size)
        }
    }

    @Test fun theKeyStaysWithTheEditorThePlayerAndTheSearchPage() {
        val editor = HostRig(DesktopShellPage.OTHER).also { it.editor = true; it.targets.register(target(true, it.calls, "b")) }
        assertFalse(editor.down("F5")); assertTrue(editor.calls.isEmpty())
        for (page in listOf(DesktopShellPage.PLAYER, DesktopShellPage.SEARCH, null)) {
            val rig = HostRig(page).also { it.targets.register(target(true, it.calls, "b")) }
            assertFalse(rig.down("F5"), "$page")
            assertTrue(rig.calls.isEmpty(), "$page")
        }
        val none = HostRig(DesktopShellPage.OTHER)
        assertFalse(none.down("F5"))
        assertFalse(none.down("R", ctrl = false))
        assertTrue(none.calls.isEmpty())
    }

    // ---- the real box in the CPU scene --------------------------------------------------------------------------------------

    @Test fun materialBoxHasACornerButtonThatRefreshesOnce() = runBlocking<Unit> { cornerButtonRefreshesOnce(AppUiStyle.MATERIAL3) }
    @Test fun miuixBoxHasACornerButtonThatRefreshesOnce() = runBlocking<Unit> { cornerButtonRefreshesOnce(AppUiStyle.MIUIX) }

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

    @Test fun aBoxThatDisabledRefreshHasNoButton() = runBlocking<Unit> {
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
    @Test fun aMouseDragDownFromTheTopOfTheListDoesNotRefresh() = runBlocking<Unit> {
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

    /** The back-to-top button of the list pages, mounted the way CategoryScreen mounts it, beside a refresh button. */
    @Test fun theBackToTopButtonRisesAboveTheRefreshButtonInTheSameCorner() = runBlocking<Unit> {
        val bindings = DesktopBackToTopBindings(flowOf(true), true, flowOf(0f to 0f), 0f to 0f, { _, _ -> }, { _, _ -> },
            com.bilipai.desktop.ui.DesktopFavoriteViewport(520, 420))
        val scene = ImageComposeScene(520, 420, Density(1f)) {
            val actualWindow = LocalWindowInfo.current
            val focused = androidx.compose.runtime.remember(actualWindow) { object : WindowInfo by actualWindow {
                override val isWindowFocused = true
            } }
            CompositionLocalProvider(LocalWindowInfo provides focused, LocalDesktopBackToTopBindings provides bindings) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        AppLiquidGlassBackToTopButton(visible = true, onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
                        DesktopRefreshButton(refreshing = false, onRefresh = {})
                    }
                }
            }
        }
        try {
            var nanos = 0L
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun find(name: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
            }
            // Both nodes exist from the first frame, but the lift is not final then: the refresh button reports itself in an effect,
            // so the back-to-top button (composed before it) rises one frame later and then fades/scales in. Measure the settled
            // layout: the refresh button is present and both bounds have held still for several frames.
            val trace = mutableListOf<String>()
            var stable = 0
            var last: Pair<Rect, Rect>? = null
            withTimeout(6_000) {
                while (stable < SETTLED_FRAMES) {
                    nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close(); delay(12)
                    val refreshNode = find("刷新")
                    val topNode = find("回到顶部")
                    if (refreshNode == null || topNode == null) { stable = 0; last = null; continue }
                    val now = topNode.boundsInRoot to refreshNode.boundsInRoot
                    trace += "top=${now.first} refresh=${now.second} lifted=${DesktopRefreshButtonPresence.present}"
                    stable = if (DesktopRefreshButtonPresence.present && now == last) stable + 1 else 0
                    last = now
                }
            }
            val (top, refresh) = assertNotNull(last)
            assertTrue(top.bottom <= refresh.top, "back to top $top must sit above refresh $refresh; frames: $trace")
            assertTrue(top.right > Scene.WIDTH - 60f, "same corner column")
        } finally { scene.close() }
    }

    private companion object { const val SETTLED_FRAMES = 5 }

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
