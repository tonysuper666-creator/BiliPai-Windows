package com.bilipai.desktop.ui

import com.android.purebilibili.feature.live.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Retired Session admission rejects before action; action failures remain visible. */
internal fun desktopLiveAdmission(repository: DesktopRepository, epoch: Long,
    current: () -> Boolean, action: () -> Unit): Boolean {
    var enteredAction = false
    var ownerRejected = false
    return try {
        repository.withPrimaryPlaybackAdmission(epoch, {
            current().also { if (!it) ownerRejected = true }
        }) { enteredAction = true; action(); true }
    } catch (rejected: BiliApiException) {
        if (!enteredAction && rejected.apiCode == -101 &&
            (repository.sessionEpoch != epoch || ownerRejected)) false else throw rejected
    }
}

/** Existing primary session and actual caller; network work is outside admission. */
internal interface DesktopLiveRecoveryPorts {
    // Production always supplies the original captured primary epoch; synthetic ports have no account authority.
    val accountEpoch: Long? get() = null
    fun isAccountCurrent(): Boolean
    fun admit(action: () -> Unit): Boolean
    // Existing isolated fake ports keep their non-PiP behavior; production factory requires real Root callbacks.
    fun isMiniLiveMode(): Boolean = false
    fun dismissMiniLive(eof: PlayerNativeEof) {}
    suspend fun reload(room: LiveRoomDetails, quality: Int, onlyAudio: Boolean, current: () -> Boolean): LivePlaybackInfo
}

internal fun desktopLiveRecoveryPorts(repository: DesktopRepository, media: DesktopMediaRepository,
    epoch: Long, miniLiveMode: () -> Boolean, dismissMini: (Long, PlayerNativeEof) -> Unit): DesktopLiveRecoveryPorts = object : DesktopLiveRecoveryPorts {
    override val accountEpoch = epoch
    override fun isAccountCurrent() = repository.sessionEpoch == epoch
    override fun isMiniLiveMode() = miniLiveMode.invoke()
    override fun dismissMiniLive(eof: PlayerNativeEof) = dismissMini.invoke(epoch, eof)
    override fun admit(action: () -> Unit): Boolean =
        desktopLiveAdmission(repository, epoch, ::isAccountCurrent, action)
    override suspend fun reload(room: LiveRoomDetails, quality: Int, onlyAudio: Boolean, current: () -> Boolean) =
        media.livePlaybackInfo(room, quality, onlyAudio, epoch, current)
}

/** Same original account/Room owner, repeated on the actor before each native command.
 * Initial load/install and recovery handoff stay inside their existing account admission.
 * The publication is a source receipt; it never borrows the completed request Job. */
internal fun DesktopLivePageMemory.nativePlaybackSource(info: LivePlaybackInfo,
    roomId: Long, ports: DesktopLiveRecoveryPorts): com.bilipai.desktop.player.PlaybackSource {
    val initialized = requireNotNull(player)
    require(roomId > 0)
    val publication = object : DesktopNativePlaybackPublication {
        private fun current(expected: OwnedPlaybackSourceSnapshot) =
            scope.isActive && stream === info && liveSourceSnapshot === expected &&
                recoveryPorts === ports && sourceVersion == expected.sourceVersion &&
                expected.source.nativePublication === this &&
                expected.source.primaryAccountEpoch == ports.accountEpoch &&
                room?.roomId == roomId && room?.isLive == true && room?.locked == false

        override fun admit(command: () -> Unit): Boolean {
            var accepted = false
            ports.admit accountAdmission@{
                val expected = liveSourceSnapshot ?: return@accountAdmission
                if (!current(expected)) return@accountAdmission
                initialized.admitSourceSnapshot(expected) {
                    if (current(expected)) { command(); accepted = true }
                }
            }
            return accepted
        }
    }
    return info.source.toNativePlayback().copy(primaryAccountEpoch = ports.accountEpoch, nativePublication = publication)
}

internal fun DesktopLivePageMemory.installLivePlayback(info: LivePlaybackInfo,
    snapshot: OwnedPlaybackSourceSnapshot, ports: DesktopLiveRecoveryPorts, resetBudget: Boolean = true) {
    stream = info; sourceVersion = snapshot.sourceVersion; liveSourceSnapshot = snapshot
    recoveryPorts = ports; handledLiveFailure = null; handledLiveEof = null
    if (resetBudget) {
        remainingLiveReloadAttempts = MAX_PLAYBACK_RELOAD_ATTEMPTS
        remainingLiveNativeReprepareAttempts = MAX_PLAYBACK_RELOAD_ATTEMPTS
    }
    if (recoveryObserver == null) recoveryObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        player?.state?.collect { state ->
            val binding = captureLiveSourceBinding() ?: return@collect
            when {
                state.failure != null -> binding.recover(state.failure)
                state.nativeEof != null -> binding.recoverUnexpectedEnd(state.nativeEof)
                else -> binding.onPlaybackStarted(state)
            }
        }
    }
}

internal fun DesktopLivePageMemory.captureLiveSourceBinding(): DesktopLiveSourceBinding? {
    val info = stream ?: return null
    val snapshot = liveSourceSnapshot ?: return null
    val currentRoom = room ?: return null
    val ports = recoveryPorts ?: return null
    return DesktopLiveSourceBinding(this, requireNotNull(player), info, snapshot, currentRoom, ports)
        .takeIf { it.current() }
}

/** A captured UI/source receipt, not another playback state or resolver. */
internal class DesktopLiveSourceBinding(
    private val memory: DesktopLivePageMemory, private val player: MpvPlayer,
    val info: LivePlaybackInfo, private val source: OwnedPlaybackSourceSnapshot,
    private val room: LiveRoomDetails, private val ports: DesktopLiveRecoveryPorts,
) {
    private fun memoryCurrent() = memory.scope.isActive && memory.stream === info &&
        memory.liveSourceSnapshot === source && memory.recoveryPorts === ports &&
        memory.sourceVersion == source.sourceVersion && memory.room?.roomId == room.roomId &&
        memory.room?.isLive == true && memory.room?.locked == false

    fun current() = memoryCurrent() && ports.isAccountCurrent() && player.ownsSourceSnapshot(source)

    private fun selected(candidateIndex: Int, urlIndex: Int, fresh: LivePlaybackInfo = info): LivePlaybackInfo? {
        val candidate = fresh.resolvedPlayback?.candidates?.getOrNull(candidateIndex) ?: return null
        val url = candidate.urls.getOrNull(urlIndex) ?: return null
        return fresh.copy(source = fresh.source.copy(videoUrl = url, quality = candidate.currentQuality),
            qualities = candidate.qualityList.map { MediaQuality(it.qn, it.desc) },
            backupUrls = candidate.urls.drop(urlIndex + 1), candidateIndex = candidateIndex, urlIndex = urlIndex)
    }

    private fun commit(next: LivePlaybackInfo, failure: PlayerFailure?, resetBudget: Boolean, caller: Job? = null,
        presentationCurrent: () -> Boolean = { true }, cancelPending: Boolean = false,
        nativeReprepare: Boolean = false, softwareFallback: Boolean = false, eof: PlayerNativeEof? = null): Boolean {
        var accepted = false
        ports.admit {
            if (!memoryCurrent() || caller?.isActive == false || !presentationCurrent()) return@admit
            player.admitSourceSnapshot(source) {
                if (!memoryCurrent() || caller?.isActive == false || !presentationCurrent() ||
                    (failure != null && player.state.value.failure !== failure) ||
                    (eof != null && !unexpectedEndCurrent(eof)) ||
                    (nativeReprepare && memory.remainingLiveNativeReprepareAttempts <= 0)) return@admitSourceSnapshot
                if (cancelPending) memory.playJob?.takeIf { it !== caller }?.cancel()
                // Live source replacement prepares at the live edge and retains
                // the user's current play/pause intent, like original prepare().
                if (player.recoverSource(source.sourceVersion, memory.nativePlaybackSource(next, room.roomId, ports), positionSeconds = 0.0,
                    paused = if (eof == null) player.state.value.paused else !eof.playWhenReady, forceSoftwareDecoding = softwareFallback,
                    expectedFailureAttemptId = failure?.attemptId, expectedNativeEof = eof)) {
                    if (nativeReprepare) memory.remainingLiveNativeReprepareAttempts -= 1
                    memory.installLivePlayback(next, requireNotNull(player.currentSourceSnapshot()), ports, resetBudget)
                    memory.loaded = true; memory.error = null; accepted = true
                }
            }
        }
        return accepted
    }

    fun switch(candidateIndex: Int, urlIndex: Int, presentationCurrent: () -> Boolean): Boolean {
        if (!current() || !presentationCurrent()) return false
        val next = selected(candidateIndex, urlIndex) ?: return false
        return commit(next, null, resetBudget = true, presentationCurrent = presentationCurrent, cancelPending = true)
    }

    fun refresh(next: LivePlaybackInfo, caller: Job): Boolean =
        commit(next, null, resetBudget = true, caller = caller, cancelPending = true)

    /** Original onIsPlayingChanged(true) budget reset, after actual output on this source. */
    fun onPlaybackStarted(state: PlayerState) {
        fun outputStarted() = state.ready && !state.loading && !state.ended && !state.paused &&
            !state.pausedForCache && state.nativePaused == false && state.failure == null && state.error == null &&
            (state.firstVideoFrameReady || state.audioCodec != null && state.positionSeconds > 0.0)
        if (!current() || !outputStarted()) return
        ports.admit {
            if (!memoryCurrent()) return@admit
            player.admitSourceSnapshot(source) {
                if (memoryCurrent() && player.state.value === state && outputStarted()) {
                    memory.remainingLiveReloadAttempts = MAX_PLAYBACK_RELOAD_ATTEMPTS
                    memory.remainingLiveNativeReprepareAttempts = MAX_PLAYBACK_RELOAD_ATTEMPTS
                }
            }
        }
    }

    private fun unexpectedEndCurrent(eof: PlayerNativeEof): Boolean =
        current() && player.ownsNativeEof(eof) && shouldRecoverUnexpectedLiveEnd(
            DesktopOriginalPlaybackStates.STATE_ENDED, eof.playWhenReady,
            memory.room?.isLive == true && memory.room?.locked == false, ports.isMiniLiveMode())

    fun recoverUnexpectedEnd(eof: PlayerNativeEof) {
        if (!current() || !player.ownsNativeEof(eof) || memory.handledLiveEof === eof) return
        var closeMini = false
        var recover = false
        ports.admit {
            if (!memoryCurrent()) return@admit
            player.admitSourceSnapshot(source) {
                if (!memoryCurrent() || !player.ownsNativeEof(eof) || memory.handledLiveEof === eof) return@admitSourceSnapshot
                memory.handledLiveEof = eof
                closeMini = ports.isMiniLiveMode()
                recover = !closeMini && unexpectedEndCurrent(eof)
            }
        }
        // No window callback inside the source/account locks. Root re-admits the exact EOF before closing.
        if (closeMini && current() && player.ownsNativeEof(eof) && ports.isMiniLiveMode()) ports.dismissMiniLive(eof)
        else if (recover) advanceAfterTerminal(null, eof)
    }

    fun recover(failure: PlayerFailure) {
        if (!current() || failure.sourceVersion != source.sourceVersion || player.state.value.failure !== failure ||
            player.state.value.ended || memory.handledLiveFailure === failure) return
        if (resolveLivePlaybackErrorRecovery(DesktopLiveFailureCode.from(failure), failure.httpStatus) !=
            LivePlaybackErrorRecovery.TRY_NEXT_SOURCE) return
        memory.handledLiveFailure = failure
        val state = player.state.value
        val decoderFailure = failure.kind == PlayerFailureKind.DECODER
        val audioInitializationFailure = failure.kind == PlayerFailureKind.AUDIO_OUTPUT && failure.nativeCode == -14
        if (memory.remainingLiveNativeReprepareAttempts > 0 && (decoderFailure || audioInitializationFailure)) {
            // A terminal native decoder/AO-init failure can be transient. Keep the
            // user's selected candidate and URL for one new native attempt first.
            // Software fallback changes only the existing per-source video guard;
            // it never changes the persistent hardware preference, audio device,
            // exclusive mode, only-audio selection, room, requested quality or URL.
            val softwareFallback = decoderFailure && !memory.onlyAudio && !state.audioOnly &&
                state.hardwareDecodeEnabled && !state.softwareDecodingRequested
            if (commit(info, failure, resetBudget = false, nativeReprepare = true,
                    softwareFallback = softwareFallback)) return
            if (!current() || player.state.value.failure !== failure) return
        }
        advanceAfterTerminal(failure, null)
    }

    private fun advanceAfterTerminal(failure: PlayerFailure?, eof: PlayerNativeEof?) {
        fun eventCurrent(): Boolean = current() && when {
            failure != null -> player.state.value.failure === failure
            eof != null -> unexpectedEndCurrent(eof)
            else -> false
        }
        if (!eventCurrent()) return
        val playback = info.resolvedPlayback ?: return
        when (val next = advanceLivePlayback(playback, info.candidateIndex, info.urlIndex)) {
            is LiveAdvanceResult.NextSource -> selected(next.candidateIndex, next.urlIndex)?.let {
                commit(it, failure, resetBudget = false, eof = eof)
            }
            is LiveAdvanceResult.ReloadCurrentQuality -> {
                if (memory.remainingLiveReloadAttempts <= 0) {
                    ports.admit { if (eventCurrent()) {
                        memory.error = "直播流恢复失败，请稍后重试"
                    } }
                    return
                }
                memory.launchRequest {
                    val caller = currentCoroutineContext().job
                    var admitted = false
                    ports.admit {
                        if (!memoryCurrent() || !caller.isActive || memory.playJob !== caller) return@admit
                        player.admitSourceSnapshot(source) {
                            if (!eventCurrent() || !caller.isActive || memory.playJob !== caller ||
                                memory.remainingLiveReloadAttempts <= 0) return@admitSourceSnapshot
                            memory.remainingLiveReloadAttempts -= 1
                            memory.opening = true; admitted = true
                        }
                    }
                    if (!admitted) return@launchRequest
                    try {
                        val fresh = ports.reload(room, next.qualityQn, memory.onlyAudio) {
                            caller.isActive && memory.playJob === caller && eventCurrent()
                        }
                        currentCoroutineContext().ensureActive()
                        commit(fresh, failure, resetBudget = false, caller = caller, eof = eof)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failed: Exception) {
                        ports.admit { if (caller.isActive && memory.playJob === caller && eventCurrent()) {
                            memory.error = failed.message ?: "直播流恢复失败，请稍后重试"
                        } }
                    } finally { if (memory.playJob === caller) memory.opening = false }
                }
            }
        }
    }
}

/** Only the actual mounted selector can submit its captured source callback. */
internal class DesktopLiveSourceSelection(val binding: DesktopLiveSourceBinding) : AutoCloseable {
    private var alive = true
    fun current() = alive && binding.current()
    fun switch(candidate: Int, url: Int): Boolean = current() && binding.switch(candidate, url, ::current)
    override fun close() { alive = false }
}
