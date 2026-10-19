package com.bilipai.desktop.danmaku

import javax.swing.SwingUtilities
import javax.swing.Timer

/** Only event-loop ports. Frame admission/deadlines belong to original v038. */
internal interface DesktopDanmakuFramePlatform {
    fun isDispatchThread(): Boolean
    fun dispatch(action: () -> Unit)
    fun uptimeMillis(): Long
    fun removeWake(wake: Runnable)
    fun scheduleWake(wake: Runnable, delayMs: Long)
    fun requestFrameCallback(callback: (Long) -> Unit, deadlineNanos: Long)
    fun cancelFrameCallback(callback: (Long) -> Unit)
}

/**
 * One-shot Swing callbacks; parking retains no periodic timer or media data.
 *
 * Pacing decision (slice dmk-protocol-hardening, roadmap v039-danmaku-60fps-cadence): the overlay stays at the original
 * 60 fps cadence and does NOT derive its interval from the monitor refresh rate.
 * - The loop is hard-capped at 60 fps by construction. The verbatim original RenderFrameCadence advances its absolute
 *   deadline `cadence.nextFrameNs` in FRAME_INTERVAL_NS = 1_000_000_000L / 60L steps and requestFrameCallback arms one Swing
 *   Timer for that deadline, so at most one paint request per 16.7 ms is admitted whatever the panel rate. Upstream 295798c
 *   chose that cap on purpose (absolute deadlines, so a 90 Hz panel does not fall to 45 fps and a 120 Hz panel does not
 *   drift); it is the original design, not a Windows defect.
 * - Whether a faster loop would actually be shown is unmeasured. The overlay is a transparent JWindow painted by Java2D
 *   (`panel.repaint()`) over the mpv surface, the Swing Timer has whole-millisecond granularity and no vsync callback, and
 *   repainting a per-pixel translucent window of 5120x2160 may itself be too slow to exceed 60 fps. Nothing in this tree
 *   measures the achieved paint rate, so raising the cadence would be a guess that multiplies CPU use at 4K.
 * - A panel probe exists (DesktopOriginalVideoOverlayPlatform.readPanelRefreshRate, used only for the player debug label).
 *   If a measurement on the real display shows the 60 fps cap is visible, the follow-up is a counted, hash-pinned
 *   adaptation of v038_danmaku_cadence.cadence_source that makes the interval a constructor parameter (default: the
 *   original constant) plus a pure desktop policy that caps it, never a silent change of the carried class.
 */
internal class SwingDesktopDanmakuFramePlatform : DesktopDanmakuFramePlatform, AutoCloseable {
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
        // Round up to the original deadline; never create a second frame clock.
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
