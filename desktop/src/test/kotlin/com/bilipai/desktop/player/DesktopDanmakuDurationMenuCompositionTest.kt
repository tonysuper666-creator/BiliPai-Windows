package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.DanmakuContextMenu
import com.android.purebilibili.feature.video.ui.components.resolveDanmakuTimestampJumpMs
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopDanmakuDurationMenuCompositionTest {
    private class Harness : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, e -> errors += e })
        val native = MutableStateFlow(PlayerState(durationSeconds = Double.NaN))
        val video = mutableStateOf(1L)
        val visible = mutableStateOf(true)
        val text = mutableStateOf("跳到 0:30")
        val seeks = mutableListOf<Pair<Long, Long>>()
        var dismissals = 0
        val platform = object : DesktopDanmakuPlatform {
            override fun isOwned() = true
            override fun showFeedback(message: String) = error("Unexpected feedback")
            override fun copyText(text: String, label: String) = error("Unexpected clipboard")
            @Composable override fun textSelection(text: String, title: String?, onDismiss: () -> Unit) = error("Unexpected selection")
        }
        val scene = ImageComposeScene(800, 1000, Density(1f), coroutineContext = scope.coroutineContext) {
            // Same collected PlayerState/provider boundary as Shell; actual production menu below.
            val state by native.collectAsState()
            val durationMs = { ((state.durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0) * 1_000).toLong() }
            MaterialTheme { CompositionLocalProvider(LocalDesktopDanmakuBindings provides platform) {
                key(video.value) {
                    val capturedVideo = video.value
                    if (visible.value) DanmakuContextMenu(
                        text = text.value,
                        timestampJumpMs = resolveDanmakuTimestampJumpMs(text.value),
                        durationMs = durationMs(),
                        onSeekToTimestamp = { if (capturedVideo == video.value) seeks += capturedVideo to it },
                        onDismiss = { if (capturedVideo == video.value) { dismissals++; visible.value = false } },
                        onLike = { error("Unexpected like") },
                        onRecall = { error("Unexpected recall") },
                        onReport = { error("Unexpected report") },
                    )
                }
            } }
        }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun jump() = all().singleOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.startsWith("跳转到 ") } == true }
        fun jumpAction(): () -> Boolean {
            var n: SemanticsNode? = assertNotNull(jump())
            while (n != null && !n.config.contains(SemanticsActions.OnClick)) n = n.parent
            return assertNotNull(n).config[SemanticsActions.OnClick].action!!
        }
        fun frame() {
            repeat(12) { Snapshot.sendApplyNotifications(); scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000).close(); scheduler.advanceTimeBy(20) }
            scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString())
        }
        fun duration(seconds: Double) { native.value = native.value.copy(durationSeconds = seconds); frame() }
        fun reopen() { visible.value = false; frame(); visible.value = true; frame() }
        override fun close() { scene.close(); scope.cancel(); scheduler.runCurrent() }
    }
    @Test fun actualMenuHidesUnknownZeroEndAndBeyondButSeeksExactInteriorTarget() = Harness().use { h ->
        h.frame(); assertNull(h.jump(), "unknown duration")
        h.duration(0.0); assertNull(h.jump(), "zero duration")
        h.duration(30.0); assertNull(h.jump(), "target equals duration")
        h.duration(29.0); assertNull(h.jump(), "target exceeds duration")
        h.duration(90.0); assertNotNull(h.jump())
        h.text.value = "跳到 0:00"; h.frame(); assertNull(h.jump(), "zero target")
        h.text.value = "跳到 0:30"; h.frame()
        assertTrue(h.seeks.isEmpty()); assertEquals(0, h.dismissals)
        h.jumpAction().invoke(); h.frame()
        assertEquals(listOf(1L to 30_000L), h.seeks)
        assertEquals(1, h.dismissals); assertNull(h.jump())
    }
    @Test fun reopeningUsesKnownDurationAndNewVideoDoesNotRetainPreviousLength() = Harness().use { h ->
        h.frame(); assertNull(h.jump())
        h.visible.value = false; h.frame(); h.duration(90.0); h.reopen()
        assertNotNull(h.jump())
        h.video.value = 2L; h.native.value = PlayerState(durationSeconds = 10.0); h.frame()
        assertNull(h.jump(), "new shorter video must not reuse 90 seconds")
        assertTrue(h.seeks.isEmpty()); assertEquals(0, h.dismissals); assertTrue(h.visible.value)
        h.duration(60.0); h.reopen(); h.jumpAction().invoke(); h.frame()
        assertEquals(listOf(2L to 30_000L), h.seeks); assertEquals(1, h.dismissals)
    }
}
