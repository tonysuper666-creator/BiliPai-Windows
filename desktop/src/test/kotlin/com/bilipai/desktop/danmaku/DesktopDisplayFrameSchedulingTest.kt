package com.bilipai.desktop.danmaku

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDisplayFrameSchedulingTest {
    private class Loop(var rate: Int) : DesktopDanmakuFramePlatform {
        var now = 1_000_000_000L
        var deadline = 0L
        var callback: ((Long) -> Unit)? = null
        var queued = 0
        override fun refreshRateHz() = rate
        override fun isDispatchThread() = true
        override fun dispatch(action: () -> Unit) = action()
        override fun uptimeMillis() = now / 1_000_000L
        override fun removeWake(wake: Runnable) = Unit
        override fun scheduleWake(wake: Runnable, delayMs: Long) = error("Unexpected wake")
        override fun requestFrameCallback(callback: (Long) -> Unit, deadlineNanos: Long) {
            assertNull(this.callback, "At most one frame callback may be armed")
            this.callback = callback
            deadline = deadlineNanos
            queued++
        }
        override fun cancelFrameCallback(callback: (Long) -> Unit) {
            if (this.callback === callback) this.callback = null
        }
        fun fire(time: Long = maxOf(now, deadline)) {
            now = time
            val active = requireNotNull(callback)
            callback = null
            active(time)
        }
    }

    @Test fun `60 120 and 165 Hz retain absolute deadlines despite millisecond callback rounding`() {
        for (rate in listOf(60, 120, 165)) {
            val loop = Loop(rate)
            var frames = 0
            val requests = DesktopDanmakuFrameRequests(loop, { true }, { frames++; true })
            requests.requestRenderFrame()
            loop.fire()
            val origin = loop.now
            val interval = 1_000_000_000L / rate
            repeat(1_000) { index ->
                assertEquals(origin + interval * (index + 1), loop.deadline, "rate=$rate")
                loop.fire(((loop.deadline + 999_999L) / 1_000_000L) * 1_000_000L)
            }
            assertEquals(1_001, frames)
            requests.cancelRenderFrames()
            assertNull(loop.callback)
        }
    }

    @Test fun `moving the overlay to 165 Hz replaces a pending 60 Hz deadline`() {
        val loop = Loop(60); var frames = 0
        val requests = DesktopDanmakuFrameRequests(loop, { true }, { frames++; true })
        requests.requestRenderFrame(); loop.fire()
        val stale = requireNotNull(loop.callback)
        loop.rate = 165
        requests.updateVisibilityScheduling()
        val replacement = requireNotNull(loop.callback)
        assertEquals(loop.now + 1_000_000_000L / 165, loop.deadline)
        stale(loop.now + 20_000_000L)
        assertEquals(1, frames)
        assertTrue(loop.callback === replacement)
        loop.fire(); assertEquals(2, frames)
        requests.cancelRenderFrames()
    }

    @Test fun `moving to a slower display does not retain the faster cadence`() {
        val loop = Loop(165)
        val requests = DesktopDanmakuFrameRequests(loop, { true }, { true })
        requests.requestRenderFrame(); loop.fire()
        loop.rate = 60; requests.requestRenderFrame()
        assertEquals(loop.now + 1_000_000_000L / 60, loop.deadline)
        requests.cancelRenderFrames()
    }

    @Test fun `late frame skips missed slots instead of queuing catch-up paints`() {
        val loop = Loop(165); var frames = 0
        val requests = DesktopDanmakuFrameRequests(loop, { true }, { frames++; true })
        requests.requestRenderFrame(); loop.fire()
        val before = loop.queued
        loop.fire(loop.now + 2_000_000_000L)
        assertEquals(2, frames)
        assertEquals(before + 1, loop.queued)
        assertEquals(loop.now + 1_000_000_000L / 165, loop.deadline)
        requests.cancelRenderFrames()
    }

    @Test fun `early callback cannot paint before the requested deadline`() {
        val loop = Loop(165); var frames = 0
        val requests = DesktopDanmakuFrameRequests(loop, { true }, { frames++; true })
        requests.requestRenderFrame(); loop.fire()
        val deadline = loop.deadline
        loop.fire(deadline - 1)
        assertEquals(1, frames); assertEquals(deadline, loop.deadline)
        loop.fire(); assertEquals(2, frames)
        requests.cancelRenderFrames()
    }

    @Test fun `unknown monitor rate uses 60 Hz and pause parks the same request owner`() {
        val loop = Loop(0); var playing = true
        val requests = DesktopDanmakuFrameRequests(loop, { true }, { playing })
        requests.requestRenderFrame(); loop.fire()
        assertEquals(loop.now + 1_000_000_000L / 60, loop.deadline)
        playing = false; loop.fire()
        assertNull(loop.callback)
        requests.requestRenderFrame(); loop.fire()
        assertNull(loop.callback)
    }

    @Test fun `retired callback cannot consume new source invalidation`() {
        val loop = Loop(165); var frames = 0
        val requests = DesktopDanmakuFrameRequests(loop, { true }, { frames++; false })
        requests.requestRenderFrame()
        val retired = requireNotNull(loop.callback)
        requests.cancelRenderFrames(); requests.requestRenderFrame()
        retired(loop.now)
        assertEquals(0, frames); assertNotNull(loop.callback)
        loop.fire(); assertEquals(1, frames)
    }

    @Test fun `cancellation during rendering prevents a new periodic callback`() {
        val loop = Loop(165)
        lateinit var requests: DesktopDanmakuFrameRequests
        requests = DesktopDanmakuFrameRequests(loop, { true }, { requests.cancelRenderFrames(); true })
        requests.requestRenderFrame(); loop.fire()
        assertNull(loop.callback)
    }

    @Test fun `real Swing platform reads the current display input and closes both one-shot timers`() {
        SwingUtilities.invokeAndWait {
            var rate = 120
            val platform = SwingDesktopDanmakuFramePlatform { rate }
            assertEquals(120, platform.refreshRateHz())
            rate = 165; assertEquals(165, platform.refreshRateHz())
            platform.scheduleWake(Runnable { error("Closed wake fired") }, 60_000)
            platform.requestFrameCallback({ error("Closed frame fired") }, System.nanoTime() + 60_000_000_000L)
            assertTrue(platform.isRunning)
            platform.close()
            assertFalse(platform.isRunning)
            platform.requestFrameCallback({ error("Closed platform resumed") }, 0)
            assertFalse(platform.isRunning)
        }
    }
}
