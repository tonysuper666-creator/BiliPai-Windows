package com.bilipai.desktop.brand

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

/** Actual animatable + packaged CPU renderer. A controlled Compose clock supplies frames, never window/OS time. */
class DesktopMaidFrameClockPlayerTest {
    private class Clock : MonotonicFrameClock {
        private val requests = Channel<CompletableDeferred<Long>>(Channel.UNLIMITED)
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val request = CompletableDeferred<Long>(currentCoroutineContext()[Job])
            requests.send(request)
            return onFrame(request.await())
        }
        suspend fun frame(timeNanos: Long) {
            // A canceled animate may have left a canceled waiter in this fixture
            // queue; exclude it while supplying only real currently awaited frames.
            while (true) {
                val request = requests.receive()
                if (request.complete(timeNanos)) return
            }
        }
    }

    private suspend fun loadedOwner(
        owner: DesktopMaidResourceOwner<DesktopMaidComposition> = DesktopMaidResourceOwner(),
    ): DesktopMaidResourceOwner<DesktopMaidComposition> {
        try {
            owner.load {
                val animation = DesktopMaidAnimation.RETRY
                fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/brand-motion/$name")).use { it.readBytes() }
                val opened = BrandMotionRenderer.open(animation, bytes(animation.asset.jsonFileName), bytes(animation.asset.pngFileName))
                val renderer = (opened as BrandMotionRendererOpenResult.Ready).renderer
                DesktopMaidComposition(animation, renderer, owner)
            }
            assertNotNull(owner.await())
            return owner
        } catch (failure: Throwable) { owner.close(); throw failure }
    }

    private suspend fun progress(player: DesktopMaidAnimatable, expected: Float) {
        withTimeout(2_000) { while (kotlin.math.abs(player.progress - expected) > 0.00001f) yield() }
    }

    @Test fun realFrameClockAnimateCancelAndResumeDoesNotChargeTheHiddenGap(): Unit = runBlocking {
        val owner = loadedOwner()
        val player = DesktopMaidAnimatable()
        val firstClock = Clock()
        val secondClock = Clock()
        var first: Job? = null
        var resumed: Job? = null
        try {
            val composition = assertNotNull(owner.await())
            assertTrue(composition.renderer.supportsAnimation)
            first = launch(firstClock) { player.animate(composition, 1, 1, 0f, false) }
            withTimeout(2_000) { firstClock.frame(1_000_000_000L); firstClock.frame(1_600_000_000L) }
            progress(player, 0.4f) // RETRY is the fixed 1500ms original asset.
            requireNotNull(first).cancelAndJoin()
            val saved = player.progress
            assertSame(composition, player.composition)
            // No frames are delivered during the logical hidden gap.
            assertEquals(saved, player.progress)
            resumed = launch(secondClock) { player.animate(composition, 1, 1, saved, false) }
            withTimeout(2_000) { secondClock.frame(30_000_000_000L) }
            progress(player, saved)
            withTimeout(2_000) { secondClock.frame(30_300_000_000L) }
            progress(player, 0.6f)
            withTimeout(2_000) { secondClock.frame(30_900_000_000L); requireNotNull(resumed).join() }
            assertEquals(1f, player.progress)
        } finally { first?.cancelAndJoin(); resumed?.cancelAndJoin(); owner.close() }
    }

    @Test fun reEnabledFinishedIdentityDrawsThroughNewLeaseWithoutRestartingFrameClock(): Unit = runBlocking {
        val oldOwner = loadedOwner()
        val newOwner = DesktopMaidResourceOwner<DesktopMaidComposition>()
        val player = DesktopMaidAnimatable()
        fun pixels(composition: DesktopMaidComposition): ByteArray = org.jetbrains.skia.Surface.makeRasterN32Premul(96, 96).use { target ->
            composition.renderer.render(target.canvas, 96, 96, player.progress.toDouble())
            target.makeImageSnapshot().use { image ->
                requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use { it.bytes }
            }
        }
        try {
            val oldComposition = assertNotNull(oldOwner.await())
            player.snapTo(oldComposition, 1f)
            val completedFrame = pixels(oldComposition)
            oldOwner.close() // Original OFF removes composition and closes native resources.
            assertTrue(oldComposition.renderer.resources().closed)
            assertFailsWith<IllegalStateException> { pixels(oldComposition) }
            val result = DesktopMaidCompositionResult(newOwner)
            assertNull(desktopMaidCompositionForDisplay(player.composition, result))
            loadedOwner(newOwner)
            val newComposition = assertNotNull(newOwner.await())
            val display = assertNotNull(desktopMaidCompositionForDisplay(player.composition, result))
            assertSame(newComposition, display)
            assertNotSame(oldComposition, display)
            var rendered: ByteArray? = null
            assertTrue(display.owner.useCurrent(display) { rendered = pixels(it) })
            assertContentEquals(completedFrame, assertNotNull(rendered))
            assertEquals(1f, player.progress)
            assertSame(oldComposition, player.composition) // No new animate call or frame request.
            assertFalse(newComposition.renderer.resources().closed)
            newOwner.close()
            assertNull(desktopMaidCompositionForDisplay(player.composition, result))
        } finally { oldOwner.close(); newOwner.close() }
    }

    @Test fun realReducedMotionSnapHasNoClockWaitAndRetiredCompositionCannotRestart(): Unit = runBlocking {
        val owner = loadedOwner()
        val composition = assertNotNull(owner.await())
        val player = DesktopMaidAnimatable()
        try {
            player.snapTo(composition, 1f)
            assertEquals(1f, player.progress)
            assertSame(composition, player.composition)
            owner.close()
            assertFailsWith<IllegalStateException> { player.snapTo(composition, 0f) }
        } finally { owner.close() }
    }
}
