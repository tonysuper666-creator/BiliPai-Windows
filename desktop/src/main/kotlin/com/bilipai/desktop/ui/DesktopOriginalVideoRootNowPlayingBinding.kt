package com.bilipai.desktop.ui

import com.bilipai.desktop.audio.ListenAudioSession
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.CancellationException

/** Read/command views of the actual original queue/accepted native lease and
 * the existing SID/AU Listen owner. No copied playback state or resume default.
 * The original UI session belongs to this retained Root epoch, not a leaf. */
internal class DesktopOriginalVideoRootNowPlayingBinding(
    private val root: DesktopHomeRetainedRoot,
    private val repository: DesktopRepository,
    private val shell: DesktopOriginalVideoShellOwner,
    private val player: MpvPlayer,
    private val listen: ListenAudioSession?,
    private val rootAlive: () -> Boolean,
) {
    private fun ownsRoot() = rootAlive() && root.isCurrentOwner()
    val session = DesktopOriginalNowPlayingSessionView(::ownsRoot, root.entry.gate::commit)
    private val ordinary = DesktopOriginalOrdinaryNowPlayingPort(shell.slot.assemblies,
        shell.slot::currentAssembly, shell.playlist, player.state, ::ownsRoot,
        admitCommand = { owner, expected, action ->
            ownsRoot() && shell.factoryFor(owner).withPresentationAdmission(owner, expected, action)
        }, navigationScope = root.entry.gate.scope)
    private val songs = listen?.let {
        DesktopOriginalListenNowPlayingPort(it, repository, root.entry.gate.scope, ::ownsRoot)
    }
    val owner = DesktopOriginalNowPlayingPriorityPort(ordinary, songs)
    val binding = DesktopOriginalAudioNowPlayingBinding(owner, session::publishBarOverlayVisible)

    /** Called with the exact composed snapshot after the Binding checked its
     * identity. Store/entry admission permits a short real native read only. */
    fun positionMs(expected: DesktopOriginalNowPlayingSnapshot): Long? {
        if (!ownsRoot() || !owner.owns(expected)) return null
        var position: Long? = null
        if (expected.owner === ordinary) {
            val assembly = shell.slot.currentAssembly() ?: return null
            val publication = assembly.native.current()?.takeIf {
                it.sourceVersion == expected.sourceVersion && it.request.bvid == expected.item.bvid &&
                    it.request.cid == expected.item.cid
            } ?: return null
            assembly.native.admitPlaybackDispatch(publication) {
                if (ordinary.owns(expected) && ownsRoot()) {
                    val actual = player.state.value.positionSeconds
                    if (actual.isFinite() && actual >= 0.0) position = (actual * 1000.0).toLong()
                }
            }
        } else if (expected.owner === songs) {
            val actualListen = listen ?: return null
            try {
                repository.withPrimaryPlaybackAdmission(root.capturedEpoch, ::ownsRoot) {
                    root.entry.gate.commit {
                        if (songs.owns(expected) && actualListen.ownedPlaybackSourceVersion == expected.sourceVersion) {
                            val actual = actualListen.player.state.value.positionSeconds
                            if (actual.isFinite() && actual >= 0.0) position = (actual * 1000.0).toLong()
                        }
                    }
                }
            } catch (_: CancellationException) { return null }
        }
        return position?.takeIf { ownsRoot() && owner.owns(expected) }
    }

    fun dismiss() {
        val expected = owner.current() ?: return
        if (owner.dismiss(expected) && ownsRoot()) session.dismiss()
    }
}
