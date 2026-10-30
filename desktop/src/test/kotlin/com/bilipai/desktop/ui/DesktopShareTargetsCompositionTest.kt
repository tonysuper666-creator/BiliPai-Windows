package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.video.share.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopShareTargetsCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    // Select the actual existing interaction-window content path without opening a native window.
    @Suppress("UNCHECKED_CAST")
    private fun windowLocal(): ProvidableCompositionLocal<Boolean> =
        Class.forName("com.bilipai.desktop.ui.DesktopWindowsVideoInteractionSectionKt")
            .getDeclaredField("LocalDesktopWindowsVideoInteractionWindow")
            .apply { isAccessible = true }.get(null) as ProvidableCompositionLocal<Boolean>

    private fun verify(width: Int, height: Int, retire: Boolean) {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val directory = Files.createTempDirectory("share-target-compose-")
        var owned = true
        val events = mutableListOf<String>()
        val bindings = DesktopVideoShareBindings(
            DesktopDynamicCardOperations(repository, repository.sessionEpoch),
            object : DesktopHomeFollowingRequests {
                override suspend fun getFollowings(mid: Long, page: Int, pageSize: Int) = error("No network")
            }, { _, _ -> error("No send") },
            DesktopVideoShareFiles(directory, { error("No download") }, { owned }, { action -> if (owned) { action(); true } else false }),
            { null }, { owned }, { error("No clipboard") }, {},
            DesktopTextShareBindings { _, _, _ -> error("No native chooser") },
            { _, _, _, _, _ -> error("No media handoff") }, { _, _ -> error("No save") },
            { true }, { width }, { height },
        )
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false),
                    LocalDesktopVideoShareBindings provides bindings, windowLocal() provides true) {
                    VideoShareMoreTargetsSheet(null, { events += "dismiss" }, { events += it.target.name }, { events += "chooser" })
                }
            }
        }
        fun frames() { repeat(5) { Snapshot.sendApplyNotifications(); scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000L).close(); scheduler.advanceTimeBy(16) }; scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString()) }
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun text(label: String) = all().single { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        fun click(label: String) {
            val point = text(label).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
            frames()
        }
        try {
            frames()
            val close = if (width > height) "关闭" else "取消"
            for (label in listOf(close, "Windows 系统分享", "复制视频链接", "系统分享面板")) {
                val bounds = text(label).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0, "$label: $bounds")
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height, "$label: $bounds outside ${width}x$height")
            }
            click(close)
            assertEquals(listOf("dismiss"), events)
            events.clear()
            if (retire) owned = false
            click("复制视频链接")
            assertEquals(if (retire) emptyList() else listOf("COPY_LINK"), events)
            click("Windows 系统分享")
            assertEquals(if (retire) emptyList() else listOf("COPY_LINK"), events, "openingTarget rejects a second dispatch")
        } finally {
            scene.close(); scope.cancel(); scheduler.runCurrent()
            repository.httpClient.dispatcher.cancelAll(); repository.httpClient.connectionPool.evictAll()
            directory.toFile().deleteRecursively()
            assertTrue(errors.isEmpty(), errors.toString())
        }
    }
    @Test fun landscapeWindowKeepsControlsVisibleAndDispatchesOnce() = verify(960, 540, false)
    @Test fun portraitWindowRejectsRetiredOwnerAfterTargetSelection() = verify(420, 800, true)
}
