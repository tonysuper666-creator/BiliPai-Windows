package com.bilipai.desktop.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.TripleSuccessAnimation
import com.android.purebilibili.feature.video.ui.feedback.TripleCelebrationPlacement
import com.android.purebilibili.feature.video.ui.feedback.resolveTripleCelebrationPlacement
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopTripleCelebrationLayoutTest {
    private fun verify(reduced: Boolean) {
        for ((width, height) in listOf(400 to 800, 1280 to 720)) {
            val scheduler = TestCoroutineScheduler()
            val errors = mutableListOf<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
            var bounds = Rect.Zero
            var completed = 0
            val visible = mutableStateOf(true)
            val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = scope.coroutineContext) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        val placement = resolveTripleCelebrationPlacement(width > height, width > height)
                        Box(Modifier.align(when (placement) {
                            TripleCelebrationPlacement.CenterOverlay -> Alignment.Center
                            TripleCelebrationPlacement.BottomTrailing -> Alignment.BottomEnd
                        }).onGloballyPositioned { bounds = it.boundsInRoot() }) {
                            TripleSuccessAnimation(visible.value, isCompact = false, reducedMotion = reduced,
                                onAnimationEnd = { completed++ })
                        }
                    }
                }
            }
            fun advance(frames: Int) {
                repeat(frames) {
                    Snapshot.sendApplyNotifications()
                    scheduler.advanceTimeBy(16)
                    scheduler.runCurrent()
                    scene.render(scheduler.currentTime * 1_000_000).close()
                    scheduler.runCurrent()
                    assertTrue(errors.isEmpty(), errors.toString())
                }
            }
            try {
                advance(40)
                assertEquals(Rect((width - 320).toFloat(), (height - 240).toFloat(), width.toFloat(), height.toFloat()), bounds)
                assertFalse(bounds.contains(androidx.compose.ui.geometry.Offset(width / 2f, height / 2f)))
                assertEquals(0, completed)
                advance(200)
                assertEquals(1, completed)
                advance(40)
                assertEquals(1, completed)
                visible.value = false
                advance(3)
                assertEquals(0f, bounds.width)
                assertEquals(0f, bounds.height)
            } finally { scene.close(); scope.cancel(); scheduler.runCurrent() }
        }
    }
    @Test fun normalCelebrationUsesTheTrailingBottomAndCompletesOnce() = verify(false)
    @Test fun reducedCelebrationUsesTheTrailingBottomAndCompletesOnce() = verify(true)
}
