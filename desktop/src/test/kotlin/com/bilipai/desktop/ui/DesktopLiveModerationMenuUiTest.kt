@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.LivePlayUrlResponse
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.MpvPlayer
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.*

/** Real production Messages + original full menu in the existing CPU scene,
 * explicitly selecting its existing in-scene M3 popup fallback. The default
 * Miuix Dialog OS-window host is outside this headless coverage.
 * Callbacks observe item/source identity, not OS clipboard/navigation or API execution.
 * Actual PageMemory/Mpv source-CAS replaces sources without a native handle or window. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("coil3.SingletonImageLoader")
class DesktopLiveModerationMenuUiTest {
    @Test fun originalMenuPointerActionsKeepTheOriginalItemAndCapturedCurrentSource(): Unit = runBlocking {
        val owner = SourceOwner()
        val item = LiveDanmakuItem("current ordinary message", uid = 77, uname = "Fixture", idStr = "ordinary-menu")
        val f = Scene(owner, item)
        try {
            val labels = listOf("复制弹幕信息" to "copy", "去TA的个人空间" to "space", "@TA" to "reply",
                "屏蔽发送者" to "shield", "举报选中弹幕" to "report")
            labels.forEachIndexed { index, (label, kind) ->
                if (index == 0) {
                    f.await { f.node(item.text) != null }
                    f.secondaryClick(assertNotNull(f.node(item.text)).boundsInRoot.center)
                } else {
                    f.await { f.node("更多") != null }
                    f.click(assertNotNull(f.node("更多")).boundsInRoot.center)
                }
                f.await { f.button(label) != null }
                f.click(f.actionPoint(label))
                f.await { f.actions.size == index + 1 }
                val actual = f.actions.last()
                assertEquals(kind, actual.kind)
                if (kind == "space") assertEquals(item.uid, actual.uid)
                else assertSame(item, actual.item)
                actual.current?.let { assertTrue(it()) }
                f.await { f.button(label) == null }
            }
            assertEquals(listOf("copy", "space", "reply", "shield", "report"), f.actions.map { it.kind })
        } finally { f.close(); owner.close() }
    }

    @Test fun queuedSourceClickAndEmittedRowPredicateRejectStaleOwnershipWhileFreshRowsStayUsable(): Unit = runBlocking {
        val owner = SourceOwner()
        val item = LiveDanmakuItem("same message id and text", uid = 77, uname = "Fixture", idStr = "retained-id")
        val f = Scene(owner, item)
        try {
            f.openMenu()
            val report = assertNotNull(assertNotNull(f.button("举报选中弹幕")).config[SemanticsActions.OnClick].action)
            val original = checkNotNull(owner.player.currentSourceSnapshot())
            val old = checkNotNull(owner.memory.captureLiveSourceBinding())
            assertTrue(old.switch(0, 1) { true })
            assertTrue(checkNotNull(owner.memory.captureLiveSourceBinding()).switch(0, 0) { true })
            val returned = checkNotNull(owner.player.currentSourceSnapshot())
            assertEquals(original.sourceVersion, returned.sourceVersion)
            assertEquals(original.source.videoUrl, returned.source.videoUrl)
            assertNotSame(original.source.nativePublication, returned.source.nativePublication)
            assertFalse(old.current())
            // Before any scene redraw, this public action's Modifier is still attached.
            // The actual application admission must reject its now-retired source.
            report()
            f.pumpFor(100)
            assertTrue(f.actions.isEmpty())
            f.await { f.button("举报选中弹幕") == null }

            f.openMenu()
            f.click(f.actionPoint("复制弹幕信息"))
            f.await { f.actions.size == 1 }
            val oldAction = f.actions.single()
            assertSame(item, oldAction.item)
            val oldRowCurrent = assertNotNull(oldAction.current)
            assertTrue(oldRowCurrent())
            val unchangedSource = checkNotNull(owner.memory.captureLiveSourceBinding())
            assertTrue(unchangedSource.current())
            f.await { f.button("复制弹幕信息") == null }
            f.openMenu()
            val replacement = item.copy()
            assertEquals(item, replacement); assertNotSame(item, replacement)
            f.replace(replacement)
            f.pumpFor(100)
            f.await { f.button("屏蔽发送者") == null }
            // The old row really detached. Do not invoke its Modifier action.
            // This predicate came from the actual, attached pointer-copy callback.
            assertFalse(oldRowCurrent(), "Equal id/text must not revive an emitted old-row admission")
            assertTrue(unchangedSource.current(), "Only row identity changed, not the source or global list lifetime")
            assertEquals(1, f.actions.size)

            f.openMenu()
            f.click(f.actionPoint("复制弹幕信息"))
            f.await { f.actions.size == 2 }
            assertSame(replacement, f.actions.last().item)
            val captured = assertNotNull(f.actions.last().current)
            assertTrue(captured())
            assertFalse(oldRowCurrent())
            f.close()
            assertFalse(captured(), "A completed list scope must retire the callback even if the source is unchanged")
            assertTrue(unchangedSource.current())
        } finally { f.close(); owner.close() }
    }

    private data class Action(val kind: String, val item: LiveDanmakuItem? = null,
        val uid: Long = 0, val current: (() -> Boolean)? = null)

    private class SourceOwner {
        private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val sessions = DesktopSessionStore.temporary()
        private val repository: DesktopRepository
        val player = MpvPlayer()
        val memory = DesktopLivePageMemory(parent, player)
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-menu", "bili_jct" to "synthetic-menu-csrf"),
                AccountSummary(42, "Synthetic", ""))
            repository = DesktopRepository(sessions)
            val closedTransport = repository.httpClient.newBuilder().addInterceptor {
                error("Menu CPU source fixture cannot access an HTTP service")
            }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, closedTransport)
            val media = DesktopMediaRepository(repository)
            val room = LiveRoomDetails(123, "Fixture", "", "Synthetic", "", 1, "生活", 1)
            val response = Json { ignoreUnknownKeys = true }.decodeFromString<LivePlayUrlResponse>(
                """{"code":0,"data":{"playurl_info":{"playurl":{"g_qn_desc":[{"qn":10000,"desc":"原画"}],"stream":[{"protocol_name":"http_hls","format":[{"format_name":"fmp4","codec":[{"codec_name":"avc","current_qn":10000,"accept_qn":[10000],"base_url":"/live.m3u8","url_info":[{"host":"https://cdn.example","extra":"?first"},{"host":"https://backup.example","extra":"?backup"}]}]}]}]}}}}""")
            val info = requireNotNull(DesktopMediaRepository.selectLive(requireNotNull(response.data), room, 10000))
            val ports = desktopLiveRecoveryPorts(repository, media, repository.sessionEpoch, { false },
                { _, _ -> error("No mini-window belongs to this CPU scene") })
            check(ports.admit {
                memory.room = room
                player.loadVersioned(memory.nativePlaybackSource(info, room.roomId, ports))
                memory.installLivePlayback(info, checkNotNull(player.currentSourceSnapshot()), ports)
                memory.loaded = true
            })
        }
        suspend fun close() = withContext(NonCancellable) {
            try {
                memory.close(); player.close(); parent.cancel()
                withTimeout(5_000) { parent.coroutineContext.job.join() }
            } finally {
                try { repository.httpClient.dispatcher.executorService.shutdownNow() }
                finally { repository.httpClient.connectionPool.evictAll() }
            }
        }
    }

    private class Scene(owner: SourceOwner, initial: LiveDanmakuItem) : AutoCloseable {
        var messages by mutableStateOf(listOf(initial))
            private set
        val actions = mutableListOf<Action>()
        private val key = Any()
        private var nanos = 0L
        private var eventMillis = 0L
        private var closed = false
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(520, 420, Density(1f)) {
                val actualWindow = LocalWindowInfo.current
                val focused = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused = true
                } }
                CompositionLocalProvider(LocalWindowInfo provides focused,
                    LocalAppThemeConfig provides AppThemeConfig(nativeMiuixPopupsEnabled = false)) {
                    MaterialTheme {
                        DesktopLiveChatMessages(key, messages, true,
                            { actions += Action("reply", item = it) }, Modifier.fillMaxSize(),
                            captureCurrent = {
                                owner.memory.captureLiveSourceBinding()?.let { captured -> { captured.current() } }
                            },
                            onCopyInfo = { item, current -> actions += Action("copy", item, current = current) },
                            onUserClick = { actions += Action("space", uid = it) },
                            onBlockUser = { item, current -> actions += Action("shield", item, current = current) },
                            onReportDanmaku = { item, current -> actions += Action("report", item, current = current) })
                    }
                }
            }
        }
        fun replace(item: LiveDanmakuItem) {
            // The whole input really changes (as with a new system wire frame), so
            // Compose/remember must update even when the replaced item's values match.
            messages = listOf(item, LiveDanmakuItem("replacement frame marker", isSystem = true))
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun node(label: String) = all().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        fun button(label: String) = all().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
            }
        }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        fun actionPoint(label: String): Offset {
            // Popup layers use window/canvas coordinates. Do not add another popup offset.
            val bounds = assertNotNull(button(label)).boundsInWindow
            assertTrue(bounds.width > 0f && bounds.height > 0f)
            return bounds.center.also { point ->
                assertTrue(point.x in 0f..520f && point.y in 0f..420f,
                    "The actual menu action must have a visible scene input position")
            }
        }
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun pumpFor(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            do { render(); delay(12) } while (System.nanoTime() < end)
        }
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
        suspend fun openMenu() {
            await { button("更多") != null }
            click(assertNotNull(button("更多")).boundsInRoot.center)
            await { button("举报选中弹幕") != null }
        }
        override fun close() { if (!closed) { closed = true; scene.close() } }
    }
}
