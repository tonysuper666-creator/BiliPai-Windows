package com.bilipai.desktop.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import com.bilipai.desktop.player.MpvSoftwareFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Real Compose runtime and Skia raster copies, with no window, lifecycle owner, player or GPU. */
class DesktopHomeFrameBitmapTest {
    @Test fun playbackStateRecompositionReusesTheSameCopiedFrameBitmap() = runBlocking {
        val composition = FrameComposition(this, frame(1L, 0x10, 0x20, 0x30))
        try {
            val first = requireNotNull(composition.bitmap)
            assertEquals(0xff102030.toInt(), pixels(first).single())
            repeat(5) {
                composition.playbackTick.value++
                composition.recompose()
                assertSame(first, composition.bitmap)
                // The source raster was already closed inside the production conversion.
                assertEquals(0xff102030.toInt(), pixels(first).single())
            }
            assertEquals(6, composition.applied)
        } finally { composition.close() }
    }

    @Test fun aNewPublishedFrameGetsAnIndependentCopyAndRetirementClearsTheSlot() = runBlocking {
        val composition = FrameComposition(this, frame(1L, 0x10, 0x20, 0x30))
        try {
            val first = requireNotNull(composition.bitmap)
            composition.currentFrame.value = frame(2L, 0x40, 0x50, 0x60)
            composition.recompose()
            val second = requireNotNull(composition.bitmap)
            assertNotSame(first, second)
            assertEquals(0xff102030.toInt(), pixels(first).single())
            assertEquals(0xff405060.toInt(), pixels(second).single())
            composition.currentFrame.value = null
            composition.recompose()
            assertNull(composition.bitmap)
        } finally { composition.close() }
    }

    private fun frame(sequence: Long, red: Int, green: Int, blue: Int) =
        MpvSoftwareFrame(7L, sequence, 1, 1, 4,
            byteArrayOf(blue.toByte(), green.toByte(), red.toByte(), 0xff.toByte()))

    private fun pixels(bitmap: ImageBitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class FrameComposition(scope: CoroutineScope, initialFrame: MpvSoftwareFrame?) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.launch(clock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private val composition = Composition(EmptyApplier(), recomposer)
        val currentFrame = mutableStateOf(initialFrame)
        val playbackTick = mutableStateOf(0)
        var bitmap: ImageBitmap? = null
            private set
        var applied = 0
            private set
        init {
            composition.setContent {
                playbackTick.value
                bitmap = rememberDesktopHomeFrameBitmap(currentFrame.value)
                SideEffect { applied++ }
            }
        }
        suspend fun recompose() {
            Snapshot.sendApplyNotifications()
            withTimeout(10_000L) { while (!clock.hasAwaiters) yield() }
            clock.sendFrame(System.nanoTime())
            withTimeout(10_000L) { recomposer.awaitIdle() }
        }
        suspend fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }
}
