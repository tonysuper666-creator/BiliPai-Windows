@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalPlatformPrefetchScheduler
import androidx.compose.ui.platform.PlatformPrefetchRequest
import androidx.compose.ui.platform.PlatformPrefetchRequestScope
import androidx.compose.ui.platform.PlatformPrefetchScheduler
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

/*
 * Compose Multiplatform 1.12 hands a desktop window a no-op prefetch scheduler, so every lazy list and grid composes an
 * item inside the frame that first shows it. On a 5K window the home feed brings a whole row of cards in at once and
 * that frame misses the 165 Hz deadline (TON-1001: about one late frame per wheel step). This scheduler gives the lazy
 * layouts the time between frames instead.
 *
 * It is not the Android Choreographer scheduler: the desktop has no vsync callback for idle time. A frame is awaited
 * through the window's own frame clock (only while requests are queued, so an idle window draws nothing extra); the
 * work runs on the EDT right after that frame and stops before the next one is due. The frame interval is measured
 * from the same clock, so it follows whatever monitor the window is on without asking AWT.
 */

/** The shortest frame interval assumed before one has been measured (240 Hz): a slice can only be too short. */
internal const val DESKTOP_PREFETCH_DEFAULT_INTERVAL_NANOS = 1_000_000_000L / 240

/** The budget of one slice: the time left until the next frame, less a margin, or half a frame when frames are idle. */
internal fun desktopPrefetchSliceDeadline(now: Long, lastFrameStart: Long?, intervalNanos: Long): Long {
    require(intervalNanos > 0)
    val margin = maxOf(intervalNanos / 6, 500_000L)
    return if (lastFrameStart != null && now - lastFrameStart in 0 until intervalNanos) {
        lastFrameStart + intervalNanos - margin
    } else {
        now + intervalNanos / 2
    }
}

/**
 * The display interval seen through the frame clock: the shortest of the recent frame-to-frame gaps. With vsync on a frame
 * never comes sooner than the display refreshes, and a late frame only makes a gap longer, so the minimum is the refresh.
 */
internal class DesktopFrameIntervalEstimator(private val window: Int = 16) {
    private val gaps = LongArray(window)
    private var count = 0
    private var next = 0
    private var previous: Long? = null

    fun onFrame(frameTimeNanos: Long) {
        val last = previous
        previous = frameTimeNanos
        val gap = frameTimeNanos - (last ?: return)
        if (gap !in MIN_GAP..MAX_GAP) return
        gaps[next] = gap
        next = (next + 1) % window
        if (count < window) count++
    }

    /** A gap after an idle stretch is not a display interval; the next frame starts a new pair. */
    fun reset() { previous = null }

    val intervalNanos: Long
        get() = if (count == 0) DESKTOP_PREFETCH_DEFAULT_INTERVAL_NANOS else (0 until count).minOf { gaps[it] }

    private companion object {
        const val MIN_GAP = 1_000_000L // 1000 Hz
        const val MAX_GAP = 50_000_000L // 20 Hz: anything longer is a pause, not a refresh
    }
}

/**
 * Runs Compose prefetch requests in the idle time of the window's frames. All methods are called on the EDT.
 * [post] queues work behind the current EDT event; [awaitFrame] calls back with the frame time at the start of the next frame.
 */
internal class DesktopFramePrefetchScheduler(
    private val awaitFrame: (onFrame: (frameTimeNanos: Long) -> Unit) -> Unit,
    private val post: (Runnable) -> Unit = SwingUtilities::invokeLater,
    private val nanoTime: () -> Long = System::nanoTime,
) : PlatformPrefetchScheduler {
    private class Queued(val request: PlatformPrefetchRequest) { var slices = 0 }

    private val high = ArrayDeque<Queued>()
    private val low = ArrayDeque<Queued>()
    private val interval = DesktopFrameIntervalEstimator()
    private var posted = false
    private var waitingForFrame = false
    private var lastFrameStart: Long? = null
    private var closed = false
    private val run = Runnable { runSlice() }

    override fun scheduleHighPriorityPrefetch(request: PlatformPrefetchRequest) = enqueue(high, request)
    override fun scheduleLowPriorityPrefetch(request: PlatformPrefetchRequest) = enqueue(low, request)

    private fun enqueue(queue: ArrayDeque<Queued>, request: PlatformPrefetchRequest) {
        if (closed) return
        queue.addLast(Queued(request))
        if (!posted && !waitingForFrame) postSlice()
    }

    private fun postSlice() { posted = true; post(run) }

    private fun runSlice() {
        posted = false
        if (closed) return
        val deadline = desktopPrefetchSliceDeadline(nanoTime(), lastFrameStart, interval.intervalNanos)
        val scope = object : PlatformPrefetchRequestScope {
            override fun availableTimeNanos(): Long = maxOf(0L, deadline - nanoTime())
        }
        while (!closed) {
            val queue = if (high.isNotEmpty()) high else if (low.isNotEmpty()) low else break
            if (scope.availableTimeNanos() <= 0L) break
            val head = queue.first()
            val moreWork = with(head.request) { scope.execute() }
            if (!moreWork) {
                queue.removeFirst()
                continue
            }
            // A request that cannot finish in this many slices (about 0.4 s of frames) is dropped: the lazy layout
            // composes the item itself when it scrolls in, and the queue must not keep frames running forever.
            if (++head.slices >= MAX_SLICES_PER_REQUEST) queue.removeFirst() else break
        }
        if (closed) return
        if (high.isEmpty() && low.isEmpty()) {
            interval.reset()
            return
        }
        waitingForFrame = true
        awaitFrame { frameTimeNanos ->
            waitingForFrame = false
            interval.onFrame(frameTimeNanos)
            lastFrameStart = nanoTime()
            if (!closed && !posted) postSlice()
        }
    }

    fun close() {
        closed = true
        high.clear()
        low.clear()
    }

    internal val queuedCount: Int get() = high.size + low.size
    internal val frameIntervalNanos: Long get() = interval.intervalNanos

    private companion object {
        const val MAX_SLICES_PER_REQUEST = 64
    }
}

/** The scheduler for one window's composition, driven by that composition's frame clock. */
@Composable
internal fun rememberDesktopFramePrefetchScheduler(): DesktopFramePrefetchScheduler {
    val scope = rememberCoroutineScope()
    val scheduler = remember(scope) {
        DesktopFramePrefetchScheduler(awaitFrame = { onFrame -> scope.launch { withFrameNanos(onFrame) } })
    }
    DisposableEffect(scheduler) { onDispose { scheduler.close() } }
    return scheduler
}

/** Gives the lazy layouts of [content] the idle time of the window's frames instead of the platform's no-op scheduler. */
@Composable
internal fun DesktopFramePrefetchScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlatformPrefetchScheduler provides rememberDesktopFramePrefetchScheduler(), content = content)
}
