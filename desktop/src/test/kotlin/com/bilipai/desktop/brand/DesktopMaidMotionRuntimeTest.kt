package com.bilipai.desktop.brand

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

/** Real per-composition publication/clock helpers, without Skia, window, native player or asset IO. */
class DesktopMaidMotionRuntimeTest {
    private class Resource : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun successfulResultHasStableIdentityUntilRetirement(): Unit = runBlocking {
        val resource = Resource()
        val owner = DesktopMaidResourceOwner<Resource>()
        try {
            owner.load { resource }
            assertSame(resource, owner.await())
            assertSame(resource, owner.await())
            var used = false
            assertTrue(owner.useCurrent(resource) { used = true })
            assertTrue(used)
        } finally { owner.close() }
        assertNull(owner.await())
        assertEquals(1, resource.closes.get())
    }

    @Test fun closedBeforeLoadCannotDecodeOrPublish(): Unit = runBlocking {
        val owner = DesktopMaidResourceOwner<Resource>()
        owner.close()
        var decoded = false
        owner.load { decoded = true; Resource() }
        assertFalse(decoded)
        assertNull(owner.current)
        assertNull(owner.await())
    }

    @Test fun retiredCompositionClosesALateResourceInsteadOfPublishingIt(): Unit = runBlocking {
        val owner = DesktopMaidResourceOwner<Resource>()
        val resource = Resource()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val loading = launch {
            owner.load { entered.complete(Unit); check(release.await(2, TimeUnit.SECONDS)); resource }
        }
        withTimeout(2_000) { entered.await() }
        owner.close()
        release.countDown()
        withTimeout(2_000) { loading.join() }
        assertNull(owner.current)
        assertNull(owner.await())
        assertEquals(1, resource.closes.get())
        assertFalse(owner.useCurrent(resource) { error("retired renderer must never draw") })
    }

    @Test fun promptCancellationAtIoReturnClosesTheInnerOrphan(): Unit = runBlocking {
        val owner = DesktopMaidResourceOwner<Resource>()
        val resource = Resource()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val loading = launch {
            owner.load { entered.complete(Unit); check(release.await(2, TimeUnit.SECONDS)); resource }
        }
        try {
            withTimeout(2_000) { entered.await() }
            loading.cancel()
            release.countDown()
            withTimeout(2_000) { loading.join() }
            assertNull(owner.current)
            assertNull(owner.await())
            assertEquals(1, resource.closes.get())
        } finally { release.countDown(); owner.close(); loading.cancelAndJoin() }
    }

    @Test fun decodeFailureCompletesAwaitWithoutFabricatingARenderer(): Unit = runBlocking {
        val owner = DesktopMaidResourceOwner<Resource>()
        try {
            owner.load { throw IllegalArgumentException("fixture decode rejected") }
            assertNull(withTimeout(2_000) { owner.await() })
            assertNull(owner.current)
        } finally { owner.close() }
    }

    @Test fun oldResultCompletionCannotCloseOrReplaceTheSuccessor(): Unit = runBlocking {
        val oldOwner = DesktopMaidResourceOwner<Resource>()
        val newOwner = DesktopMaidResourceOwner<Resource>()
        val oldResource = Resource()
        val newResource = Resource()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val loading = launch { oldOwner.load { entered.complete(Unit); check(release.await(2, TimeUnit.SECONDS)); oldResource } }
        try {
            withTimeout(2_000) { entered.await() }
            oldOwner.close()
            newOwner.load { newResource }
            release.countDown()
            withTimeout(2_000) { loading.join() }
            assertSame(newResource, newOwner.await())
            assertEquals(0, newResource.closes.get())
            assertEquals(1, oldResource.closes.get())
        } finally { release.countDown(); oldOwner.close(); newOwner.close(); loading.cancelAndJoin() }
    }

    @Test fun duplicateLoadCannotCreateASecondOwnedRenderer(): Unit = runBlocking {
        val owner = DesktopMaidResourceOwner<Resource>()
        val resource = Resource()
        try {
            owner.load { resource }
            owner.load { error("a second factory must not execute") }
            assertSame(resource, owner.await())
        } finally { owner.close(); owner.close() }
        assertEquals(1, resource.closes.get())
    }

    @Test fun resumedEpochPreservesProgressWithoutChargingHiddenTime(): Unit {
        val first = DesktopMaidFrameProgress(1_000.0, 0f)
        assertEquals(0f, first.advance(1_000_000_000L))
        val saved = first.advance(1_400_000_000L)
        assertEquals(0.4f, saved, 0.000001f)
        val resumed = DesktopMaidFrameProgress(1_000.0, saved)
        assertEquals(saved, resumed.advance(20_000_000_000L), 0.000001f)
        assertEquals(0.5f, resumed.advance(20_100_000_000L), 0.000001f)
    }

    @Test fun firstFrameDoesNotConsumeTimeBeforePlaybackAndCompletionIsBounded(): Unit {
        val clock = DesktopMaidFrameProgress(1_200.0, 0f)
        assertEquals(0f, clock.advance(50_000_000_000L))
        assertEquals(0.5f, clock.advance(50_600_000_000L), 0.000001f)
        assertEquals(1f, clock.advance(52_000_000_000L))
    }

    @Test fun newLoopStartsAtZeroAndNeverReusesThePriorEpoch(): Unit {
        val prior = DesktopMaidFrameProgress(1_600.0, 0f)
        prior.advance(1_000_000_000L)
        assertEquals(1f, prior.advance(2_600_000_000L))
        assertEquals(0f, DesktopMaidFrameProgress(1_600.0, 0f).advance(30_000_000_000L))
    }

    @Test fun invalidDurationProgressOrNonMonotonicClockIsRejected(): Unit {
        assertFailsWith<IllegalArgumentException> { DesktopMaidFrameProgress(Double.NaN, 0f) }
        assertFailsWith<IllegalArgumentException> { DesktopMaidFrameProgress(0.0, 0f) }
        assertFailsWith<IllegalArgumentException> { DesktopMaidFrameProgress(1_000.0, Float.NaN) }
        val clock = DesktopMaidFrameProgress(1_000.0, 0f)
        clock.advance(100L)
        assertFailsWith<IllegalArgumentException> { clock.advance(99L) }
    }
}
