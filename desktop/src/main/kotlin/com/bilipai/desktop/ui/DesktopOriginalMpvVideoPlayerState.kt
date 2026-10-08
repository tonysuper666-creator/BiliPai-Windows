package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.session.PendingPlaybackUserAction
import com.android.purebilibili.feature.video.playback.session.PlaybackUserActionTracker
import com.android.purebilibili.feature.video.ui.overlay.PlaybackDebugInfo
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.player.PlayerTrack
import com.bilipai.desktop.player.PlayerFailure
import com.bilipai.desktop.player.platform.DesktopMedia3ColorTransfers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import java.util.IdentityHashMap

/** Platform value for the original speed/pitch policies. The sole MPV decoder keeps
 * pitch-corrected speed; independent pitch is an explicitly unavailable capability.
 */
data class DesktopOriginalPlaybackRate(val speed: Float, val pitch: Float = 1f) {
    companion object { val DEFAULT = DesktopOriginalPlaybackRate(1f) }
}

/** Actual MPV failure facts. Android renderer/code values are not manufactured. */
data class DesktopOriginalNativePlaybackError(val message: String, val failure: PlayerFailure?)
data class DesktopOriginalNativeVideoSize(val width: Int, val height: Int)

/** One extended command/readback view of the already installed Overlay control.
 * All native publication goes through its existing Store->entry->source admission.
 * This class neither loads a URL nor creates/closes any native player or scope.
 */
class DesktopOriginalMpvSectionControl internal constructor(
    nativePlayer: MpvPlayer,
    private val acceptedSourceVersion: () -> Long?,
    private val entryOwns: () -> Boolean,
    commitIfCurrent: ((() -> Unit) -> Boolean),
    diagnosticLoggingEnabled: () -> Boolean,
    ensurePreparedSource: () -> Unit,
    resumeEndedSource: () -> Unit,
    logSeekDiagnostic: (Long, Long, Long, Long) -> Unit,
    private val eventScope: CoroutineScope,
    private val sourceVersions: StateFlow<Long?>,
    private val commitEventIfCurrent: ((() -> Unit) -> Boolean),
    private val captureContinuation: (Long, Job) -> DesktopOriginalNativePlaybackContinuation? = { _, _ -> null },
) : DesktopOriginalMpvOverlayControl(nativePlayer, acceptedSourceVersion, entryOwns,
    commitIfCurrent, diagnosticLoggingEnabled, ensurePreparedSource, resumeEndedSource, logSeekDiagnostic) {
    override fun snapshot(): PlayerState {
        if (!entryOwns()) throw CancellationException("Original player entry retired")
        // A loading entry has no accepted native subject yet. Its UI starts at the
        // original empty player state, never reading another page's running source.
        if (acceptedSourceVersion() == null) return PlayerState()
        return super.snapshot()
    }
    var playbackParameters: DesktopOriginalPlaybackRate
        get() = DesktopOriginalPlaybackRate(playbackSpeed)
        set(value) {
            require(value.speed.isFinite() && value.speed > 0f)
            require(value.pitch == 1f) { "Windows MPV independent audio pitch is unavailable" }
            setPlaybackSpeed(value.speed)
        }
    var volume: Float
        get() = (snapshot().volume / 100.0).toFloat().let { if (snapshot().muted) 0f else it }
        set(value) { require(value.isFinite()); write { nativePlayer.setVolume(value.toDouble() * 100.0) } }
    fun setMuted(value: Boolean) = write { nativePlayer.setMuted(value) }
    fun toggleMuted() = write { nativePlayer.toggleMuted() }
    fun currentNativeSourceVersion(): Long? = acceptedSourceVersion().takeIf { isOwned() }
    val videoSize: DesktopOriginalNativeVideoSize get() = snapshot().let { DesktopOriginalNativeVideoSize(it.videoWidth, it.videoHeight) }
    val firstVideoFrameReady: Boolean get() = snapshot().firstVideoFrameReady
    val playerError: DesktopOriginalNativePlaybackError? get() = snapshot().let { it.error?.let { message -> DesktopOriginalNativePlaybackError(message, it.failure) } }
    val currentTracks: List<PlayerTrack> get() = snapshot().tracks
    var repeatMode: Int
        get() = if (snapshot().looping) REPEAT_MODE_ONE else REPEAT_MODE_OFF
        set(value) {
            require(value == REPEAT_MODE_OFF || value == REPEAT_MODE_ONE) { "Playlist repeat-all is owned by the existing queue coordinator" }
            write { nativePlayer.setLoop(value == REPEAT_MODE_ONE) }
        }
    private val eventLock = Any()
    private val registrations = IdentityHashMap<Listener, Job>()
    override fun onSeekQueued(submission: DesktopOriginalNativeSeekSubmission) {
        val listeners = synchronized(eventLock) { registrations.entries.map { it.key to it.value } }
        for ((listener, registrationJob) in listeners) {
            commitEventIfCurrent {
                if (entryOwns() && registrationJob.isActive && acceptedSourceVersion() == submission.sourceVersion &&
                    nativePlayer.ownsSourceVersion(submission.sourceVersion) &&
                    synchronized(eventLock) { registrations[listener] === registrationJob }) listener.onSeekQueued(submission)
            }
        }
    }
    override fun addListener(listener: Listener) {
        if (!entryOwns() || !eventScope.coroutineContext[Job]!!.isActive) return
        val created = synchronized(eventLock) {
            if (registrations.containsKey(listener)) return
            val job = eventScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                var previousVersion: Long? = null
                var previous: PlayerState? = null
                combine(state, sourceVersions) { state, version -> state to version }.collect { (_, version) ->
                    val callbackContext = currentCoroutineContext()
                    val registrationJob = checkNotNull(callbackContext[Job])
                    callbackContext.ensureActive()
                    if (version == null || !entryOwns() || acceptedSourceVersion() != version || !nativePlayer.ownsSourceVersion(version)) {
                        previousVersion = null
                        previous = null
                        return@collect
                    }
                    // Read the current StateFlow after source admission. combine can carry a
                    // delayed emission from before the owner published its replacement token.
                    // Fix the accepted publication BEFORE reading this event's native state.
                    // A known origin is kept even with an absent/stale EOF, so it cannot
                    // fall through to the source-less compatibility path after recovery.
                    val capturedContinuation = captureContinuation(version, registrationJob)
                    val value = state.value
                    val old = previous.takeIf { previousVersion == version }
                    fun admit(callback: () -> Unit) {
                        callbackContext.ensureActive()
                        commitEventIfCurrent {
                            if (entryOwns() && acceptedSourceVersion() == version && nativePlayer.ownsSourceVersion(version) &&
                                synchronized(eventLock) { registrations[listener] === registrationJob && registrationJob.isActive }) callback()
                        }
                    }
                    // A registration first observes state. An already-rendered native frame is
                    // delivered as its actual receipt, never inferred from position/isPlaying.
                    if (previousVersion != version) admit { listener.onSourceTransition(version) }
                    if (old == null || playbackStateFor(old) != playbackStateFor(value)) {
                        val continuation = capturedContinuation?.forEvent(value.nativeEof)
                        admit { listener.onPlaybackStateChanged(playbackStateFor(value), continuation) }
                    }
                    if (old == null || playingFor(old) != playingFor(value)) admit { listener.onIsPlayingChanged(playingFor(value)) }
                    if (old == null || old.paused != value.paused) admit { listener.onPlayWhenReadyChanged(!value.paused, 0) }
                    if (old == null || old.speed != value.speed) admit { listener.onPlaybackParametersChanged(DesktopOriginalPlaybackRate(value.speed.toFloat())) }
                    if (value.firstVideoFrameReady && old?.firstVideoFrameReady != true) admit { listener.onRenderedFirstFrame() }
                    if (old == null || old.tracks != value.tracks) admit { listener.onTracksChanged(value.tracks) }
                    if (value.error != null && (old?.error != value.error || old.failure != value.failure)) admit { listener.onPlayerError(DesktopOriginalNativePlaybackError(value.error, value.failure)) }
                    previousVersion = version
                    previous = value
                }
            }
            registrations[listener] = job
            job.invokeOnCompletion { synchronized(eventLock) { if (registrations[listener] === job) registrations.remove(listener) } }
            job
        }
        // Never launch user callbacks while holding the registration lock. Admission
        // takes Root Store/entry first, then the short registration identity check.
        created.start()
    }
    override fun removeListener(listener: Listener) {
        val job = synchronized(eventLock) { registrations.remove(listener) }
        job?.cancel()
    }
    private fun playbackStateFor(value: PlayerState): Int = when {
        value.ended -> STATE_ENDED
        value.error != null -> STATE_IDLE
        value.loading || value.pausedForCache -> STATE_BUFFERING
        value.firstVideoFrameReady || value.videoCodec != null || value.audioCodec != null -> STATE_READY
        else -> STATE_IDLE
    }
    private fun playingFor(value: PlayerState): Boolean = !(value.nativePaused ?: value.paused) && !value.ended && value.error == null && !value.loading && !value.pausedForCache &&
        (value.firstVideoFrameReady || value.videoCodec != null || value.audioCodec != null)
}

/** Actual native HDR metadata at the Android Format platform boundary. Unknown native
 * codec/color facts remain absent. No guessed bit rate, frame rate or transfer value.
 */
data class DesktopOriginalDecodedVideoFormat(val sampleMimeType: String?, val colorInfo: ColorInfo?) {
    data class ColorInfo(val colorTransfer: Int)
}

/** Read-only projections of the same MPV StateFlow plus the original API dimension UI
 * state. The supplied scope is the same page owner; closing it cancels only projections.
 */
class DesktopOriginalMpvVideoPlayerState internal constructor(
    val player: DesktopOriginalMpvSectionControl,
    private val scope: CoroutineScope,
    sourceVersion: StateFlow<Long?>,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val updateMetadata: (String, String, String) -> Unit,
) {
    private val mutableApiDimension = MutableStateFlow<Pair<Int, Int>?>(null)
    private val mutablePortraitFullscreen = MutableStateFlow(false)
    val apiDimension: StateFlow<Pair<Int, Int>?> = mutableApiDimension.asStateFlow()
    val isPortraitFullscreen: StateFlow<Boolean> = mutablePortraitFullscreen.asStateFlow()
    private val native = combine(player.state, sourceVersion) { state, version ->
        state.takeIf { isCurrent() && version != null && player.nativePlayer.ownsSourceVersion(version) }
    }.stateIn(scope, SharingStarted.Eagerly, null)
    val videoSize: StateFlow<Pair<Int, Int>> = native.map { it?.let { s -> s.videoWidth to s.videoHeight } ?: (0 to 0) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, 0 to 0)
    val isVerticalVideo: StateFlow<Boolean> = combine(videoSize, apiDimension) { size, api ->
        if (size.first > 0 && size.second > 0) size.second > size.first
        else api?.let { com.android.purebilibili.feature.video.state.resolveApiDimensionIsVertical(it.first, it.second) } ?: false
    }.stateIn(scope, SharingStarted.Eagerly, false)
    private val output = combine(player.nativePlayer.videoOutput, sourceVersion) { value, version ->
        value.takeIf { isCurrent() && version != null && it.sourceVersion == version && player.nativePlayer.ownsSourceVersion(version) }
    }
    val videoInputFormat: StateFlow<DesktopOriginalDecodedVideoFormat?> = combine(native, output) { state, current ->
        if (state == null || current == null || state.videoCodec == null) null
        else DesktopOriginalDecodedVideoFormat(
            if (current.dolbyVisionProfile != null) "video/dolby-vision" else null,
            current.gamma?.lowercase()?.let { gamma -> when (gamma) {
                "pq" -> DesktopOriginalDecodedVideoFormat.ColorInfo(DesktopMedia3ColorTransfers.COLOR_TRANSFER_ST2084)
                "hlg" -> DesktopOriginalDecodedVideoFormat.ColorInfo(DesktopMedia3ColorTransfers.COLOR_TRANSFER_HLG)
                else -> null
            } },
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)
    val debugInfo: StateFlow<PlaybackDebugInfo> = native.map { state ->
        if (state == null) PlaybackDebugInfo() else PlaybackDebugInfo(
            resolution = if (state.videoWidth > 0 && state.videoHeight > 0) "${state.videoWidth}x${state.videoHeight}" else "",
            videoCodec = state.videoCodec.orEmpty(), audioCodec = state.audioCodec.orEmpty(),
            videoDecoder = state.hardwareDecoder.orEmpty(),
            playbackState = when { state.ended -> "ENDED"; state.error != null -> "IDLE"; state.loading || state.pausedForCache -> "BUFFERING"; state.ready -> "READY"; else -> "IDLE" },
            playWhenReady = (!state.paused).toString(),
            isPlaying = (!(state.nativePaused ?: state.paused) && state.ready && !state.ended && !state.loading && !state.pausedForCache && state.error == null).toString(),
            firstFrame = if (state.firstVideoFrameReady) "rendered" else "",
            forwardBuffer = state.bufferedForwardSeconds?.let { "${(it * 1000).toLong()} ms" }.orEmpty(),
            lastLoadError = state.error.orEmpty(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, PlaybackDebugInfo())
    // This is the installed original user-action tracker keyed by the same Overlay
    // control object. It is not a second command or player authority.
    val pendingUserAction: StateFlow<PendingPlaybackUserAction?> = PlaybackUserActionTracker.stateFor(player)
    val diagnosticEvents: StateFlow<List<String>> = native.map { state ->
        state?.failure?.let { listOf("native load failure: ${it}") }.orEmpty()
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())
    private fun commit(block: () -> Unit) {
        if (!isCurrent()) throw CancellationException("Original player state entry retired")
        if (!commitIfCurrent { if (!isCurrent()) throw CancellationException("Original player state entry retired"); block() })
            throw CancellationException("Original player state entry retired")
    }
    fun setApiDimension(width: Int, height: Int, rotate: Int = 0) = commit {
        if (width > 0 && height > 0) {
            val normalizedRotate = ((rotate % 360) + 360) % 360
            val shouldSwap = normalizedRotate == 90 || normalizedRotate == 270
            val effectiveWidth = if (shouldSwap) height else width
            val effectiveHeight = if (shouldSwap) width else height
            mutableApiDimension.value = Pair(effectiveWidth, effectiveHeight)
        }
    }
    fun setPortraitFullscreen(fullscreen: Boolean) = commit { mutablePortraitFullscreen.value = fullscreen }
    fun updateMediaMetadata(title: String, artist: String, coverUrl: String) = commit { updateMetadata(title, artist, coverUrl) }
}
