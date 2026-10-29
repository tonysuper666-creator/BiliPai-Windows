@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox
import java.awt.GraphicsEnvironment
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.io.File
import java.io.IOException
import javax.swing.JPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun answer(request: Request, code: Int, type: String, body: String) = Response.Builder().request(request)
    .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody(type.toMediaType())).build()

/**
 * TON-1026, every page that mounts AdaptivePullToRefreshBox: the list goes back to the top when the button refreshes it (the wheel
 * the desktop scroller sends is replayed in the CPU scene), a refresh whose requests fail says so once and leaves the button usable,
 * and the narrow window keeps the button in the corner. The page is a generic list on isolated data; the failures are real OkHttp
 * calls through the app's failure-watch interceptor with an in-memory network.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("coil3.SingletonImageLoader")
class DesktopRefreshPagesUiTest {
    // ---- the pure parts -----------------------------------------------------------------------------------------------------

    @Test fun theWheelScrollerAimsAtTheMiddleOfTheBoxOnTheLayerThatTakesTheWheel() {
        val root = JPanel()
        val layer = JPanel().also { it.setSize(800, 600) }
        val events = mutableListOf<MouseWheelEvent>()
        layer.addMouseWheelListener(MouseWheelListener { events += it })
        root.add(layer)
        val scroller = DesktopAwtWheelScroller({ root }, { 2f }, post = { it.run() })
        // Not showing yet (a headless panel never is): there is no layer to scroll.
        assertFalse(scroller.scrollToTop(Rect(0f, 0f, 400f, 300f)))
        assertTrue(events.isEmpty())
        val showing = ShowingPanel().also { it.setSize(800, 600); it.addMouseWheelListener(MouseWheelListener { events += it }) }
        val host = java.awt.Container().also { it.add(showing) }
        val real = DesktopAwtWheelScroller({ host }, { 2f }, post = { it.run() })
        assertTrue(real.scrollToTop(Rect(100f, 200f, 500f, 800f)))
        assertEquals(2, events.size)
        val event = events.first()
        assertEquals(150, event.x)
        assertEquals(250, event.y)
        assertTrue(event.wheelRotation <= -1_000, "upward and far longer than any feed")
        assertEquals(0, event.modifiersEx, "no Ctrl: the wheel must not be read as zoom")
        assertFalse(real.scrollToTop(Rect.Zero))
        assertFalse(DesktopAwtWheelScroller({ null }, { 1f }, post = { it.run() }).scrollToTop(Rect(0f, 0f, 10f, 10f)))
    }

    private class ShowingPanel : JPanel() { override fun isShowing() = true }

    @Test fun onlyFailedRequestsAreCountedAndOnlyWhileAButtonRefreshIsWatched() {
        fun client(vararg replies: (Request) -> Response) = OkHttpClient.Builder()
            .addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { chain -> replies[minOf(chain.request().url.pathSegments.first().toInt(), replies.size - 1)](chain.request()) }
            .build()
        val c = client(
            { answer(it, 200, "application/json", """{"code":0,"data":{"code":-1}}""") },
            { answer(it, 200, "application/json", """{"code":-352,"message":"risk"}""") },
            { answer(it, 503, "text/plain", "busy") },
            { answer(it, 200, "image/png", "code:-1") },
            { throw IOException("connection reset") },
            { throw IOException("Canceled") },
        )
        fun get(n: Int) = try { c.newCall(Request.Builder().url("http://fixture.invalid/$n").build()).execute().close() } catch (_: IOException) { }
        (0..5).forEach(::get)
        assertEquals(0, DesktopRefreshFailureWatch.end(), "nothing is counted while nobody watches")
        DesktopRefreshFailureWatch.begin()
        (0..5).forEach(::get)
        assertEquals(3, DesktopRefreshFailureWatch.end(), "business code, HTTP status and a lost connection; not success, image, or a cancelled call")
        assertEquals(0, DesktopRefreshFailureWatch.end())
        assertEquals("刷新未完全成功：有 1 个请求失败，已保留原内容", desktopRefreshWatchedFailureMessage(1))
        assertEquals("刷新未完全成功：有 3 个请求失败，已保留原内容", desktopRefreshWatchedFailureMessage(3))
    }

    // ---- the page in the CPU scene ------------------------------------------------------------------------------------------

    private class Recorder : DesktopToaster {
        val messages = mutableListOf<String>()
        override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) { messages += message }
    }

    /** A generic list page: rows that scroll, a refresh that makes requests through the watched client, an in-memory network. */
    private class Page(width: Int = 520, height: Int = 420) : AutoCloseable {
        val toaster = Recorder()
        var refreshing by mutableStateOf(false)
        var reply: (Request) -> Response = { answer(it, 200, "application/json", """{"code":0}""") }
        var requests = 0
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        private val client = OkHttpClient.Builder().addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { chain -> reply(chain.request()) }.build()
        private var nanos = 0L
        private var eventMillis = 0L
        val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(width, height, Density(1f)) {
                val actualWindow = LocalWindowInfo.current
                val focused = androidx.compose.runtime.remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused = true
                } }
                val scope = rememberCoroutineScope()
                list = rememberLazyListState()
                CompositionLocalProvider(
                    LocalWindowInfo provides focused,
                    LocalDesktopToaster provides toaster,
                ) {
                    MaterialTheme {
                        AdaptivePullToRefreshBox(isRefreshing = refreshing, modifier = Modifier.fillMaxSize(), onRefresh = {
                            scope.desktopRefreshLaunch(kotlinx.coroutines.Dispatchers.IO) {
                                refreshing = true
                                try {
                                    requests++
                                    client.newCall(Request.Builder().url("http://fixture.invalid/feed").build()).execute().close()
                                } catch (_: IOException) { }
                                delay(120)
                                refreshing = false
                            }
                        }) {
                            LazyColumn(Modifier.fillMaxSize(), state = list) { items(120) { Text("row $it", Modifier.fillMaxWidth().height(48.dp)) } }
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
        suspend fun pumpFor(ms: Long) { val end = System.nanoTime() + ms * 1_000_000; do { render(); delay(12) } while (System.nanoTime() < end) }
        suspend fun await(what: String, condition: () -> Boolean) {
            try { withTimeout(8_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) } }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
        }
        suspend fun awaitButton(name: String): SemanticsNode { await("button $name") { button(name) != null }; return assertNotNull(button(name)) }
        private fun time() = maxOf(System.nanoTime() / 1_000_000, eventMillis + 1).also { eventMillis = it }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time())
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time(), buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time(), buttons = PointerButtons())
        }
        fun image(file: File) { render(); file.parentFile.mkdirs(); file.writeBytes(scene.render(nanos + 1).encodeToData()!!.bytes) }
        override fun close() = scene.close()
    }

    @Test fun aScrolledListGoesBackToTheTopWhenTheButtonRefreshesIt() = runBlocking<Unit> {
        val page = Page()
        try {
            val button = page.awaitButton("刷新")
            page.list.scrollToItem(60)
            page.await("scrolled down") { page.list.firstVisibleItemIndex >= 50 }
            // The wheel the scroller sends reaches the list in this scene.
            page.scene.sendPointerEvent(PointerEventType.Scroll, button.boundsInRoot.center - Offset(200f, 200f), scrollDelta = Offset(0f, -100_000f))
            page.await("a huge upward wheel ends at the top") { page.list.firstVisibleItemIndex == 0 }
            page.list.scrollToItem(60)
            page.await("scrolled down again") { page.list.firstVisibleItemIndex >= 50 }
            page.click(button.boundsInRoot.center)
            page.await("list back at the top") { page.list.firstVisibleItemIndex == 0 && page.list.firstVisibleItemScrollOffset == 0 }
            page.await("refresh ran") { page.requests == 1 }
        } finally { page.close() }
    }

    @Test fun aRefreshWhoseRequestFailsTellsOnceKeepsTheRowsAndTheButtonWorksAgain() = runBlocking<Unit> {
        for ((label, failure) in listOf<Pair<String, (Request) -> Response>>(
            "HTTP 500" to { answer(it, 500, "text/plain", "down") },
            "business code" to { answer(it, 200, "application/json", """{"code":-352,"message":"risk"}""") },
            "lost connection" to { throw IOException("connection reset") },
        )) {
            val page = Page()
            try {
                var button = page.awaitButton("刷新")
                page.reply = failure
                page.click(button.boundsInRoot.center)
                page.await("$label: toast") { page.toaster.messages.isNotEmpty() }
                assertEquals(listOf("刷新未完全成功：有 1 个请求失败，已保留原内容"), page.toaster.messages, label)
                button = page.awaitButton("刷新")
                assertTrue(page.list.layoutInfo.totalItemsCount > 0, "$label: the rows stay")
                page.reply = { answer(it, 200, "application/json", """{"code":0}""") }
                page.click(button.boundsInRoot.center)
                page.await("$label: second refresh") { page.requests == 2 }
                page.awaitButton("刷新")
                page.pumpFor(300)
                assertEquals(1, page.toaster.messages.size, "$label: a good refresh says nothing")
            } finally { page.close() }
        }
    }

    @Test fun theNarrowWindowKeepsTheButtonInTheCornerAndLeavesAScreenshot() = runBlocking<Unit> {
        val page = Page(width = 360, height = 640)
        try {
            val button = page.awaitButton("刷新").boundsInRoot
            assertTrue(button.width >= 40f && button.height >= 40f)
            assertTrue(button.right > 360f - 40f && button.bottom > 640f - 40f, "bottom right of a 360 x 640 window: $button")
            page.pumpFor(200)
            page.image(File("build/ton1026/narrow-360x640.png"))
        } finally { page.close() }
    }
}
