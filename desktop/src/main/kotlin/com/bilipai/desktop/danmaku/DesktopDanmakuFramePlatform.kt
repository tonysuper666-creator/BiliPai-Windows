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

/** One-shot Swing callbacks; parking retains no periodic timer or media data. */
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
