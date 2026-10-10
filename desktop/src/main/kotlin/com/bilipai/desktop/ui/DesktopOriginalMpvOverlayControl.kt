package com.bilipai.desktop.ui

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerState
import com.android.purebilibili.feature.video.ui.overlay.PlaybackUserActionType
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException

/** Immutable admission receipt. Completion remains the actual native readback ID. */
data class DesktopOriginalNativeSeekSubmission(val sourceVersion: Long, val operationId: Long, val targetPositionMs: Long)

/** Real MPV readback and the existing Controller command admission. Not a Media3 player/decoder.
 * Root binds a fixed BVID/CID + page/epoch owner and a live accepted sourceVersion getter.
 */
open class DesktopOriginalMpvOverlayControl internal constructor(
    val nativePlayer: MpvPlayer,
    private val sourceVersion: () -> Long?,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val diagnosticLoggingEnabledPort: () -> Boolean,
    private val ensurePreparedSource: () -> Unit,
    private val resumeEndedSource: () -> Unit,
    private val logSeekDiagnostic: (Long, Long, Long, Long) -> Unit,
) {
    companion object {
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
    /** Events from the same native StateFlow; only the extended control with the
     * required entry scope implements admission. This is not a Media3 decoder. */
    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onPlaybackStateChanged(playbackState: Int, continuation: DesktopOriginalNativePlaybackContinuation?) {
            onPlaybackStateChanged(playbackState)
        }
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onPlaybackParametersChanged(parameters: DesktopOriginalPlaybackRate) {}
        fun onRenderedFirstFrame() {}
        fun onTracksChanged(tracks: List<com.bilipai.desktop.player.PlayerTrack>) {}
        fun onSourceTransition(sourceVersion: Long) {}
        fun onPlayerError(error: DesktopOriginalNativePlaybackError) {}
        fun onSeekQueued(submission: DesktopOriginalNativeSeekSubmission) {}
    }
    open fun addListener(listener: Listener) {
        error("The same-entry native event scope is required")
    }
    open fun removeListener(listener: Listener) {
        error("The same-entry native event scope is required")
    }
    val diagnosticLoggingEnabled get() = diagnosticLoggingEnabledPort()
    val state: StateFlow<PlayerState> get() = nativePlayer.state
    fun isOwned(): Boolean = isCurrent() && sourceVersion()?.let(nativePlayer::ownsSourceVersion) == true
    protected open fun snapshot(): PlayerState = state.value.also { if (!isOwned()) throw CancellationException("Original player source retired") }
    val currentPosition: Long get() = (snapshot().positionSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
    val duration: Long get() = (snapshot().durationSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
    val bufferedPosition: Long get() = snapshot().let {
        val buffered = it.positionSeconds.coerceAtLeast(0.0) + (it.bufferedForwardSeconds?.takeIf { value -> value.isFinite() && value >= 0.0 } ?: 0.0)
        (if (it.durationSeconds > 0.0) buffered.coerceAtMost(it.durationSeconds) else buffered).times(1000.0).toLong()
    }
    val playbackSpeed: Float get() = snapshot().speed.toFloat()
    val playbackState: Int get() = snapshot().let { when {
        it.ended -> DesktopOriginalPlaybackStates.STATE_ENDED
        it.error != null -> DesktopOriginalPlaybackStates.STATE_IDLE
        it.loading || it.pausedForCache -> DesktopOriginalPlaybackStates.STATE_BUFFERING
        it.firstVideoFrameReady || it.videoCodec != null || it.audioCodec != null -> DesktopOriginalPlaybackStates.STATE_READY
        else -> DesktopOriginalPlaybackStates.STATE_IDLE
    } }
    val isPlaying: Boolean get() = snapshot().let {
        !(it.nativePaused ?: it.paused) && !it.ended && it.error == null && !it.loading && !it.pausedForCache &&
            (it.firstVideoFrameReady || it.videoCodec != null || it.audioCodec != null)
    }
    var playWhenReady: Boolean
        get() = !snapshot().paused
        set(value) { write { nativePlayer.setPaused(!value) } }
    val playerErrorMessage: String? get() = snapshot().error
    val mediaItemCount: Int get() = if (isOwned()) 1 else 0
    // Lexical synchronous original UseCase writes may need a captured origin or
    // accepted child. This is a borrowed gate, never a new source/command owner.
    private val lexicalCommandAdmission = ThreadLocal<((() -> Unit) -> Boolean)?>()
    internal fun withSourceCommandAdmission(admission: (() -> Unit) -> Boolean, action: () -> Unit) {
        val previous = lexicalCommandAdmission.get()
        lexicalCommandAdmission.set(admission)
        try { action() }
        finally { if (previous == null) lexicalCommandAdmission.remove() else lexicalCommandAdmission.set(previous) }
    }
    protected fun write(block: () -> Unit) {
        if (!isOwned()) return
        val admission = lexicalCommandAdmission.get() ?: commitIfCurrent
        admission { if (isOwned()) block() }
    }
    fun prepare() = write(ensurePreparedSource)
    fun play() = write { if (state.value.ended) resumeEndedSource() else nativePlayer.setPaused(false) }
    fun pause() = write { nativePlayer.setPaused(true) }
    fun seekTo(positionMs: Long) { seekToTracked(positionMs) }
    fun seekToTracked(positionMs: Long): DesktopOriginalNativeSeekSubmission? {
        var queued: DesktopOriginalNativeSeekSubmission? = null
        write {
            val version = sourceVersion() ?: return@write
            val target = positionMs.coerceAtLeast(0L)
            val id = nativePlayer.seekToTrackedIfSourceVersion(version, target / 1000.0) ?: return@write
            queued = DesktopOriginalNativeSeekSubmission(version, id, target)
            onSeekQueued(checkNotNull(queued))
        }
        return queued
    }
    protected open fun onSeekQueued(submission: DesktopOriginalNativeSeekSubmission) {}
    fun setPlaybackSpeed(value: Float) = write { nativePlayer.setSpeed(value.toDouble()) }
    fun logSeek(targetPositionMs: Long, currentPositionMs: Long, bufferedPositionMs: Long, durationMs: Long) {
        if (isOwned()) logSeekDiagnostic(targetPositionMs, currentPositionMs, bufferedPositionMs, durationMs)
    }
}
