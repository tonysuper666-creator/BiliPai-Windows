package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.shouldApplyPlayerInfoResult

/** Identity of one original player-info invocation. Part switches do not advance the
 * load token, so an equal BV/CID/token must not revive an older cancelled request. */
internal class DesktopOriginalVideoBgmRequest(
    val bvid: String,
    val cid: Long,
    val requestToken: Long,
)

/** The original fields copied at their admitted publication, never relabelled by UI. */
internal class DesktopOriginalVideoBgmResult(
    val request: DesktopOriginalVideoBgmRequest,
    val bgmInfo: BgmInfo?,
    bgmInfoList: List<BgmInfo>,
) {
    val bvid: String get() = request.bvid
    val cid: Long get() = request.cid
    val requestToken: Long get() = request.requestToken
    val bgmInfoList: List<BgmInfo> = bgmInfoList.toList()
}

internal fun desktopOriginalVideoBgmRequestIsCurrent(
    request: DesktopOriginalVideoBgmRequest,
    activeRequest: DesktopOriginalVideoBgmRequest?,
    session: PlaybackSessionState,
    state: VideoPlaybackUiState.Success?,
    callerActive: Boolean,
    switchingPart: Boolean,
): Boolean = callerActive && !switchingPart && request === activeRequest &&
    request.bvid.isNotBlank() && request.cid > 0L && state != null &&
    state.info.bvid == request.bvid && state.info.cid == request.cid &&
    shouldApplyPlayerInfoResult(session.currentLoadRequestToken, request.requestToken,
        request.bvid, request.cid, session.currentBvid, session.currentCid)

internal fun desktopOriginalVideoBgmForOwner(
    result: DesktopOriginalVideoBgmResult?,
    activeRequest: DesktopOriginalVideoBgmRequest?,
    session: PlaybackSessionState,
    state: VideoPlaybackUiState.Success?,
    switchingPart: Boolean,
): DesktopOriginalVideoBgmResult? = result?.takeIf {
    desktopOriginalVideoBgmRequestIsCurrent(it.request, activeRequest, session, state,
        callerActive = true, switchingPart = switchingPart) &&
        state != null && state.bgmInfo == it.bgmInfo && state.bgmInfoList == it.bgmInfoList
}
