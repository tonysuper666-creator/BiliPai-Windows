package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.DesktopWindowsConfirmedLikeBurstAnimation
import com.android.purebilibili.feature.video.ui.components.DesktopWindowsConfirmedTripleSuccessAnimation
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopConfirmedCelebrationCompositionTest {
    private fun verify(triple: Boolean) {
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        val visible = mutableStateOf(false)
        var completed = 0
        val listeners = mutableSetOf<DesktopHomeWindowBackgroundPort.Listener>()
        val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) { listeners += listener }
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) { listeners -= listener }
        }
        val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
        val scene = ImageComposeScene(400, 400, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
              CompositionLocalProvider(LocalDesktopHomePlatform provides platform) {
                if (triple) DesktopWindowsConfirmedTripleSuccessAnimation(
                    visible.value, reducedMotion = true, onAnimationEnd = { completed++ })
                else DesktopWindowsConfirmedLikeBurstAnimation(
                    visible.value, reducedMotion = true, onAnimationEnd = { completed++ })
              }
            }
        }
        fun frame(): ByteArray {
            Snapshot.sendApplyNotifications()
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            val image = scene.render(scheduler.currentTime * 1_000_000)
            val data = checkNotNull(image.encodeToData())
            val bytes = data.bytes
            data.close()
            image.close()
            scheduler.runCurrent()
            Thread.sleep(4)
            assertTrue(errors.isEmpty(), errors.toString())
            return bytes
        }
        fun advance(frames: Int) { repeat(frames) { frame() } }
        try {
            advance(4)
            val empty = frame()
            assertEquals(0, completed)
            visible.value = true
            advance(20)
            assertFalse(empty.contentEquals(frame()), "Actual packaged animation must draw")
            assertEquals(0, completed)
            advance(240)
            assertEquals(1, completed)
            advance(40)
            assertEquals(1, completed)
            visible.value = false
            advance(4)
            assertContentEquals(empty, frame(), "Hidden component must clear its raster")
            visible.value = true
            advance(260)
            assertEquals(2, completed, "Remount must complete exactly once again")
        } finally { scene.close(); scope.cancel(); scheduler.runCurrent() }
    }
    @Test fun confirmedLikeDrawsCompletesAndRemounts() = verify(false)
    @Test fun confirmedTripleDrawsCompletesAndRemounts() = verify(true)
}
