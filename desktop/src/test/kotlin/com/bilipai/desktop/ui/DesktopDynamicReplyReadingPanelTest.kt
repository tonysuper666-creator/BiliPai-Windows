@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.DynamicSubReplyPreviewHost
import com.android.purebilibili.feature.video.viewmodel.SubReplyUiState
import com.android.purebilibili.feature.video.viewmodel.SubReplySortMode
import com.bilipai.desktop.data.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import com.bilipai.desktop.ui.modal.*
import java.awt.GraphicsEnvironment
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real upstream PreviewHost -> SubReplySheet -> original SubReplyDetailContent, with no account/network. */
class DesktopDynamicReplyReadingPanelTest {
    @TempDir lateinit var directory: Path
    private val emotes = object : DesktopDynamicEmotes {
        override fun snapshot() = emptyMap<String, String>()
        override fun currentSessionKey(): Any = "isolated-reply-test"
        override suspend fun ensureLoaded() = snapshot()
    }
    private fun platform(): DesktopCommentPlatform {
        val context = DesktopPluginContext(DesktopPluginStore(directory))
        return Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "IsolatedReplyPlatform"
                "isOwned" -> true
                "getContext" -> context
                "getEmotes" -> emotes
                "getSubReplyLoadedCountEnabled" -> flowOf(false)
                "getCollapsedReplyPreviewLimit" -> 3
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as DesktopCommentPlatform
    }
    private fun state() = SubReplyUiState(visible = true, rootReply = ReplyItem(rpid = 100),
        items = (1..60).map { ReplyItem(rpid = 100L + it, content = ReplyContent(message = "Reply $it")) }.toPersistentList(),
        totalCount = 60, isEnd = true)

    @Composable private fun Host(state: SubReplyUiState, onDismiss: () -> Unit,
        reading: Boolean = true, onSort: (SubReplySortMode) -> Unit = {}) {
        val bindings = remember { platform() }
        CompositionLocalProvider(LocalDesktopCommentBindings provides bindings) {
            DynamicSubReplyPreviewHost(state, onDismiss, onLoadMore = { error("End of list must not reload") },
                onRefresh = {}, onSortModeChange = onSort, onUserClick = {},
                sheetContainer = if (reading) ({ content -> DesktopDynamicReplyReadingPanel(onDismiss, content) }) else null)
        }
    }
    private inner class Scene(reduced: Boolean = true, content: @Composable () -> Unit) : AutoCloseable {
        private var time = 0L
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val transport = repository.httpClient.newBuilder().addInterceptor { chain ->
            check(chain.request().url.encodedPath == "/x/emote/user/panel/web") { "Unexpected request: ${chain.request().url}" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":{"packages":[]}}""".toResponseBody()).build()
        }.build()
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        init { DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport) }
        private val scene = DesktopUpstreamCalls.withOwner(owner) { ImageComposeScene(800, 600, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) + DesktopUpstreamScope(owner) +
            CoroutineExceptionHandler { _, error -> errors += error }) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopKitReducedMotion provides reduced) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        }
        init { check(GraphicsEnvironment.isHeadless()) }
        fun advance(frames: Int) { repeat(frames) {
            scheduler.runCurrent()
            time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }
            scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Reply composition failed", it) }
        } }
        fun settle() = advance(8)
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun scrollRange(node: SemanticsNode) = flatten(node).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .config[SemanticsProperties.VerticalScrollAxisRange]
        fun ownerCount() = scene.semanticsOwners.size
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun tagged(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        fun key(key: Key, shift: Boolean = false, settle: Boolean = true) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
            if (settle) settle()
        }
        fun click(tag: String) {
            val p = tagged(tag).boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons()); settle()
        }
        fun scroll(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 8f)); settle()
        }
        override fun close() {
            scene.close()
            repository.logout()
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
    }

    @Test fun originalReplyEntersFocusTrapsTabAndRestoresOpenerWithoutNestingSheets() {
        var reply by mutableStateOf(state().copy(visible = false))
        var mounts = 0
        Scene {
            DisposableEffect(Unit) { mounts++; onDispose {} }
            Button({ reply = reply.copy(visible = true) }, Modifier.testTag("opener")) { Text("Open replies") }
            Host(reply, { reply = reply.copy(visible = false) })
        }.use { s ->
            s.settle(); s.click("opener")
            assertEquals(1, s.nodes().count { it.config.getOrNull(SemanticsProperties.TestTag) == "desktop-modal-surface" })
            assertEquals(2, s.ownerCount(), "One page and one reply popup, with no nested legacy sheet")
            val bounds = s.tagged("desktop-modal-surface").boundsInWindow
            assertEquals(460f, bounds.left); assertEquals(800f, bounds.right); assertEquals(600f, bounds.height)
            assertEquals("subreply_detail_close", s.focusedTag())
            repeat(12) {
                s.key(Key.Tab)
                assertTrue(s.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true && bounds.contains(it.boundsInWindow.center) })
            }
            s.key(Key.Tab, shift = true)
            s.key(Key.Escape)
            assertFalse(reply.visible); assertEquals("opener", s.focusedTag()); assertEquals(1, mounts)
            s.click("opener"); assertTrue(reply.visible)
            s.click("subreply_detail_close")
            assertFalse(reply.visible); assertEquals("opener", s.focusedTag()); assertEquals(1, mounts)
        }
    }

    @Test fun escapeClosesOnlyTheReplyOverItsActualParentModal() {
        var parent by mutableStateOf(true)
        var reply by mutableStateOf(state().copy(visible = false))
        Scene {
            if (parent) DesktopPurposeModal(DesktopModalPurpose.CONFIRM, { parent = false }) {
                Button({ reply = reply.copy(visible = true) }, Modifier.testTag("opener")) { Text("Replies") }
                Host(reply, { reply = reply.copy(visible = false) })
            }
        }.use { s ->
            s.settle(); s.click("opener"); assertTrue(reply.visible)
            s.key(Key.Escape); assertFalse(reply.visible); assertTrue(parent)
            assertEquals("opener", s.focusedTag())
            s.key(Key.Escape); assertFalse(parent)
        }
    }

    @Test fun originalLazyListScrollsKeepsItsPositionOnUpdatesAndNeverMovesTheBackground() {
        var backgroundScroll = 0
        var reply by mutableStateOf(state())
        var sort: SubReplySortMode? = null
        Scene {
            val background = rememberScrollState()
            SideEffect { backgroundScroll = background.value }
            Column(Modifier.fillMaxSize().verticalScroll(background)) { repeat(80) { Text("Background $it") } }
            Host(reply, {}, onSort = { sort = it })
        }.use { s ->
            s.settle()
            val list = s.tagged("subreply_detail_reply_list")
            val range = s.scrollRange(list)
            assertEquals(0f, range.value()); assertTrue(range.maxValue() > 0f)
            s.click("subreply_detail_sort"); assertNotNull(sort)
            s.scroll(list.boundsInWindow.center); assertTrue(range.value() > 0f)
            val before = range.value()
            reply = reply.copy(totalCount = 61)
            s.settle(); assertEquals(before, range.value())
            assertEquals(0f, s.tagged("desktop-modal-body").config[SemanticsProperties.VerticalScrollAxisRange].maxValue())
            s.scroll(Offset(20f, 250f)); assertEquals(0, backgroundScroll)
        }
    }

    @Test fun omittedContainerStillUsesTheOriginalSheetWithOriginalContent() {
        Scene { Host(state(), {}, reading = false) }.use { s ->
            s.settle()
            assertTrue(s.tagged("subreply_detail_header").boundsInWindow.height > 0)
            assertTrue(s.tagged("subreply_detail_reply_list").boundsInWindow.height > 0)
            assertFalse(s.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "desktop-modal-surface" })
        }
    }
}
