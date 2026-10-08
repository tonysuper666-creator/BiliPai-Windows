package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.audio.player.AudioNowPlayingSession
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import com.bilipai.desktop.audio.ListenAudioSession
import com.bilipai.desktop.audio.nativeMusicSourceForListenItem
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/** A read/command capability, not a second queue, native player or state actor.
 * Every control captures this exact source/subject capability in composition. */
internal class DesktopOriginalNowPlayingSnapshot(
    val owner: DesktopOriginalNowPlayingOwnerPort,
    val item: PlaylistItem,
    val active: Boolean,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val canToggle: Boolean,
    val sourceVersion: Long?,
    internal val identity: Any,
)

internal interface DesktopOriginalNowPlayingOwnerPort {
    @Composable fun observe(): DesktopOriginalNowPlayingSnapshot?
    fun current(): DesktopOriginalNowPlayingSnapshot?
    fun owns(expected: DesktopOriginalNowPlayingSnapshot): Boolean
    /** False retains the original navigation-to-audio fallback. */
    fun toggle(expected: DesktopOriginalNowPlayingSnapshot): Boolean
    fun next(expected: DesktopOriginalNowPlayingSnapshot)
    fun previous(expected: DesktopOriginalNowPlayingSnapshot)
    fun dismiss(expected: DesktopOriginalNowPlayingSnapshot): Boolean
}

/** A thin view of the single original two-flow UI presentation object.
 * Root clears it when retiring the actual ordinary owner, before installing a
 * replacement. The view is not another actor and owns no media or persistence. */
internal class DesktopOriginalNowPlayingSessionView(
    private val owns: () -> Boolean,
    private val commit: ((() -> Unit) -> Boolean),
) : DesktopOriginalAudioSessionPort {
    val active: StateFlow<Boolean> get() = AudioNowPlayingSession.active
    val barOverlayVisible: StateFlow<Boolean> get() = AudioNowPlayingSession.barOverlayVisible
    override fun markListening() { commit { if (owns()) AudioNowPlayingSession.markListening() } }
    override fun dismiss() { commit { if (owns()) AudioNowPlayingSession.dismiss() } }
    fun publishBarOverlayVisible(value: Boolean) {
        commit { if (owns()) AudioNowPlayingSession.publishBarOverlayVisible(value) }
    }
}

/** Ordinary BV audio uses the installed WHOLE original VM, logical subject,
 * NativeOwner and original playlist. No Listen method is reachable here.
 * admitCommand must be Factory.withPresentationAdmission(assembly, expected):
 * Store -> entry validates NativeOwner briefly, releases its native lock, then
 * runs action. Do not run VM/queue writes inside NativeOwner's native-lock gate.
 */
internal class DesktopOriginalOrdinaryNowPlayingPort(
    private val assemblies: StateFlow<DesktopOriginalVideoOwnerAssembly?>,
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
    private val playlist: DesktopOriginalVideoOwnerPlaylist,
    private val native: StateFlow<PlayerState>,
    private val rootOwns: () -> Boolean,
    private val admitCommand: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
    private val navigationScope: CoroutineScope?,
) : DesktopOriginalNowPlayingOwnerPort {
    // Preserve the original six-argument/trailing-lambda API for legacy callers.
    constructor(assemblies: StateFlow<DesktopOriginalVideoOwnerAssembly?>,
        currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
        playlist: DesktopOriginalVideoOwnerPlaylist, native: StateFlow<PlayerState>,
        rootOwns: () -> Boolean,
        admitCommand: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
    ) : this(assemblies, currentAssembly, playlist, native, rootOwns, admitCommand, null)
    private class Identity(val assembly: DesktopOriginalVideoOwnerAssembly,
        val subject: VideoSubjectSnapshot, val publication: DesktopOriginalVideoAcceptedPublication,
        val queueItem: PlaylistItem, val queueIndex: Int)

    private fun snapshot(): DesktopOriginalNowPlayingSnapshot? {
        if (!rootOwns()) return null
        val assembly = currentAssembly()?.takeIf { it === assemblies.value && it.owns() } ?: return null
        require(assembly.environment.playlist === playlist) { "Now-playing must borrow the same original queue" }
        require(assembly.section.nativePlayer.state === native) { "Now-playing must borrow the same native flow" }
        val subject = assembly.playback.subjectSnapshot.value ?: return null
        val accepted = assembly.native.current() ?: return null
        val success = assembly.playback.uiState.value as? VideoPlaybackUiState.Success ?: return null
        val index = playlist.currentIndex.value
        val item = playlist.playlist.value.getOrNull(index) ?: return null
        if (item.bvid != accepted.request.bvid || item.cid < 0L || accepted.request.cid <= 0L ||
            (item.cid > 0L && item.cid != accepted.request.cid) ||
            subject.bvid != accepted.request.bvid || subject.cid != accepted.request.cid ||
            success.info.bvid != accepted.request.bvid) return null
        val state = native.value
        val token = Identity(assembly, subject, accepted, item, index)
        // Original external rows may legitimately have CID 0. Navigation uses
        // the CID actually accepted for this very subject; the queue is unchanged.
        val resolvedItem = if (item.cid == 0L) item.copy(cid=accepted.request.cid) else item
        val value = DesktopOriginalNowPlayingSnapshot(this, resolvedItem, AudioNowPlayingSession.active.value,
            state.ready && !(state.nativePaused ?: state.paused) && !state.ended && !state.loading &&
                !state.pausedForCache && state.error == null,
            state.speed.toFloat(), state.ready && state.durationSeconds > 0.0 && state.error == null, accepted.sourceVersion, token)
        return value.takeIf(::owns)
    }

    @Composable override fun observe(): DesktopOriginalNowPlayingSnapshot? {
        val assembly by assemblies.collectAsState()
        val queue by playlist.playlist.collectAsState()
        val index by playlist.currentIndex.collectAsState()
        val state by native.collectAsState()
        val active by AudioNowPlayingSession.active.collectAsState()
        if (assembly != null) {
            val ui by assembly!!.playback.uiState.collectAsState()
            val subject by assembly!!.playback.subjectSnapshot.collectAsState()
            // Read these actual dependencies; snapshot is guarded again below.
            @Suppress("UNUSED_VARIABLE") val dependencies = arrayOf(ui, subject, queue, index, state, active)
        }
        return snapshot()
    }
    override fun current() = snapshot()
    override fun owns(expected: DesktopOriginalNowPlayingSnapshot): Boolean {
        if (expected.owner !== this || !rootOwns()) return false
        val token = expected.identity as? Identity ?: return false
        val assembly = token.assembly
        return currentAssembly() === assembly && assemblies.value === assembly && assembly.owns() &&
            assembly.playback.subjectSnapshot.value === token.subject &&
            playlist.currentIndex.value == token.queueIndex &&
            playlist.playlist.value.getOrNull(token.queueIndex) == token.queueItem &&
            assembly.native.isCurrent(token.publication)
    }
    private fun command(expected: DesktopOriginalNowPlayingSnapshot, action: (DesktopOriginalVideoOwnerAssembly) -> Unit): Boolean {
        if (!owns(expected)) return false
        val token = expected.identity as Identity
        var applied = false
        val admitted = admitCommand(token.assembly, token.publication) {
            if (owns(expected)) { action(token.assembly); applied = true }
        }
        return admitted && applied
    }
    override fun toggle(expected: DesktopOriginalNowPlayingSnapshot): Boolean {
        if (!expected.canToggle) return false
        return command(expected) { assembly ->
            if (assembly.section.playWhenReady) assembly.section.pause() else assembly.section.play()
        }
    }
    override fun next(expected: DesktopOriginalNowPlayingSnapshot) { navigatePlaylistFromClick(expected, true) }
    override fun previous(expected: DesktopOriginalNowPlayingSnapshot) { navigatePlaylistFromClick(expected, false) }
    private fun navigatePlaylistFromClick(expected: DesktopOriginalNowPlayingSnapshot, forward: Boolean) {
        if (!owns(expected)) return
        val scope = navigationScope
        if (scope == null) {
            // Exact old compatibility branch; actual Root always supplies its scope.
            command(expected) {
                if (forward) it.playback.playNextAudioModeTrack() else it.playback.playPreviousAudioModeTrack()
            }
            return
        }
        val token = expected.identity as Identity
        launchDesktopOriginalManualAudioNavigation(token.assembly, token.publication, scope, forward,
            clickCurrent = { owns(expected) }, callerStillOwned = { rootOwns() && scope.isActive })
    }
    override fun dismiss(expected: DesktopOriginalNowPlayingSnapshot): Boolean = command(expected) {
        if (it.section.isPlaying) it.section.pause()
        it.playback.setAudioMode(false)
        AudioNowPlayingSession.dismiss()
    }
}

/** Legacy SID/AU remains the same retained native music actor. Its constructor
 * is also used by the preserved old Listen-only overload during serial rollout. */
internal class DesktopOriginalListenNowPlayingPort(
    private val listen: ListenAudioSession,
    private val repository: DesktopRepository,
    private val scope: CoroutineScope,
    private val rootOwns: () -> Boolean,
) : DesktopOriginalNowPlayingOwnerPort {
    private val epoch = repository.sessionEpoch
    private class Identity(val item: PlaylistItem, val sourceVersion: Long?)
    private fun rootCurrent() = scope.isActive && rootOwns() && repository.sessionEpoch == epoch
    private fun snapshot(): DesktopOriginalNowPlayingSnapshot? {
        if (!rootCurrent()) return null
        val state = listen.state.value
        val item = state.current ?: return null
        val source = listen.ownedPlaybackSourceVersion
        val native = listen.player.state.value
        val result = DesktopOriginalNowPlayingSnapshot(this,item,true,
            state.active && source != null && !(native.nativePaused ?: native.paused) && !native.ended &&
                native.ready && !native.loading && !native.pausedForCache && native.error == null,
            native.speed.toFloat(), source != null && native.ready && native.durationSeconds > 0.0,
            source, Identity(item,source))
        return result.takeIf(::owns)
    }
    @Composable override fun observe(): DesktopOriginalNowPlayingSnapshot? {
        val listenState by listen.state.collectAsState()
        val nativeState by listen.player.state.collectAsState()
        @Suppress("UNUSED_VARIABLE") val dependencies = arrayOf(listenState,nativeState)
        return snapshot()
    }
    override fun current() = snapshot()
    override fun owns(expected: DesktopOriginalNowPlayingSnapshot): Boolean {
        if (expected.owner !== this || !rootCurrent()) return false
        val token = expected.identity as? Identity ?: return false
        return listen.state.value.current == token.item && listen.ownedPlaybackSourceVersion == token.sourceVersion
    }
    override fun toggle(expected: DesktopOriginalNowPlayingSnapshot): Boolean {
        if (!owns(expected) || !expected.canToggle) return false
        listen.togglePause(); return owns(expected)
    }
    override fun next(expected: DesktopOriginalNowPlayingSnapshot) { if (owns(expected)) listen.next() }
    override fun previous(expected: DesktopOriginalNowPlayingSnapshot) { if (owns(expected)) listen.previous() }
    override fun dismiss(expected: DesktopOriginalNowPlayingSnapshot): Boolean {
        if (!owns(expected)) return false
        if (expected.isPlaying) listen.pause()
        return owns(expected)
    }
}

/** Only the actual, native-owned AU song outranks the original BV owner. A saved
 * Listen BV queue or an unowned/stopped AU row must not take over ordinary BV.
 * This projection has no background Job, mutable flow, persistence or player. */
internal class DesktopOriginalNowPlayingPriorityPort(
    private val ordinary: DesktopOriginalNowPlayingOwnerPort,
    private val listen: DesktopOriginalNowPlayingOwnerPort?,
) : DesktopOriginalNowPlayingOwnerPort {
    private fun choose(ordinaryValue: DesktopOriginalNowPlayingSnapshot?, listenValue: DesktopOriginalNowPlayingSnapshot?): DesktopOriginalNowPlayingSnapshot? {
        val song = listenValue?.takeIf { listen?.owns(it) == true && it.sourceVersion != null &&
            nativeMusicSourceForListenItem(it.item) is MusicPlaybackSource.AudioSong }
        return song ?: ordinaryValue?.takeIf { ordinary.owns(it) }
    }
    @Composable override fun observe() = choose(ordinary.observe(),listen?.observe())
    override fun current() = choose(ordinary.current(),listen?.current())
    override fun owns(expected: DesktopOriginalNowPlayingSnapshot): Boolean =
        current()?.let { it.owner === expected.owner && it.item == expected.item && expected.owner.owns(expected) } == true
    override fun toggle(expected: DesktopOriginalNowPlayingSnapshot) = owns(expected) && expected.owner.toggle(expected)
    override fun next(expected: DesktopOriginalNowPlayingSnapshot) { if (owns(expected)) expected.owner.next(expected) }
    override fun previous(expected: DesktopOriginalNowPlayingSnapshot) { if (owns(expected)) expected.owner.previous(expected) }
    override fun dismiss(expected: DesktopOriginalNowPlayingSnapshot) = owns(expected) && expected.owner.dismiss(expected)
}
