package com.android.purebilibili.feature.video.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuMaskFrame
import com.bilipai.desktop.danmaku.DesktopDanmakuSource
import com.bilipai.desktop.danmaku.DesktopOwnedWebMaskSource
import kotlinx.coroutines.*

/** The existing Overlay inherits these original mask members; this creates no scope, actor or transport. */
abstract class DesktopOriginalWebMaskOwner internal constructor() {
    protected abstract val webMaskLock: Any
    protected abstract val scope: CoroutineScope
    protected abstract val cachedCid: Long
    protected abstract val loadGeneration: Long
    protected abstract val webMaskEnabled: Boolean
    protected abstract val webMaskTransport: DesktopDanmakuSource
    protected abstract fun webMaskPositionMs(): Long
    protected abstract fun invalidateWebMaskPaint()
    private var target: DesktopOwnedWebMaskSource? = null
    private var maskLoadJob: Job? = null
    private var maskFetchJob: Job? = null
    private var windowGeneration: Long = 0L
    private var webMaskBytes: ByteArray? = null
    private var webMaskFps: Int = 0
    private var webMaskWindowStartMs: Long = Long.MIN_VALUE
    private var webMaskWindowEndMs: Long = Long.MIN_VALUE
    private var currentMaskFrames: List<DanmakuMaskFrame> = emptyList()
    private var publishedWindowGeneration = Long.MIN_VALUE
    private var toggleGeneration = 0L

    internal fun bindWebMaskSource(next: DesktopOwnedWebMaskSource?) = synchronized(webMaskLock) {
        retireWebMaskSource()
        target = next
        if (next != null && next.cid == cachedCid && webMaskEnabled && next.stillOwned()) fetchWebMask()
    }

    internal fun retireWebMaskSource() = synchronized(webMaskLock) {
        ++toggleGeneration; ++windowGeneration
        maskFetchJob?.cancel(); maskFetchJob = null
        maskLoadJob?.cancel(); maskLoadJob = null
        target = null
        webMaskBytes = null; webMaskFps = 0
        webMaskWindowStartMs = Long.MIN_VALUE; webMaskWindowEndMs = Long.MIN_VALUE
        currentMaskFrames = emptyList(); publishedWindowGeneration = Long.MIN_VALUE
        invalidateWebMaskPaint()
    }

    internal fun onWebMaskSettingChanged() = synchronized(webMaskLock) {
        ++toggleGeneration; ++windowGeneration
        maskFetchJob?.cancel(); maskFetchJob = null
        maskLoadJob?.cancel(); maskLoadJob = null
        currentMaskFrames = emptyList(); publishedWindowGeneration = Long.MIN_VALUE
        webMaskWindowStartMs = Long.MIN_VALUE; webMaskWindowEndMs = Long.MIN_VALUE
        if (webMaskEnabled && owned()) {
            if (webMaskBytes == null) fetchWebMask() else requestWebMaskWindow(webMaskPositionMs())
        }
        invalidateWebMaskPaint()
    }

    internal fun refreshWebMaskWindow(positionMs: Long) = synchronized(webMaskLock) {
        if (!owned()) { if (target != null) retireWebMaskSource(); return@synchronized }
        if (!webMaskEnabled) return@synchronized
        // A replacement parse retires the previous decode before its owned publication.
        if (!isWithinWebMaskWindowGuard(positionMs)) {
            ++windowGeneration
            requestWebMaskWindow(positionMs)
        }
    }

    internal fun validateWebMaskOwnership() = synchronized(webMaskLock) {
        if (target != null && !owned()) retireWebMaskSource()
    }

    internal fun onWebMaskSegmentWindowChanged() = synchronized(webMaskLock) {
        ++windowGeneration; maskLoadJob?.cancel(); maskLoadJob = null
        webMaskWindowStartMs = Long.MIN_VALUE; webMaskWindowEndMs = Long.MIN_VALUE
    }

    internal fun currentWebMaskFrame(positionMs: Long): DanmakuMaskFrame? = synchronized(webMaskLock) {
        if (!webMaskEnabled || !owned() || publishedWindowGeneration != windowGeneration) return@synchronized null
        // Windows compositor uses the current half-open frame, so a shared boundary cannot union two masks.
        currentMaskFrames.lastOrNull { positionMs >= it.startTimeMs && positionMs < it.endTimeMs }
    }

    internal fun webMaskAvailable():Boolean = synchronized(webMaskLock) {webMaskEnabled && owned() && webMaskBytes!=null}

    private fun owned(): Boolean = target?.let { it.cid == cachedCid && it.stillOwned() } == true
    private fun isWithinWebMaskWindowGuard(positionMs:Long):Boolean {
        if(webMaskWindowStartMs==Long.MIN_VALUE || webMaskWindowEndMs==Long.MIN_VALUE || webMaskWindowEndMs<=webMaskWindowStartMs)return false
        return // SELECTED_ORIGINAL_WINDOW_GUARD
    }
    private fun owned(cid: Long, generation: Long): Boolean =
        shouldApplyDanmakuLoadResult(cid, generation, cachedCid, loadGeneration) && owned()
    private fun fetchWebMask() {
        val source = target ?: return
        val expectedCid = cachedCid
        val expectedGeneration = loadGeneration
        val expectedToggleGeneration = toggleGeneration
        maskFetchJob?.cancel()
        maskFetchJob = scope.launch {
            try {
                if (!source.stillOwned()) return@launch
                val maskInfo = source.metadata(source.bvid, expectedCid).dmMask ?: return@launch
                ensureActive()
                if (!synchronized(webMaskLock) { owned(expectedCid, expectedGeneration) && webMaskEnabled && expectedToggleGeneration == toggleGeneration }) return@launch
                loadWebMask(expectedCid, maskInfo.maskUrl, maskInfo.fps, webMaskPositionMs(), expectedGeneration)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Same optional metadata failure behavior as original Result.getOrNull(). */ }
        }
    }

    private fun replaceMaskFrames(frames: List<DanmakuMaskFrame>, positionMs: Long, expectedCid: Long, requestGeneration: Long, requestWindowGeneration: Long) = synchronized(webMaskLock) {
        if (!webMaskEnabled || !isCurrentSegmentWindowRequest(expectedCid, requestGeneration, requestWindowGeneration)) return@synchronized
        // A retained window is limited to the original 40s at max60fps (+ two inclusive parser boundaries).
        // Path commands are also bounded in the actual Windows carrier; rejected optional masks leave video/danmaku active.
        currentMaskFrames = if(frames.size>MAX_CACHED_MASK_FRAMES || frames.sumOf {it.path.commandCount.toLong()}>MAX_CACHED_PATH_COMMANDS)emptyList()
            else frames.sortedBy(DanmakuMaskFrame::startTimeMs)
        publishedWindowGeneration = requestWindowGeneration
        invalidateWebMaskPaint()
    }

// SELECTED_ORIGINAL_METHODS

    companion object {
        private const val WEB_MASK_LOOK_BEHIND_MS = 10_000L
        private const val WEB_MASK_LOOK_AHEAD_MS = 30_000L
        private const val WEB_MASK_REFRESH_GUARD_MS = 5_000L
        private const val MAX_CACHED_MASK_FRAMES = 2402
        private const val MAX_CACHED_PATH_COMMANDS = 2_000_000L
    }
}
