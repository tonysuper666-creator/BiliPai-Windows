package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.android.purebilibili.feature.video.ui.components.AnimatedGesturePercentText
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopGesturePercentCompositionTest {
    private class Host(initial: Int) : AutoCloseable {
        val percent = mutableStateOf(initial)
        val visible = mutableStateOf(true)
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        private val scene = ImageComposeScene(240, 100, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                if (visible.value) AnimatedGesturePercentText(percent.value, Color.White, 40.sp, FontWeight.Bold, enableHaptic = false)
            }
        }
        fun frame(): IntArray {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            val bytes = scene.render(scheduler.currentTime * 1_000_000L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            }
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), errors.toString())
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            return image.getRGB(0, 0, 240, 100, null, 0, 240)
        }
        fun advance(frames: Int): IntArray {
            var result = frame()
            repeat(frames) { scheduler.advanceTimeBy(16); result = frame() }
            return result
        }
        override fun close() { scene.close(); scope.cancel(); scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString()) }
    }

    @Test fun rapidChangesSettleToTheSameSharpPixelsAsAFreshFinalValue() {
        Host(20).use { animated ->
            val initial = animated.advance(2)
            assertTrue(initial.any { it ushr 24 != 0 })
            animated.percent.value = 70
            val changing = animated.advance(2)
            assertFalse(initial.contentEquals(changing))
            animated.percent.value = 35
            animated.advance(1)
            animated.percent.value = 80
            val settled = animated.advance(100)
            Host(80).use { fresh ->
                assertContentEquals(fresh.advance(100), settled)
            }
            assertContentEquals(settled, animated.advance(20))
            animated.visible.value = false
            assertTrue(animated.advance(2).all { it ushr 24 == 0 })
            animated.visible.value = true
            assertContentEquals(settled, animated.advance(100))
        }
    }
}
