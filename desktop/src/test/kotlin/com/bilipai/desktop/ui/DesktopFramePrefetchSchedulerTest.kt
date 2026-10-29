@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.ui.platform.PlatformPrefetchRequest
import androidx.compose.ui.platform.PlatformPrefetchRequestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Frame-timed prefetch: a fake clock, a fake EDT queue and fake frames. No window is created. */
class DesktopFramePrefetchSchedulerTest {
    private val hz165 = 1_000_000_000L / 165

    private class Harness {
        var now = 0L
        val edt = ArrayDeque<Runnable>()
        val frames = ArrayDeque<(Long) -> Unit>()
        val scheduler = DesktopFramePrefetchScheduler(
            awaitFrame = { frames.addLast(it) },
            post = { edt.addLast(it) },
            nanoTime = { now },
        )
        fun drainEdt() { while (edt.isNotEmpty()) edt.removeFirst().run() }
        /** A frame starts at [at]: the frame callbacks run, then the EDT gets the time after the frame. */
        fun frame(at: Long) {
            now = at
            val pending = frames.toList(); frames.clear()
            pending.forEach { it(at) }
            drainEdt()
        }
    }

    /**
     * Like a Compose prefetch request: it declines a budget smaller than its [cost] (and reports work left), otherwise
     * it spends [cost] ns of the fake clock on one of its [steps].
     */
    private class Request(private val h: Harness, private val cost: Long, private var steps: Int = 1) : PlatformPrefetchRequest {
        val budgets = mutableListOf<Long>()
        override fun PlatformPrefetchRequestScope.execute(): Boolean {
            val budget = availableTimeNanos()
            budgets += budget
            if (budget < cost) return true
            h.now += cost
            return --steps > 0
        }
    }

    private fun request(body: () -> Boolean) = object : PlatformPrefetchRequest {
        override fun PlatformPrefetchRequestScope.execute(): Boolean = body()
    }

    @Test
    fun theSliceAfterAFrameEndsBeforeTheNextFrameIsDue() {
        val start = 10_000_000L
        assertEquals(start + hz165 - hz165 / 6, desktopPrefetchSliceDeadline(start + 2_000_000, start, hz165))
        // No frame in the last interval: half a frame from now, so input waits at most that long.
        assertEquals(50_000_000L + hz165 / 2, desktopPrefetchSliceDeadline(50_000_000, start, hz165))
        assertEquals(7L + hz165 / 2, desktopPrefetchSliceDeadline(7, null, hz165))
    }

    @Test
    fun theIntervalIsTheShortestRecentFrameGapAndLateFramesOrPausesDoNotStretchIt() {
        val e = DesktopFrameIntervalEstimator()
        assertEquals(DESKTOP_PREFETCH_DEFAULT_INTERVAL_NANOS, e.intervalNanos, "nothing measured yet: assume 240 Hz")
        var t = 0L
        e.onFrame(t)
        for (gap in listOf(hz165, 2 * hz165, hz165, 3 * hz165)) { t += gap; e.onFrame(t) }
        assertEquals(hz165, e.intervalNanos)
        e.reset()
        e.onFrame(t + 2_000_000_000L) // the first frame after a pause pairs with nothing
        assertEquals(hz165, e.intervalNanos)

        val hz60 = DesktopFrameIntervalEstimator()
        t = 0L
        repeat(20) { hz60.onFrame(t); t += 16_666_666L }
        assertEquals(16_666_666L, hz60.intervalNanos, "a 60 Hz monitor gets 60 Hz slices")
    }

    @Test
    fun requestsRunAfterTheFrameThatQueuedThemAndHighPriorityGoesFirst() {
        val h = Harness()
        val order = mutableListOf<String>()
        h.scheduler.scheduleLowPriorityPrefetch(request { order += "low"; false })
        h.scheduler.scheduleHighPriorityPrefetch(request { order += "high"; false })
        assertTrue(order.isEmpty(), "nothing runs inside the frame that schedules")
        h.drainEdt()
        assertEquals(listOf("high", "low"), order)
        assertEquals(0, h.scheduler.queuedCount)
        assertTrue(h.frames.isEmpty(), "an empty queue requests no frame")
    }

    @Test
    fun workThatDoesNotFitWaitsForTheNextFrameAndGetsTheIdleTimeOfThatFrame() {
        val h = Harness()
        val half = DESKTOP_PREFETCH_DEFAULT_INTERVAL_NANOS / 2
        val a = Request(h, cost = half - 100_000)
        val b = Request(h, cost = 4_000_000)
        h.now = 1_000_000
        h.scheduler.scheduleHighPriorityPrefetch(a)
        h.scheduler.scheduleHighPriorityPrefetch(b)
        h.drainEdt()
        // No frame seen yet: half of the assumed 240 Hz frame. a fits, b is left 0.1 ms and waits for a frame.
        assertEquals(1, a.budgets.size)
        assertEquals(listOf(100_000L), b.budgets)
        assertEquals(1, h.frames.size, "the remaining request waits for exactly one frame")
        // Two frames 165 Hz apart: the second slice knows the real interval.
        h.frame(10_000_000)
        assertEquals(2, b.budgets.size, "still too short for 4 ms at the assumed rate")
        h.frame(10_000_000 + hz165)
        assertEquals(hz165, h.scheduler.frameIntervalNanos)
        // Started right after the frame callback: the whole measured frame less the margin.
        assertEquals(hz165 - hz165 / 6, b.budgets.last())
        assertEquals(0, h.scheduler.queuedCount)
        assertTrue(h.frames.isEmpty(), "no frame is requested once the queue is empty")
    }

    @Test
    fun aPausedCompositionResumesInLaterFramesAndARequestThatNeverFinishesIsDropped() {
        val h = Harness()
        val slow = Request(h, cost = 1_000_000, steps = 3)
        h.scheduler.scheduleLowPriorityPrefetch(slow)
        h.drainEdt()
        var t = 0L
        repeat(2) { t += hz165; h.frame(t) }
        assertEquals(3, slow.budgets.size)
        assertEquals(0, h.scheduler.queuedCount)

        h.scheduler.scheduleLowPriorityPrefetch(request { true })
        h.drainEdt()
        var frames = 0
        while (h.frames.isNotEmpty() && frames < 1000) { t += hz165; h.frame(t); frames++ }
        assertEquals(0, h.scheduler.queuedCount)
        assertTrue(frames < 100, "frames stop once the request is dropped ($frames)")
    }

    @Test
    fun aClosedSchedulerRunsNothingAndRequestsNoFrame() {
        val h = Harness()
        var ran = false
        h.scheduler.scheduleHighPriorityPrefetch(request { ran = true; false })
        h.scheduler.close()
        h.drainEdt()
        h.scheduler.scheduleHighPriorityPrefetch(request { ran = true; false })
        h.drainEdt()
        assertTrue(!ran && h.frames.isEmpty() && h.scheduler.queuedCount == 0)
    }
}
