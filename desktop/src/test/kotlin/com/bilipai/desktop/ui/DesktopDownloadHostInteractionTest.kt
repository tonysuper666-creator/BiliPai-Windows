@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMuxer
import com.bilipai.desktop.download.DownloadTask
import com.bilipai.desktop.player.DesktopLocalPlaybackPublication
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Production host, real manager queue and isolated files; no application window or network. */
class DesktopDownloadHostInteractionTest {
    @TempDir lateinit var root: Path

    @Test fun selectedRowsReachTheOwnerAndCancelLeavesTheOriginalFile() = runBlocking {
        val files = (1..4).map { root.resolve("video-$it.mp4").also { p -> Files.writeString(p, "isolated-$it") } }
        val originals = files.mapIndexed { i, path -> OriginalTask(
            bvid = "BVfixture${i+1}", cid = (i+1).toLong(), title = "Fixture ${i+1}", cover = "", ownerName = "fixture",
            ownerFace = "", duration = 30, quality = 80, qualityDesc = "HD", videoUrl = "", audioUrl = "",
            status = DownloadStatus.COMPLETED, filePath = path.toString(), createdAt = (4-i).toLong(),
        ) }
        val queueFile = root.resolve("queue.json")
        val items = originals.map { DownloadTask(it, root.toString()) }
        Files.writeString(queueFile, Json.encodeToString(ListSerializer(DownloadTask.serializer()), items))
        val client = OkHttpClient.Builder().addInterceptor { error("Network is forbidden in this fixture") }.build()
        val manager = DesktopDownloadManager(client, queueFile, DownloadMuxer { _, _, _ -> error("Muxing forbidden") },
            publication = DesktopLocalPlaybackPublication({ true }, { action -> action(); true }))
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, e -> errors += e })
        var owned = true
        var admissions = 0
        val played = mutableListOf<String>()
        val bindings = DesktopOriginalDownloadListBindings(DesktopPluginContext(DesktopPluginStore(root.resolve("settings"))),
            manager, scope, { owned }, { action -> admissions++; action(); true }, { false }, {})
        assertEquals(originals.map { it.id }.toSet(), manager.tasks.value.map { it.id }.toSet(), "real queue restored")
        val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
        }
        val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val navigation = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
        val scene = ImageComposeScene(1100, 900, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val focused = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides focused, LocalDesktopHomePlatform provides platform,
                    LocalLifecycleOwner provides lifecycle, LocalNavigationEventDispatcherOwner provides navigation) {
                    DesktopOriginalDownloadListHost(bindings, {}, { played += it }, { played += it })
                }
            }
        }
        var nanos = 0L
        fun settle() { repeat(10) { scheduler.advanceTimeBy(30); scheduler.runCurrent(); nanos += 30_000_000; scene.render(nanos).close() }; assertTrue(errors.isEmpty(), "scene errors: $errors") }
        fun descendants(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descendants)
        fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        fun text(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true }
        fun clickNode(node: SemanticsNode, ctrl: Boolean = false, render: Boolean = true) {
            val point = node.boundsInRoot.center
            val modifiers = PointerKeyboardModifiers(isCtrlPressed = ctrl)
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = nanos / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000 + 1, type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true), keyboardModifiers = modifiers, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000 + 40, type = PointerType.Mouse,
                buttons = PointerButtons(), keyboardModifiers = modifiers, button = PointerButton.Primary)
            if (render) settle()
        }
        fun click(value: String, ctrl: Boolean = false, render: Boolean = true) = clickNode(
            assertNotNull(text(value), "missing $value; visible=" + nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }), ctrl, render)
        fun checkDeleteFiles() = clickNode(assertNotNull(nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox
        }, "delete files checkbox"))
        try {
            settle()
            click("Fixture 1")
            assertTrue(played.isEmpty(), "Selecting a completed download must not navigate away to playback")
            val rowDelete = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("删除") == true
            }, "row delete button")
            clickNode(rowDelete)
            assertNotNull(text("删除缓存"), "the row action remains clickable")
            click("取消")
            assertNotNull(text("删除选中 (1)"), "the row action does not replace the selection")
            nanos += 400_000_000
            click("Fixture 1", render = false)
            nanos += 100_000_000
            click("Fixture 1")
            assertEquals(listOf(originals[0].id), played, "double click still opens the selected task")
            click("Fixture 3", ctrl = true)
            assertNotNull(text("删除选中 (2)"))
            manager.remove(originals[1].id, deleteFiles = false)
            settle()
            assertNotNull(text("删除选中 (2)"), "queue refresh keeps selection by task ID")
            click("删除选中 (2)")
            assertNotNull(text("同时删除本地文件"))
            checkDeleteFiles()
            click("取消")
            assertEquals(0, admissions, "cancel must not admit a removal")
            assertEquals("isolated-1", Files.readString(files[0]))
            assertEquals("isolated-3", Files.readString(files[2]))
            click("删除选中 (2)")
            click("删除")
            assertEquals(setOf(originals[3].id), manager.tasks.value.map { it.id }.toSet(),
                "confirmation after a sorted queue refresh removes exactly the selected stable IDs")
            assertTrue(files.all(Files::exists), "queue-only removal preserves real files")
            val admissionsBeforeRetirement = admissions
            click("Fixture 4")
            click("删除选中 (1)")
            checkDeleteFiles()
            owned = false
            click("删除")
            assertEquals(admissionsBeforeRetirement, admissions, "a late confirmation after owner retirement cannot dispatch")
            assertEquals(setOf(originals[3].id), manager.tasks.value.map { it.id }.toSet())
            assertTrue(files.all(Files::exists))
        } finally {
            scene.close(); scope.cancel(); manager.shutdownForApplicationExit()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
