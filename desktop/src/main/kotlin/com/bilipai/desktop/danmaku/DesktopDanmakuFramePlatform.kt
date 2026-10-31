package com.bilipai.desktop.danmaku

import com.bytedance.danmaku.render.engine.RenderFrameCadence
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Kept for existing consumers and their lifecycle tests while generated scheduling retires. */
internal typealias DesktopOriginalDanmakuFrameRequests = DesktopDanmakuFrameRequests

/**
 * One request owner, one absolute monotonic timeline. Swing is not a vsync source:
 * the display rate sets the requested cadence, not a claim about achieved paint fps.
 */
internal class DesktopDanmakuFrameRequests(
    private val platform: DesktopDanmakuFramePlatform,
    private val visible: () -> Boolean,
    private val render: () -> Boolean,
) {
    private val cadence = RenderFrameCadence()
    private var pending: ((Long) -> Unit)? = null
    private var needsFrame = false
    private var generation = 0L
    private var wakeAtMs = 0L
    private var intervalNs = 1_000_000_000L / 60
    private var nextFrameNs = 0L
    private var lastFrameNs: Long? = null
    private val wakeFrame = Runnable {
        wakeAtMs = 0L
        enqueueFrame()
    }

    fun requestRenderFrame(delayMs: Long = 0L) {
        if (!platform.isDispatchThread()) {
            platform.dispatch { requestRenderFrame(delayMs) }
            return
        }
        needsFrame = true
        if (!visible()) return
        if (delayMs <= 0L) {
            platform.removeWake(wakeFrame)
            wakeAtMs = 0L
            enqueueFrame()
        } else if (pending == null) {
            val deadline = platform.uptimeMillis() + delayMs
            if (wakeAtMs == 0L || deadline < wakeAtMs) {
                platform.removeWake(wakeFrame)
                wakeAtMs = deadline
                platform.scheduleWake(wakeFrame, delayMs)
            }
        }
    }

    fun cancelRenderFrames() {
        if (!platform.isDispatchThread()) {
            platform.dispatch { cancelRenderFrames() }
            return
        }
        generation++
        platform.removeWake(wakeFrame)
        pending?.let(platform::cancelFrameCallback)
        pending = null
        needsFrame = false
        wakeAtMs = 0L
        nextFrameNs = 0L
        lastFrameNs = null
        cadence.reset()
    }

    fun updateVisibilityScheduling() {
        if (visible()) requestRenderFrame() else cancelRenderFrames()
    }

    private fun enqueueFrame() {
        if (!needsFrame || !visible()) return
        // DisplayMode uses zero for an unknown refresh rate. Keep a bounded fallback
        // for unavailable/invalid reports; ordinary monitors retain their actual rate.
        val rate = platform.refreshRateHz().takeIf { it in 1..1000 } ?: 60
        val interval = 1_000_000_000L / rate
        if (interval != intervalNs) {
            intervalNs = interval
            nextFrameNs = lastFrameNs?.plus(interval) ?: 0L
            pending?.let(platform::cancelFrameCallback)
            pending = null
        }
        if (pending != null) return
        val ticket = generation
        lateinit var callback: (Long) -> Unit
        callback = frame@{ timeNs ->
            // A cancelled callback must not consume the newly queued frame's intent.
            if (pending !== callback || ticket != generation) return@frame
            pending = null
            if (!needsFrame || !visible()) return@frame
            if (!cadence.shouldRender(timeNs) || (nextFrameNs != 0L && timeNs < nextFrameNs)) {
                enqueueFrame()
                return@frame
            }
            needsFrame = false
            lastFrameNs = timeNs
            cadence.onFrame(timeNs)
            nextFrameNs = if (nextFrameNs == 0L || timeNs - nextFrameNs > intervalNs * 4) {
                timeNs + intervalNs
            } else {
                // Skip missed deadlines without a burst of catch-up paints.
                nextFrameNs + ((timeNs - nextFrameNs) / intervalNs + 1) * intervalNs
            }
            if (render() && ticket == generation) requestRenderFrame()
        }
        pending = callback
        platform.requestFrameCallback(callback, nextFrameNs)
    }
}

/** Event-loop and current-display inputs; one desktop request owner controls all frame deadlines. */
internal interface DesktopDanmakuFramePlatform {
    fun refreshRateHz(): Int = 60
    fun isDispatchThread(): Boolean
    fun dispatch(action: () -> Unit)
    fun uptimeMillis(): Long
    fun removeWake(wake: Runnable)
    fun scheduleWake(wake: Runnable, delayMs: Long)
    fun requestFrameCallback(callback: (Long) -> Unit, deadlineNanos: Long)
    fun cancelFrameCallback(callback: (Long) -> Unit)
}

/** One-shot Swing callbacks. The mounted overlay supplies its current display, never the default screen. */
internal class SwingDesktopDanmakuFramePlatform(
    private val currentDisplayRefreshRate: () -> Int = { 60 },
) : DesktopDanmakuFramePlatform, AutoCloseable {
    override fun refreshRateHz(): Int = currentDisplayRefreshRate()
    private var wake: Runnable? = null
    private var frame: ((Long) -> Unit)? = null
    private var closed = false
    private val wakeTimer = Timer(0) {
        val callback = wake
        wake = null
        if (!closed) callback?.run()
    }.apply { isRepeats = false; isCoalesce = true }
    private val frameTimer = Timer(0) {
        val callback = frame
        frame = null
        if (!closed) callback?.invoke(System.nanoTime())
    }.apply { isRepeats = false; isCoalesce = true }

    val isRunning: Boolean get() = wakeTimer.isRunning || frameTimer.isRunning

    override fun isDispatchThread() = SwingUtilities.isEventDispatchThread()
    override fun dispatch(action: () -> Unit) { SwingUtilities.invokeLater { action() } }
    override fun uptimeMillis() = System.nanoTime() / 1_000_000L
    override fun removeWake(wake: Runnable) {
        check(isDispatchThread())
        if (this.wake === wake) { wakeTimer.stop(); this.wake = null }
    }
    override fun scheduleWake(wake: Runnable, delayMs: Long) {
        check(isDispatchThread())
        if (closed) return
        this.wake = wake
        wakeTimer.initialDelay = delayMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        wakeTimer.restart()
    }
    override fun requestFrameCallback(callback: (Long) -> Unit, deadlineNanos: Long) {
        check(isDispatchThread())
        if (closed) return
        frame = callback
        val remaining = if (deadlineNanos == 0L) 0L else (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
        // Round up to the absolute deadline; never create a second frame clock.
        frameTimer.initialDelay = ((remaining + 999_999L) / 1_000_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        frameTimer.restart()
    }
    override fun cancelFrameCallback(callback: (Long) -> Unit) {
        check(isDispatchThread())
        if (frame === callback) { frameTimer.stop(); frame = null }
    }
    override fun close() {
        check(isDispatchThread())
        closed = true
        wakeTimer.stop(); frameTimer.stop(); wake = null; frame = null
    }
}
