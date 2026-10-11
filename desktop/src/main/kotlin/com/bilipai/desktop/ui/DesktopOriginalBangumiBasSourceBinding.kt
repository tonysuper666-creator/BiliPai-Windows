package com.bilipai.desktop.ui

import com.android.purebilibili.feature.bangumi.BangumiPlayerState
import com.bilipai.desktop.danmaku.DanmakuOverlay
import java.awt.EventQueue
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Retained BAS view of the original PGC Entry's same owner/presenter and global
 * Overlay registry. All ownership, Success, route-revision and Store/Entry/native
 * admission are borrowed from the actual Entry; no scope, source or authority is
 * created here. Entry constructs this once and its original BAS methods delegate.
 * Providers are read by the original methods, never during construction. */
internal class DesktopOriginalBangumiBasSourceBinding(
    private val owner: DesktopOriginalVideoOwnerAssembly,
    private val native: DesktopOriginalBangumiNativePresenter,
    private val player: DesktopOriginalMpvSectionControl,
    private val overlay: DanmakuOverlay,
    private val success: () -> BangumiPlayerState.Success?,
    private val owns: () -> Boolean,
    private val navigationRevision: () -> Long,
    private val isPresentationCurrent: (DesktopOriginalVideoAcceptedPublication) -> Boolean,
    private val withPresentationAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
) {
    internal val basNavigationRevision: Long
        get() = navigationRevision()
    internal fun isBasPresenterCurrent(): Boolean = owner.playback.isDesktopBangumiPresenterCurrent(native)

    // Called under Overlay.requestLock: only existing Entry/Flow/atomic facts,
    // never native.current/ownsPlaybackSource or a Store/native admission.
    internal fun ownsBasSource(expected: DesktopOriginalVideoAcceptedPublication): Boolean {
        if (!isBasPresenterCurrent() || !owns() || !isPresentationCurrent(expected)) return false
        val state = success() ?: return false
        val episode = state.currentEpisode
        val load = owner.playback.captureDesktopLoadState()
        val request = load.currentRequest ?: return false
        val referer = "https://www.bilibili.com/" +
            (if (state.seasonDetail.seasonType == 10) "cheese" else "bangumi") + "/play/ep${episode.id}"
        return expected.request.bvid == episode.bvid && expected.request.aid == episode.aid &&
            expected.request.cid == episode.cid && request.bvid == episode.bvid &&
            request.aid == episode.aid && request.cid == episode.cid &&
            load.currentBvid == episode.bvid && load.currentCid == episode.cid &&
            expected.nativeSource.source.videoUrl == state.playUrl &&
            expected.nativeSource.source.audioUrl.orEmpty() == state.audioUrl.orEmpty() &&
            expected.nativeSource.source.referer == referer
    }

    /** A view of the same global BAS registry, not another player/Entry/lease authority.
     * Keep formal rejection when presentation/source is absent. Browser work is
     * performed by the unchanged action mapper after its short native admission. */
    internal fun acquireBasActions(expected: DesktopOriginalVideoAcceptedPublication,
        presentationCurrent: () -> Boolean, openExternal: (URI) -> Boolean): AutoCloseable {
        check(EventQueue.isDispatchThread())
        val load = owner.playback.captureDesktopLoadState()
        val request = load.currentRequest
        val token = load.currentLoadRequestToken
        val navigationRevision = basNavigationRevision
        val closed = AtomicBoolean(false)
        fun owned(): Boolean {
            if (closed.get() || !presentationCurrent() || basNavigationRevision != navigationRevision ||
                !ownsBasSource(expected)) return false
            val actual = owner.playback.captureDesktopLoadState()
            return request != null && actual.currentRequest === request && actual.currentLoadRequestToken == token
        }
        val actions = DesktopWindowsBasActions(expected, owner.native::current, ::owned,
            admit = { action -> owner.native.admitPlaybackDispatch(expected, action) },
            originalSeek = owner.playback::seekTo, originalPause = player::pause,
            openExternal = openExternal)
        val binding = overlay.acquireBasActions(expected.nativeSource, expected.request.cid, ::owned,
            admit = { action ->
                var applied = false
                withPresentationAdmission(expected) {
                    if (owned()) { action(); applied = true }
                } && applied
            }, activate = actions::dispatch)
        return AutoCloseable {
            check(EventQueue.isDispatchThread())
            closed.set(true)
            binding.close()
        }
    }
}
