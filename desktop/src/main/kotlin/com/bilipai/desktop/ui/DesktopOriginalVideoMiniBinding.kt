package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.VideoTag
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlinx.coroutines.CancellationException

/** Mini/ordinary/PiP are views of ONE retained VM and accepted native lease.
 * Metadata updates go to Root's existing SMTC/Library consumer. Tags already live
 * in that VM's Success; no duplicate Mini cache/state or player is constructed.
 */
internal class DesktopOriginalVideoMiniBinding(
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
    private val onMetadata: (DesktopOriginalVideoOwnerAssembly, VideoPlaybackUiState.Success) -> Unit,
    private val currentEntry: () -> Boolean,
) : DesktopOriginalVideoOwnerMini {
    override var onNavigateNextCallback: (() -> Boolean)? = null
    override var onNavigatePreviousCallback: (() -> Boolean)? = null
    override var onHasNextNavigationCallback: (() -> Boolean)? = null
    override var onHasPreviousNavigationCallback: (() -> Boolean)? = null
    private fun owned() = currentAssembly()?.takeIf { currentEntry() && it.owns() }
    override val currentBvid: String? get() = owned()?.native?.current()?.request?.bvid
    override val currentCid: Long? get() = owned()?.native?.current()?.request?.cid
    override val isActive: Boolean get() = owned()?.native?.current() != null
    override val player: DesktopOriginalMpvSectionControl? get() = owned()?.let { if (it.native.current() != null) it.section else null }
    override fun syncCurrentVideoInfo(state: VideoPlaybackUiState.Success) {
        val assembly = owned() ?: throw CancellationException("Original Mini owner retired")
        // The original VM calls this immediately after publishing _uiState. Its
        // public stateIn projection can still contain Loading on this continuation.
        val current = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success
        if (current?.info?.bvid != state.info.bvid || current.info.cid != state.info.cid)
            throw CancellationException("Original Mini metadata subject replaced")
        val accepted = assembly.native.current()
        if (accepted?.request?.bvid != state.info.bvid || accepted.request.cid != state.info.cid)
            throw CancellationException("Original Mini accepted source replaced")
        onMetadata(assembly, state)
    }
    override fun updateCachedVideoTags(bvid: String, tags: List<VideoTag>) {
        val assembly = owned() ?: throw CancellationException("Original Mini tags owner retired")
        val current = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success
        if (current?.info?.bvid != bvid || current.videoTags != tags)
            throw CancellationException("Original Mini tag snapshot replaced")
        // Actual full VM published these exact tags before this original callback.
        // SMTC/Library projection consumes the same Success, without another cache.
        onMetadata(assembly, current)
    }
}
