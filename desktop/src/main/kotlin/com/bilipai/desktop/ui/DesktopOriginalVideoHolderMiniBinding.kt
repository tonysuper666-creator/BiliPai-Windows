package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlinx.coroutines.CancellationException

/** Holder/Fullscreen view of the SAME retained VM, native Section and original
 * Mini binding. Only the physical Root may own mini/navigation presentation
 * flags; every such read/write callback is required and receives this fixed
 * Assembly. No cached playback state, source, player or coroutine is created.
 */
internal class DesktopOriginalVideoHolderMiniBinding(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
    private val mini: DesktopOriginalVideoMiniBinding,
    private val readMiniMode: (DesktopOriginalVideoOwnerAssembly) -> Boolean,
    private val readNavigatingToVideo: (DesktopOriginalVideoOwnerAssembly) -> Boolean,
    private val writeNavigatingToVideo: (DesktopOriginalVideoOwnerAssembly, Boolean) -> Unit,
    private val markLeaving: (DesktopOriginalVideoOwnerAssembly, String, Boolean) -> Unit,
    private val rememberEntrySide: (DesktopOriginalVideoOwnerAssembly, Boolean) -> Unit,
    private val retainUiState: (DesktopOriginalVideoOwnerAssembly, VideoPlaybackUiState.Success) -> Unit,
    private val enterMini: (DesktopOriginalVideoOwnerAssembly, Boolean) -> Unit,
) : DesktopOriginalVideoHolderMini {
    private fun owns() = currentAssembly() === assembly && assembly.owns()
    private fun requireOwned() {
        if (!owns()) throw CancellationException("Original Holder Mini entry retired")
    }
    private fun matchingSuccess(): VideoPlaybackUiState.Success? {
        if (!owns()) return null
        val accepted = assembly.native.current() ?: return null
        return (assembly.playback.uiState.value as? VideoPlaybackUiState.Success)?.takeIf {
            it.info.bvid == accepted.request.bvid && it.info.cid == accepted.request.cid
        }
    }
    override val isActive get() = owns() && mini.isActive
    override val player get() = mini.player.takeIf { owns() && it === assembly.section }
    override val currentBvid get() = if (owns()) mini.currentBvid else null
    override val currentCid get() = if (owns()) mini.currentCid ?: 0L else 0L
    override val currentAid get() = matchingSuccess()?.info?.aid ?: assembly.native.current()
        ?.takeIf { owns() }?.request?.aid ?: 0L
    override val currentTitle get() = matchingSuccess()?.info?.title ?: assembly.native.current()
        ?.takeIf { owns() }?.nativeSource?.source?.title.orEmpty()
    override val duration get() = player?.duration ?: 0L
    override val isMiniMode get() = owns() && readMiniMode(assembly)
    override var isNavigatingToVideo: Boolean
        get() = owns() && readNavigatingToVideo(assembly)
        set(value) { requireOwned(); writeNavigatingToVideo(assembly, value) }

    override fun markLeavingByNavigation(expectedBvid: String, deferPlaybackStop: Boolean) {
        requireOwned()
        val request = assembly.captureLoadState().currentRequest
        val published = matchingSuccess()?.info?.bvid
        if (expectedBvid.isBlank() || (mini.currentBvid != expectedBvid &&
                request?.bvid != expectedBvid && published != expectedBvid))
            throw CancellationException("Original Holder navigation subject replaced")
        markLeaving(assembly, expectedBvid, deferPlaybackStop)
    }
    override fun setVideoInfo(bvid: String, title: String, cover: String, owner: String,
        cid: Long, aid: Long, externalPlayer: DesktopOriginalMpvSectionControl, fromLeft: Boolean) {
        requireOwned()
        val current = matchingSuccess() ?: throw CancellationException("Original Holder Mini metadata unavailable")
        if (externalPlayer !== assembly.section || current.info.bvid != bvid || current.info.cid != cid ||
            current.info.aid != aid || current.info.title != title || current.info.pic != cover || current.info.owner.name != owner)
            throw CancellationException("Original Holder Mini metadata/source replaced")
        mini.syncCurrentVideoInfo(current) // Existing Root SMTC/Library consumer, outside a new gate.
        requireOwned()
        rememberEntrySide(assembly, fromLeft)
    }
    override fun cacheUiState(state: VideoPlaybackUiState.Success) {
        requireOwned()
        val current = matchingSuccess() ?: throw CancellationException("Original Holder state unavailable")
        if (state.info.bvid != current.info.bvid || state.info.cid != current.info.cid || state.info.aid != current.info.aid)
            throw CancellationException("Original Holder retained state subject replaced")
        // The Root retains the original reference/view with this same Assembly;
        // it must not construct a parallel playback cache or mutate another VM.
        retainUiState(assembly, state)
    }
    override fun enterMiniMode(forced: Boolean) {
        requireOwned()
        if (player == null) throw CancellationException("Original Holder Mini source unavailable")
        enterMini(assembly, forced)
    }
}
