@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.live.LiveDanmakuItem
import java.awt.Canvas
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.*

/** The live room host (DesktopLiveRoomStage, called by LiveBrowserScreen) with the production chat list,
 * chat editor and copy writer in its chat slot. CPU scene, no account, network or native player. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("coil3.SingletonImageLoader")
class DesktopLiveRoomLayoutUiTest {
    @Test fun wideEntryDocksChatAndNarrowEntryCollapsesItWithoutFlippingOnResize(): Unit = runBlocking {
        Scene(coroutineContext, width = 1440).use { s ->
            s.await { s.tagged(DESKTOP_LIVE_CHAT_TAG) != null }
            // Spec 6: wide window docks the chat at 22% clamped to 340..480 beside the stage.
            assertEquals(340f, s.bounds(DESKTOP_LIVE_CHAT_TAG).width)
            assertEquals(1440f - 340f - 12f, s.bounds(DESKTOP_LIVE_STAGE_TAG).width)
            assertEquals(s.bounds(DESKTOP_LIVE_STAGE_TAG).top, s.bounds(DESKTOP_LIVE_CHAT_TAG).top)
            assertNotNull(s.button(DESKTOP_LIVE_CHAT_HIDE))
            // A window drag never flips the entry decision; it only re-fits the open chat.
            s.width = 1000; s.settle()
            assertEquals(340f, s.bounds(DESKTOP_LIVE_CHAT_TAG).width)
            assertEquals(1000f - 340f - 12f, s.bounds(DESKTOP_LIVE_STAGE_TAG).width)
            s.width = 700; s.settle()
            val stacked = s.bounds(DESKTOP_LIVE_CHAT_TAG)
            assertEquals(700f, stacked.width, "An open chat that cannot dock goes below the stage")
            assertTrue(stacked.top > s.bounds(DESKTOP_LIVE_STAGE_TAG).bottom)
            s.click(s.button(DESKTOP_LIVE_CHAT_HIDE)!!.boundsInRoot.center)
            s.await { s.tagged(DESKTOP_LIVE_CHAT_TAG) == null }
            assertEquals(700f, s.bounds(DESKTOP_LIVE_STAGE_TAG).width)

            // A room entered in a narrow window starts with the chat collapsed behind the button.
            s.width = 1000; s.room = 2; s.settle()
            assertNull(s.tagged(DESKTOP_LIVE_CHAT_TAG))
            assertEquals(1000f, s.bounds(DESKTOP_LIVE_STAGE_TAG).width)
            s.width = 1440; s.settle()
            assertNull(s.tagged(DESKTOP_LIVE_CHAT_TAG), "Growing the window does not open a collapsed chat")
            s.click(assertNotNull(s.button(DESKTOP_LIVE_CHAT_SHOW)).boundsInRoot.center)
            s.await { s.tagged(DESKTOP_LIVE_CHAT_TAG) != null }
            assertEquals(340f, s.bounds(DESKTOP_LIVE_CHAT_TAG).width)
            assertEquals(1440f - 340f - 12f, s.bounds(DESKTOP_LIVE_STAGE_TAG).width)
        }
    }

    @Test fun chatListScrollsOnItsOwnAndNeverMovesTheStage(): Unit = runBlocking {
        val many = (1..80).map { LiveDanmakuItem("message $it", uid = it.toLong(), uname = "U$it", idStr = "id-$it") }
        Scene(coroutineContext, width = 1440, messages = many).use { s ->
            // The list first follows the newest message on its own.
            s.await { s.list.listState.firstVisibleItemIndex > 40 && !s.list.listState.isScrollInProgress }
            s.settle()
            val stage = s.bounds(DESKTOP_LIVE_STAGE_TAG)
            val before = s.list.listState.firstVisibleItemIndex
            val chat = s.bounds(DESKTOP_LIVE_CHAT_TAG)
            repeat(6) { s.wheel(Offset(chat.center.x, chat.top + 80f), -12f) }
            s.settle()
            assertTrue(s.list.listState.firstVisibleItemIndex < before,
                "The chat list itself scrolls (${s.list.listState.firstVisibleItemIndex} vs $before)")
            assertEquals(stage, s.bounds(DESKTOP_LIVE_STAGE_TAG))
            repeat(3) { s.wheel(stage.center, -12f) }
            s.settle()
            assertEquals(stage, s.bounds(DESKTOP_LIVE_STAGE_TAG), "The stage has nothing to scroll")
        }
    }

    @Test fun spaceInTheChatEditorNeverTogglesPlaybackButOutsideTheEditorItDoes(): Unit = runBlocking {
        Scene(coroutineContext, width = 1440).use { s ->
            s.await { s.tagged(DESKTOP_LIVE_CHAT_TAG) != null }
            s.settle()
            // Entering the room puts focus on the stage: Space controls playback right away.
            assertTrue(s.space())
            assertEquals(1, s.toggles)
            s.click(s.editor().boundsInRoot.center)
            s.await { s.focus?.focused == true }
            repeat(3) { s.space(typed = true) }
            assertEquals(1, s.toggles, "Space typed into the chat editor must not pause the live stream")
            s.space(ctrl = true); assertEquals(1, s.toggles)
            // Pointing at the stage leaves the editor; Space is the playback shortcut again.
            s.click(s.bounds(DESKTOP_LIVE_STAGE_TAG).center)
            s.await { s.focus?.focused == false }
            assertTrue(s.space())
            assertEquals(2, s.toggles)
            assertFalse(s.space(ctrl = true)); assertEquals(2, s.toggles, "Modified Space is not the shortcut")
        }
    }

    @Test fun rightClickCopiesThePointedRowNotTheNewestOne(): Unit = runBlocking {
        val rows = listOf(
            LiveDanmakuItem("first", uid = 1, uname = "Ann", idStr = "a"),
            LiveDanmakuItem("second", uid = 2, uname = "Bob", idStr = "b"),
            LiveDanmakuItem("third", uid = 3, uname = "Cid", idStr = "c"))
        Scene(coroutineContext, width = 1440, messages = rows).use { s ->
            s.await { s.text("second") != null }
            s.secondaryClick(assertNotNull(s.text("second")).boundsInRoot.center)
            s.await { s.button("复制弹幕信息") != null }
            s.click(assertNotNull(s.button("复制弹幕信息")).boundsInWindow.center)
            s.await { s.clipboard.value != null }
            assertEquals("uid=2, uname=Bob, text=second", s.clipboard.value?.text)
        }
    }

    private class Clipboard : ClipboardManager {
        var value: AnnotatedString? = null
        override fun getText() = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }

    private class Scene(sceneContext: CoroutineContext, width: Int,
        messages: List<LiveDanmakuItem> = listOf(LiveDanmakuItem("hello", uid = 7, uname = "Fixture", idStr = "x"))) : AutoCloseable {
        var width by mutableIntStateOf(width)
        var room by mutableIntStateOf(1)
        var toggles = 0
        var focus: DesktopLiveChatInputFocus? = null
        val clipboard = Clipboard()
        val list = DesktopLiveChatMessageState()
        private var draft by mutableStateOf("")
        private val key = Any()
        private var nanos = 0L
        private var eventMillis = 0L
        private var closed = false
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(1600, 860, Density(1f), coroutineContext = sceneContext) {
                val actualWindow = LocalWindowInfo.current
                val focused = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused = true
                } }
                CompositionLocalProvider(LocalWindowInfo provides focused, LocalClipboardManager provides clipboard,
                    LocalAppThemeConfig provides AppThemeConfig(nativeMiuixPopupsEnabled = false)) {
                    MaterialTheme {
                        Box(Modifier.size(this@Scene.width.dp, 820.dp)) {
                            DesktopLiveRoomStage(room, onTogglePause = { toggles++ }, Modifier.fillMaxSize(),
                                chat = { chatModifier, inputFocus ->
                                    focus = inputFocus
                                    val clipboardManager = LocalClipboardManager.current
                                    Column(chatModifier) {
                                        DesktopLiveChatMessages(key, messages, true, {}, Modifier.weight(1f).fillMaxWidth(),
                                            state = list, captureCurrent = { { true } },
                                            onCopyInfo = { item, current -> if (current()) clipboardManager.copyDesktopLiveDanmaku(item) })
                                        DesktopLiveChatInput(draft, { draft = it }, enabled = true, maxLength = 40,
                                            onSend = {}, inputFocus = inputFocus)
                                    }
                                }) { stageModifier -> Box(stageModifier.background(Color.Black)) { Text("stage") } }
                        }
                    }
                }
            }
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun tagged(tag: String) = all().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun bounds(tag: String): Rect = assertNotNull(tagged(tag), "missing $tag").boundsInRoot
        fun text(label: String) = all().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        fun editor() = all().first { it.config.contains(SemanticsActions.SetText) }
        fun button(label: String) = all().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
            }
        }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun settle() { repeat(8) { render(); delay(12) } }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(5_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) }
        }
        private fun time() = maxOf(System.nanoTime() / 1_000_000, eventMillis + 1).also { eventMillis = it }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(), buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons())
        }
        fun secondaryClick(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(), buttons = PointerButtons(isSecondaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons())
        }
        fun wheel(point: Offset, delta: Float) {
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time())
            scene.sendPointerEvent(PointerEventType.Scroll, point, timeMillis = time(), scrollDelta = Offset(0f, delta))
        }
        /** KeyDown (+ the typed character an editor takes) + KeyUp; returns whether KeyDown was consumed. */
        fun space(ctrl: Boolean = false, typed: Boolean = false): Boolean {
            val down = scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            if (typed) scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.Unknown, ' '.code,
                nativeEvent = AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, ' ')))
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp, isCtrlPressed = ctrl))
            return down
        }
        override fun close() { if (!closed) { closed = true; scene.close() } }
    }
}
