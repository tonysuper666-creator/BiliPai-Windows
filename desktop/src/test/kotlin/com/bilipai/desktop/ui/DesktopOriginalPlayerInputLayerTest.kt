package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real production input-layer pixels and Compose events. Native Windows
 * layered-window hit testing and MPV still require a separate real UI retest. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopOriginalPlayerInputLayerTest {
    @Test fun hiddenControlsKeepNonzeroHitCoverageAtEveryWindowDensity() {
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) {
            withScene(density) {
                assertTrue(pixels().all { it ushr 24 == 1 }, "Every hidden-player pixel must remain hit-testable at density $density")
            }
        }
    }

    @Test fun transparentOriginalContentDoesNotEraseCoverageOrDimOpaqueControls() = withScene {
        controls.value = true
        val image = pixels()
        assertEquals(0xffffffff.toInt(), image[HEIGHT / 2 * WIDTH + WIDTH / 2])
        assertEquals(1, image.first() ushr 24)
        assertEquals(1, image.last() ushr 24)
        controls.value = false
        assertTrue(pixels().all { it ushr 24 == 1 })
    }

    @Test fun actualPointerEventsCanRevealThenHideTheControlsOnTheProductionLayer() = withScene {
        assertFalse(controls.value)
        click(10, 30)
        advanceTo(100)
        assertTrue(controls.value)
        assertEquals(0xffffffff.toInt(), pixels()[HEIGHT / 2 * WIDTH + WIDTH / 2])
        click(150, 180)
        advanceTo(250)
        assertFalse(controls.value)
        assertTrue(pixels().all { it ushr 24 == 1 })
        click(300, 330)
        advanceTo(400)
        assertTrue(controls.value, "Hiding the controls must not retire the input layer")
    }

    @Test fun inputCoverageChangesOpaqueVideoColorsByAtMostOneCodeValue() {
        for (color in listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff804020.toInt(), 0xff00a0ff.toInt())) {
            withScene(underlay = Color(color)) {
                for (actual in pixels()) {
                    assertEquals(255, actual ushr 24)
                    for (shift in listOf(0, 8, 16)) {
                        val expectedChannel = color ushr shift and 255
                        val actualChannel = actual ushr shift and 255
                        assertTrue(actualChannel in (expectedChannel - 1).coerceAtLeast(0)..expectedChannel,
                            "Input coverage exceeded its one-code-value compositing budget")
                    }
                }
            }
        }
    }

    private fun withScene(density: Float = 1f, underlay: Color? = null, block: InputScene.() -> Unit) {
        val scene = InputScene(density, underlay)
        try { scene.block() } finally { scene.close() }
    }

    private class InputScene(density: Float, underlay: Color?) {
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        val controls = mutableStateOf(false)
        private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(density), coroutineContext = scope.coroutineContext) {
            val root = if (underlay == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(underlay)
            Box(root) {
                DesktopOriginalPlayerInputLayer {
                    Box(Modifier.fillMaxSize().background(Color.Transparent).pointerInput(Unit) {
                        detectTapGestures(onTap = { controls.value = !controls.value })
                    }) {
                        if (controls.value) Box(Modifier.size(12.dp).align(Alignment.Center).background(Color.White))
                    }
                }
            }
        }
        init { pixels() }
        fun pixels(): IntArray {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            val pixels = scene.render(scheduler.currentTime * 1_000_000L).use { image ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocN32Pixels(image.width, image.height) && image.readPixels(bitmap))
                    IntArray(WIDTH * HEIGHT) { index -> bitmap.getColor(index % WIDTH, index / WIDTH) }
                }
            }
            scheduler.runCurrent()
            return pixels
        }
        fun advanceTo(time: Long) {
            require(time >= scheduler.currentTime)
            scheduler.advanceTimeBy(time - scheduler.currentTime)
            scheduler.runCurrent()
            pixels()
        }
        fun click(down: Long, up: Long) {
            event(PointerEventType.Press, down)
            event(PointerEventType.Release, up)
        }
        private fun event(type: PointerEventType, time: Long) {
            advanceTo(time)
            scene.sendPointerEvent(eventType = type, position = Offset(2f, 2f), timeMillis = time,
                type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = type == PointerEventType.Press),
                button = PointerButton.Primary)
            scheduler.runCurrent()
            pixels()
        }
        fun close() {
            try { scene.close() } finally { scope.cancel(); scheduler.runCurrent() }
            assertTrue(errors.isEmpty(), "Unexpected Compose/coroutine errors: $errors")
        }
    }

    private companion object { const val WIDTH = 96; const val HEIGHT = 64 }
}
