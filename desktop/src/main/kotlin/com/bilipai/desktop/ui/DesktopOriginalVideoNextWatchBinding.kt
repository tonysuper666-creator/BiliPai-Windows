package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.next.NextWatchSuggestion
import com.android.purebilibili.feature.video.playback.next.nextWatchRemainingSeconds
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only emitted from the installed owner's real accepted source and clock.
 * Target is original immutable lookahead; this object never contains a new URL. */
internal class DesktopOriginalVideoNextWatchHint internal constructor(
    val target: NextWatchSuggestion,
    val remainingSeconds: Int,
    internal val accepted: DesktopOriginalVideoAcceptedPublication,
    internal val originalRequest: PlaybackRequest,
    internal val requestToken: Long,
) {
    override fun toString() = "DesktopOriginalVideoNextWatchHint(sourceVersion=${accepted.sourceVersion}, remainingSeconds=$remainingSeconds)"
}

/** Live bottom consumer of the original lookahead over existing MPV/VM/playlist.
 * No timer, queue, decoder, source receipt or EOF observer is created. Original
 * selection runs outside gates; final hint/click admission is short and owned. */
internal class DesktopOriginalVideoNextWatchBinding(
    private val owner: DesktopOriginalVideoOwnerAssembly,
    private val scope: CoroutineScope,
    sourceVersions: StateFlow<Long?>,
) : AutoCloseable {
    private val mutable = MutableStateFlow<DesktopOriginalVideoNextWatchHint?>(null)
    val state: StateFlow<DesktopOriginalVideoNextWatchHint?> = mutable.asStateFlow()
    private var closed = false
    private val worker = scope.launch(Dispatchers.Main) {
        combine(owner.native.player.state, owner.playback.uiState, owner.playback.sleepTimerMinutes,
            sourceVersions) { _, _, _, _ -> Unit }.collect { refresh() }
    }
    private fun current(hint: DesktopOriginalVideoNextWatchHint): Boolean {
        if (closed || !scope.isActive || !owner.owns() || !owner.native.isCurrent(hint.accepted)) return false
        val session = owner.captureLoadState()
        val ui = owner.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return false
        return session.currentRequest === hint.originalRequest && session.currentLoadRequestToken == hint.requestToken &&
            ui.info.bvid == hint.target.sourceBvid && ui.info.cid == hint.target.sourceCid &&
            ui.info.bvid == hint.accepted.request.bvid && ui.info.cid == hint.accepted.request.cid && !ui.isQualitySwitching
    }
    private fun seconds(): Int? {
        val clock = owner.native.player.state.value
        // The existing owned Section observes this accepted file's playback
        // state. Core initialization alone is not source readiness or output proof.
        if (owner.section.playbackState != DesktopOriginalPlaybackStates.STATE_READY ||
            clock.loading || clock.pausedForCache || clock.ended || clock.error != null || clock.failure != null ||
            !clock.durationSeconds.isFinite() || !clock.positionSeconds.isFinite()) return null
        return nextWatchRemainingSeconds((clock.durationSeconds * 1000).toLong(), (clock.positionSeconds * 1000).toLong())
    }
    private suspend fun refresh() {
        currentCoroutineContext().ensureActive()
        if (closed || !scope.isActive || !owner.owns()) { mutable.value = null; return }
        try {
            val expected = owner.native.current() ?: run { mutable.value = null; return }
            val session = owner.captureLoadState();val request = session.currentRequest ?: run { mutable.value = null; return }
            val remaining = seconds() ?: run { mutable.value = null; return }
            // Feedback/playlist/settings read and original algorithm stay outside
            // Store/entry/native monitors, on the real owner Main task.
            val target = owner.playback.resolveNextWatchSuggestion() ?: run { mutable.value = null; return }
            val hint = DesktopOriginalVideoNextWatchHint(target, remaining, expected, request, session.currentLoadRequestToken)
            var applied = false
            if (!owner.native.admitPlaybackDispatch(expected) {
                if (current(hint)) { mutable.value = hint; applied = true }
            } || !applied) mutable.value = null
        } catch (_: CancellationException) {
            mutable.value = null
            // A replaced source can retire Section.snapshot without retiring this
            // entry worker. Its real parent cancellation must still propagate.
            currentCoroutineContext().ensureActive()
        }
    }
    fun capture(): DesktopOriginalVideoNextWatchHint? {
        val hint = mutable.value ?: return null
        return try {
            hint.takeIf { current(it) && seconds() != null && owner.playback.resolveNextWatchSuggestion() == it.target }
        } catch (_: CancellationException) {
            mutable.value = null
            null // A read-only UI getter cannot borrow a retired source.
        }
    }
    fun play(hint: DesktopOriginalVideoNextWatchHint, caller: Job, callerStillOwned: () -> Boolean): Boolean {
        try {
            fun sourceCurrent() = !caller.isCancelled && callerStillOwned() && current(hint)
            if (!caller.isActive || !sourceCurrent() || seconds() == null ||
                owner.playback.resolveNextWatchSuggestion() != hint.target) return false
            var admitted = false
            if (!owner.native.admitPlaybackDispatch(hint.accepted) { admitted = sourceCurrent() } || !admitted) return false
            val origin = hint.accepted.bootstrapOrigin
            if (origin != null && origin.seed.assembly !== owner) return false
            // Queue selection can change its cursor. The genuine manual provenance
            // keeps source/request/entry lifetime, not the old suggestion/cursor.
            val source = origin?.let { DesktopOriginalManualPlaybackNavigation(it, owner.native, hint.accepted,
                caller, ::sourceCurrent, callerStillOwned) }
            if (source != null && !source.isCurrent()) return false
            return owner.playback.playNextWatchSuggestion(hint.target, desktopManualNavigation = source)
        } catch (_: CancellationException) {
            mutable.value = null
            caller.ensureActive() // Preserve this actual click cancellation.
            return false
        }
    }
    override fun close() { closed = true; worker.cancel(); mutable.value = null }
}
