package com.bilipai.desktop.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopOriginalDanmakuFrameRequestsTest {
    private class Loop : DesktopDanmakuFramePlatform {
        var now = 1_000_000_000L
        var edt = true
        var frame: ((Long) -> Unit)? = null
        var deadline = 0L
        var wake: Runnable? = null
        var wakeDelay = 0L
        var frameRequests = 0
        val posted = mutableListOf<() -> Unit>()
        override fun isDispatchThread() = edt
        override fun dispatch(action: () -> Unit) { posted += action }
        override fun uptimeMillis() = now / 1_000_000L
        override fun removeWake(wake: Runnable) { if (this.wake === wake) this.wake = null }
        override fun scheduleWake(wake: Runnable, delayMs: Long) { this.wake = wake; wakeDelay = delayMs }
        override fun requestFrameCallback(callback: (Long) -> Unit, deadlineNanos: Long) {
            frame = callback; deadline = deadlineNanos; frameRequests++
        }
        override fun cancelFrameCallback(callback: (Long) -> Unit) { if (frame === callback) frame = null }
        fun fireFrame() {
            now = maxOf(now, deadline)
            val callback = requireNotNull(frame)
            frame = null
            callback(now)
        }
    }

    @Test fun `repeated invalidations coalesce into one original frame request`() {
        val loop = Loop(); var rendered = 0
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { true }, { rendered++; false })
        repeat(20) { requests.requestRenderFrame() }
        assertEquals(1, loop.frameRequests)
        loop.fireFrame()
        assertEquals(1, rendered)
        assertNull(loop.frame)
    }

    @Test fun `playing renderer continues on original absolute deadlines then parks on pause`() {
        val loop = Loop(); var playing = true; var rendered = 0
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { true }, { rendered++; playing })
        requests.requestRenderFrame(); loop.fireFrame()
        assertEquals(1_016_666_666L, loop.deadline)
        loop.fireFrame(); assertEquals(2, rendered)
        playing = false; loop.fireFrame()
        assertEquals(3, rendered)
        assertNull(loop.frame); assertNull(loop.wake)
    }

    @Test fun `hidden surface cancels both callbacks and restoration resets deadline debt`() {
        val loop = Loop(); var visible = true; var rendered = 0
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { visible }, { rendered++; true })
        requests.requestRenderFrame(); loop.fireFrame()
        visible = false; requests.updateVisibilityScheduling()
        assertNull(loop.frame); assertNull(loop.wake)
        loop.now += 9_000_000_000L
        visible = true; requests.updateVisibilityScheduling()
        assertEquals(0L, loop.deadline)
        loop.fireFrame(); assertEquals(2, rendered)
    }

    @Test fun `source retirement before queued callback never paints the retired source`() {
        val loop = Loop(); var owned = true; var rendered = 0
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { owned }, { rendered++; true })
        requests.requestRenderFrame()
        owned = false; loop.fireFrame()
        assertEquals(0, rendered); assertNull(loop.frame)
    }

    @Test fun `a parked renderer can be invalidated for one additional paint`() {
        val loop = Loop(); var rendered = 0
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { true }, { rendered++; false })
        requests.requestRenderFrame(); loop.fireFrame()
        requests.requestRenderFrame(); loop.fireFrame()
        assertEquals(2, rendered); assertNull(loop.frame)
    }

    @Test fun `delayed requests keep the earliest original wake and immediate requests replace it`() {
        val loop = Loop()
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { true }, { false })
        requests.requestRenderFrame(500)
        requests.requestRenderFrame(600)
        assertEquals(500L, loop.wakeDelay)
        requests.requestRenderFrame(200)
        assertEquals(200L, loop.wakeDelay); assertNotNull(loop.wake)
        requests.requestRenderFrame()
        assertNull(loop.wake); assertNotNull(loop.frame)
    }

    @Test fun `off thread request is dispatched before touching frame platform`() {
        val loop = Loop(); loop.edt = false
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { true }, { false })
        requests.requestRenderFrame()
        assertNull(loop.frame); assertEquals(1, loop.posted.size)
        loop.edt = true; loop.posted.removeAt(0).invoke()
        assertNotNull(loop.frame)
    }

    @Test fun `cancelled callback arriving late cannot resume a parked loop`() {
        val loop = Loop(); var rendered = 0
        val requests = DesktopOriginalDanmakuFrameRequests(loop, { true }, { rendered++; true })
        requests.requestRenderFrame()
        val retired = requireNotNull(loop.frame)
        requests.cancelRenderFrames()
        assertNull(loop.frame)
        retired(loop.now)
        assertEquals(0, rendered); assertNull(loop.frame)
    }
}
