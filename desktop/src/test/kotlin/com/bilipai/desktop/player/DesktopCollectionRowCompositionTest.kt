package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.ui.LocalVisualWorkAllowed
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.CollectionRow
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopCollectionRowCompositionTest {
    @TempDir lateinit var directory: Path

    private class Host(directory: Path) : AutoCloseable {
        val playing = mutableStateOf(true)
        val allowed = mutableStateOf(true)
        val cid = mutableLongStateOf(22)
        var clicks = 0
        private val repository = DesktopRepository(DesktopSessionStore(directory.resolve("session.json")))
        private val store = DesktopPluginStore(directory.resolve("preferences"))
        private val bindings = DesktopCollectionBindings(DesktopPluginContext(store), DesktopDynamicCardOperations(repository),
            { true }, { error("Unexpected feedback: $it") }, { _, _, _ -> error("Unexpected share") })
        private val season = UgcSeason(title = "测试合集", sections = listOf(UgcSection(episodes = listOf(
            UgcEpisode(aid = 1, bvid = "BV-fixture", cid = 11),
            UgcEpisode(aid = 1, bvid = "BV-fixture", cid = 22)))))
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val scene = ImageComposeScene(640, 100, Density(1f), coroutineContext = scope.coroutineContext) {
            val actual = LocalWindowInfo.current
            val focused = remember(actual) { object : WindowInfo by actual { override val isWindowFocused = true } }
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCollectionBindings provides bindings, LocalLifecycleOwner provides owner,
                    LocalWindowInfo provides focused, LocalVisualWorkAllowed provides allowed.value) {
                    // Zero collection id deliberately prevents the subscription button's network read.
                    CollectionRow(season, "BV-fixture", currentCid = cid.longValue, isPlaying = playing.value, onClick = { clicks++ })
                }
            }
        }
        private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun texts() = all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
        private fun frame(): java.awt.image.BufferedImage {
            Snapshot.sendApplyNotifications(); scheduler.runCurrent()
            val bytes = scene.render(scheduler.currentTime * 1_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
            scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString())
            return ImageIO.read(ByteArrayInputStream(bytes))
        }
        fun advance(frames: Int): IntArray {
            var image = frame()
            repeat(frames) { scheduler.advanceTimeBy(16); image = frame() }
            val indicator = all().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { s -> s.startsWith("合集视频") } == true }
            val b = indicator.boundsInRoot
            assertEquals(14f, b.width); assertEquals(16f, b.height)
            return image.getRGB(b.left.toInt(), b.top.toInt(), 14, 16, null, 0, 14)
        }
        fun clickTitle() {
            val n = all().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "测试合集" } == true }
            scene.sendPointerEvent(PointerEventType.Press, n.boundsInRoot.center, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, n.boundsInRoot.center, buttons = PointerButtons())
            frame()
        }
        override fun close() { scene.close(); scope.cancel(); scheduler.runCurrent(); store.freezeWrites(); assertTrue(errors.isEmpty(), errors.toString()) }
    }

    @Test fun realCollectionBarsAnimateOnlyWhilePlayingAndVisuallyActive() {
        Host(directory).use { h ->
            val moving = h.advance(10)
            assertFalse(moving.contentEquals(h.advance(15)))
            h.playing.value = false
            val paused = h.advance(3)
            assertContentEquals(paused, h.advance(20))
            h.playing.value = true; h.allowed.value = false
            assertContentEquals(paused, h.advance(20))
            h.allowed.value = true
            (h.owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED
            assertContentEquals(paused, h.advance(20))
            (h.owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
            assertFalse(h.advance(10).contentEquals(h.advance(15)))
        }
    }

    @Test fun rowClickAndCurrentPartContinueUsingTheOriginalCollectionContract() {
        Host(directory).use { h ->
            h.advance(3)
            assertTrue("2/2" in h.texts())
            h.clickTitle(); assertEquals(1, h.clicks)
            h.cid.longValue = 11; h.advance(3)
            assertTrue("1/2" in h.texts())
            h.clickTitle(); assertEquals(2, h.clicks)
        }
    }
}
