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
    fun isAccountCurrent(): Boolean
    fun admit(action: () -> Unit): Boolean
    suspend fun reload(room: LiveRoomDetails, quality: Int, onlyAudio: Boolean, current: () -> Boolean): LivePlaybackInfo
}

internal fun desktopLiveRecoveryPorts(repository: DesktopRepository, media: DesktopMediaRepository,
    epoch: Long): DesktopLiveRecoveryPorts = object : DesktopLiveRecoveryPorts {
    override fun isAccountCurrent() = repository.sessionEpoch == epoch
    override fun admit(action: () -> Unit): Boolean =
        desktopLiveAdmission(repository, epoch, ::isAccountCurrent, action)
    override suspend fun reload(room: LiveRoomDetails, quality: Int, onlyAudio: Boolean, current: () -> Boolean) =
        media.livePlaybackInfo(room, quality, onlyAudio, epoch, current)
}

internal fun DesktopLivePageMemory.installLivePlayback(info: LivePlaybackInfo,
    snapshot: OwnedPlaybackSourceSnapshot, ports: DesktopLiveRecoveryPorts, resetBudget: Boolean = true) {
    stream = info; sourceVersion = snapshot.sourceVersion; liveSourceSnapshot = snapshot
    recoveryPorts = ports; handledLiveFailure = null
    if (resetBudget) remainingLiveReloadAttempts = MAX_PLAYBACK_RELOAD_ATTEMPTS
    if (recoveryObserver == null) recoveryObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        player?.state?.collect { state ->
            val binding = captureLiveSourceBinding() ?: return@collect
            state.failure?.let(binding::recover) ?: binding.onPlaybackStarted(state)
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
        presentationCurrent: () -> Boolean = { true }, cancelPending: Boolean = false): Boolean {
        var accepted = false
        ports.admit {
            if (!memoryCurrent() || caller?.isActive == false || !presentationCurrent()) return@admit
            player.admitSourceSnapshot(source) {
                if (!memoryCurrent() || caller?.isActive == false || !presentationCurrent() ||
                    (failure != null && player.state.value.failure !== failure)) return@admitSourceSnapshot
                if (cancelPending) memory.playJob?.takeIf { it !== caller }?.cancel()
                // Live source replacement prepares at the live edge and retains
                // the user's current play/pause intent, like original prepare().
                if (player.recoverSource(source.sourceVersion, next.source.toNativePlayback(), positionSeconds = 0.0,
                    paused = player.state.value.paused, expectedFailureAttemptId = failure?.attemptId)) {
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
                if (memoryCurrent() && player.state.value === state && outputStarted())
                    memory.remainingLiveReloadAttempts = MAX_PLAYBACK_RELOAD_ATTEMPTS
            }
        }
    }

    fun recover(failure: PlayerFailure) {
        if (!current() || failure.sourceVersion != source.sourceVersion || player.state.value.failure !== failure ||
            player.state.value.ended || memory.handledLiveFailure === failure) return
        if (resolveLivePlaybackErrorRecovery(DesktopLiveFailureCode.from(failure), failure.httpStatus) !=
            LivePlaybackErrorRecovery.TRY_NEXT_SOURCE) return
        memory.handledLiveFailure = failure
        val playback = info.resolvedPlayback ?: return
        when (val next = advanceLivePlayback(playback, info.candidateIndex, info.urlIndex)) {
            is LiveAdvanceResult.NextSource -> selected(next.candidateIndex, next.urlIndex)?.let {
                commit(it, failure, resetBudget = false)
            }
            is LiveAdvanceResult.ReloadCurrentQuality -> {
                if (memory.remainingLiveReloadAttempts <= 0) {
                    ports.admit { if (memoryCurrent() && player.ownsSourceSnapshot(source) && player.state.value.failure === failure) {
                        memory.error = "直播流恢复失败，请稍后重试"
                    } }
                    return
                }
                memory.launchRequest {
                    val caller = currentCoroutineContext().job
                    if (!current()) return@launchRequest
                    memory.remainingLiveReloadAttempts -= 1
                    memory.opening = true
                    try {
                        val fresh = ports.reload(room, next.qualityQn, memory.onlyAudio) {
                            caller.isActive && current() && player.state.value.failure === failure
                        }
                        currentCoroutineContext().ensureActive()
                        commit(fresh, failure, resetBudget = false, caller = caller)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failed: Exception) {
                        ports.admit { if (caller.isActive && memoryCurrent() && player.ownsSourceSnapshot(source) && player.state.value.failure === failure) {
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
