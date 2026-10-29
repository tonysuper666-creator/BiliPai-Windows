@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox
import com.android.purebilibili.core.ui.components.AppLazyColumn
import com.android.purebilibili.core.ui.components.AppLazyVerticalGrid
import java.io.IOException
import java.io.File
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import kotlin.test.*

/** Actual shared list components and the generated Category/Inbox/Trending content inset shapes. No AWT or injected scroller. */
class DesktopRefreshContentLayoutsTest {
    private enum class Page { CATEGORY, INBOX, TRENDING }
    @Test fun categoryLastMenuStaysClearAndRefreshReturnsItsGridToTop() = scenario(Page.CATEGORY)
    @Test fun inboxLastMenuStaysClearAndRefreshReturnsItsListToTop() = scenario(Page.INBOX)
    @Test fun trendingLastMenuStaysClearAndRefreshReturnsItsListToTop() = scenario(Page.TRENDING)

    private fun scenario(page: Page) = runBlocking {
        var refreshing by mutableStateOf(false)
        lateinit var grid: LazyGridState
        lateinit var list: LazyListState
        var lastMenu = Rect.Zero
        var requests = 0
        var broken = false
        val messages = mutableListOf<String>()
        val toaster = object : DesktopToaster {
            override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) { messages += message }
        }
        val client = OkHttpClient.Builder().addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { chain ->
                val body = if (broken) lateDisconnect() else """{"data":{"code":-1},"code":0}""".toResponseBody("application/json".toMediaType())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
            }.build()
        val scene = ImageComposeScene(520, 420, Density(1f)) {
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            val scope = rememberCoroutineScope()
            grid = rememberLazyGridState()
            list = rememberLazyListState()
            CompositionLocalProvider(LocalWindowInfo provides focused, LocalDesktopToaster provides toaster) {
                MaterialTheme {
                    AdaptivePullToRefreshBox(refreshing, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), onRefresh = {
                        scope.desktopRefreshLaunch(Dispatchers.IO) {
                            refreshing = true
                            try { requests++; client.newCall(Request.Builder().url("https://fixture.invalid/feed").build()).execute().use { it.body.string() } }
                            catch (_: IOException) { }
                            delay(100)
                            refreshing = false
                        }
                    }) {
                        val row: @Composable (Int) -> Unit = { index ->
                            Box(Modifier.fillMaxWidth().height(80.dp)) {
                                Text("row $index")
                                if (index == 119) Box(Modifier.align(Alignment.BottomEnd).size(44.dp)
                                    .onGloballyPositioned { lastMenu = it.boundsInRoot() }) { Text("menu") }
                            }
                        }
                        when (page) {
                            Page.CATEGORY -> AppLazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { items(120) { row(it) } }
                            Page.INBOX -> AppLazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 56.dp + 8.dp, bottom = 16.dp + 8.dp)) { items(120) { row(it) } }
                            Page.TRENDING -> AppLazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) { items(120) { row(it) } }
                        }
                    }
                }
            }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun button(name: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true && it.config.getOrNull(SemanticsProperties.Role) != null }
        suspend fun await(condition: () -> Boolean) = withTimeout(6000) {
            while (true) { scene.render(System.nanoTime()).close(); if (condition()) break; delay(12) }
        }
        var tick = 0L
        fun click() {
            val point = assertNotNull(button("刷新")).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = ++tick)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = ++tick, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = ++tick, buttons = PointerButtons())
        }
        fun atTop() = if (page == Page.CATEGORY) grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0
            else list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
        try {
            await { button("刷新") != null }
            if (page == Page.CATEGORY) grid.scrollToItem(119) else list.scrollToItem(119)
            await { lastMenu.width > 0f }
            val refreshBounds = assertNotNull(button("刷新")).boundsInRoot
            assertFalse(lastMenu.overlaps(refreshBounds), "$page last menu $lastMenu")
            println("TON1026 $page last menu=$lastMenu refresh=$refreshBounds overlap=false")
            scene.render(System.nanoTime()).use { rendered ->
                rendered.encodeToData()!!.use { data ->
                    val screenshot = File("build/ton1026/${page.name.lowercase()}-end.png")
                    screenshot.parentFile.mkdirs()
                    screenshot.writeBytes(data.bytes)
                }
            }
            broken = true
            click()
            await { requests == 1 && atTop() && messages.size == 1 && button("刷新") != null }
            assertTrue(messages.single().contains("刷新未完全成功"))
            broken = false
            click()
            await { requests == 2 && !refreshing && button("刷新") != null }
            assertEquals(1, messages.size, "$page recovery succeeds without another failure toast")
            assertTrue(atTop(), "$page exact registered state, no synthetic wheel")
        } finally { scene.close() }
    }

    private fun lateDisconnect(): ResponseBody = object : ResponseBody() {
        private val prefix = Buffer().writeUtf8("{\"code\":0,\"data\":\"" + "x".repeat(3000))
        private val stream = object : Source {
            override fun timeout() = Timeout.NONE
            override fun close() { }
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (prefix.size > 0) return prefix.read(sink, minOf(byteCount, prefix.size))
                throw IOException("connection reset during body")
            }
        }.buffer()
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = -1L
        override fun source() = stream
    }
}
