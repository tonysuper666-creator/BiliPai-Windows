package com.android.purebilibili.feature.video.ambient

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AmbientFrameControllerTest {
    private val policy = AmbientRunPolicy(true, true, true, false, 0, false)

    @Test fun cancelledPlatformRequestMustCompleteBeforeNextSessionCanCapture() = runTest {
        val complete = CompletableDeferred<Unit>()
        var captures = 0
        var released = false
        val source = object : AmbientFrameSource {
            override suspend fun capture(width: Int, height: Int): Bitmap? {
                captures++
                withContext(NonCancellable) { complete.await() }
                return null
            }
            override fun release() { released = true }
        }
        val controller = AmbientFrameController(AmbientPresentation()) { testScheduler.currentTime }
        val first = launch { controller.run(source, 1920, 1080) { policy } }
        runCurrent()
        first.cancel()
        controller.invalidate()
        val second = launch { controller.run(source, 1920, 1080) { policy } }
        runCurrent()
        assertEquals(1, captures)
        assertFalse(released)
        complete.complete(Unit)
        runCurrent()
        assertTrue(released)
        assertEquals(2, captures)
        second.cancelAndJoin()
        first.join()
    }

    @Test fun failuresBackOffWithoutQueuingRequestsAndInactivePlayerDoesNotSample() = runTest {
        var captures = 0
        var active = false
        val source = object : AmbientFrameSource {
            override suspend fun capture(width: Int, height: Int): Bitmap? { captures++; return null }
            override fun release() = Unit
        }
        val controller = AmbientFrameController(AmbientPresentation()) { testScheduler.currentTime }
        val job = launch { controller.run(source, 1920, 1080) { policy.copy(active = active) } }
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, captures)
        active = true
        advanceTimeBy(350); runCurrent()
        assertEquals(3, captures)
        advanceTimeBy(1000); runCurrent()
        assertEquals(3, captures)
        job.cancelAndJoin()
    }
}
