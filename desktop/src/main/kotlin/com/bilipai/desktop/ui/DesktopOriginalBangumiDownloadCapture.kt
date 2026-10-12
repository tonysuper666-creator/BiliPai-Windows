package com.bilipai.desktop.ui

import com.android.purebilibili.feature.bangumi.BangumiPlayerState
import com.android.purebilibili.feature.download.DownloadTask
import com.bilipai.desktop.download.DesktopDownloadManager
import kotlinx.coroutines.*

/** The original Entry's exact course/PGC download capture body. These required
 * views borrow the same Entry gates, Assembly/native receipt and global queue;
 * this component creates no playback, identity, lifetime or task authority. */
internal fun captureDesktopOriginalBangumiDownloadTask(
    task: DownloadTask,
    owner: DesktopOriginalVideoOwnerAssembly,
    native: DesktopOriginalBangumiNativePresenter,
    downloads: DesktopDownloadManager,
    assertOwned: () -> Unit,
    success: () -> BangumiPlayerState.Success?,
    assertSubject: (String, Long, Long, Long) -> Unit,
    owns: () -> Boolean,
    withPresentationAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
): suspend () -> Boolean {
    assertOwned()
    val state = success() ?: throw CancellationException("PGC download detail unavailable")
    val episode = state.currentEpisode
    val accepted = owner.native.current() ?: throw CancellationException("PGC download source unavailable")
    assertSubject(episode.bvid, episode.cid, episode.id, state.seasonDetail.seasonId)
    val load = owner.captureLoadState()
    val request = load.currentRequest ?: throw CancellationException("PGC download request unavailable")
    val acceptedSource = accepted.nativeSource.source
    val selectedVideoUrl = state.playUrl
    val selectedAudioUrl = state.audioUrl.orEmpty()
    val receipt = acceptedSource.authorizationReceipt
        ?: throw CancellationException("PGC download authorization unavailable")
    fun sourceCurrent(): Boolean {
        if (!owns() || !native.ownsPlaybackSource() || !owner.native.isCurrent(accepted)) return false
        val actual = owner.captureLoadState()
        val displayed = success() ?: return false
        return actual.currentRequest === request &&
            actual.currentLoadRequestToken == load.currentLoadRequestToken &&
            actual.currentBvid == load.currentBvid && actual.currentCid == load.currentCid &&
            displayed.currentEpisode.id == episode.id && displayed.currentEpisode.cid == episode.cid &&
            displayed.seasonDetail.seasonId == state.seasonDetail.seasonId &&
            displayed.playUrl == selectedVideoUrl && displayed.audioUrl.orEmpty() == selectedAudioUrl
    }
    val expectedTaskBvid = if (task.isAudioOnly) episode.bvid.ifBlank { "ep${episode.id}" } else episode.bvid
    require(task.bvid == expectedTaskBvid && task.cid == episode.cid && task.aid == episode.aid)
    if (task.isAudioOnly) {
        require(task.videoUrl.isBlank() && task.audioUrl.isNotBlank() && task.audioUrl == selectedAudioUrl &&
            accepted.request.bvid == episode.bvid && accepted.request.cid == episode.cid &&
            acceptedSource.videoUrl == selectedVideoUrl && acceptedSource.audioUrl == task.audioUrl) {
            "PGC audio task must retain this exact accepted episode and selected audio track"
        }
    } else require(task.videoUrl == selectedVideoUrl && task.audioUrl == selectedAudioUrl)
    val explicitDownloadRight = state.seasonDetail.rights?.allowDownload
    // PUGV's original mapper has no PGC allow_download field. Preserve
    // original course behavior only over this exact accepted URL/receipt,
    // never fabricate a rights value or acquire unrestricted full media.
    val downloadAllowed = explicitDownloadRight == 1 ||
        (explicitDownloadRight == null && state.seasonDetail.seasonType == 10)
    require(downloadAllowed) { "此媒体的服务器权限不允许下载" }
    val plan = desktopOriginalBangumiNativePlan(state.seasonDetail, episode, checkNotNull(state.cachedPlayData),
        checkNotNull(selectedVideoUrl), selectedAudioUrl.takeIf(String::isNotBlank),
        acceptedSource.progressiveSegments.takeIf { it.isNotEmpty() }?.map { it.url },
        acceptedSource.referer, null, 0L, true)
    val retained = plan.retainedSource(acceptedSource, checkNotNull(selectedVideoUrl), selectedAudioUrl.takeIf(String::isNotBlank))
    // Match the ordinary resolver's audio-only download projection. Receipt,
    // Cookie and complete original headers stay captured; no playback is published.
    val source = if (task.isAudioOnly) retained.copy(videoUrl = task.audioUrl, audioUrl = null,
        title = task.title, progressiveSegments = emptyList(), nativePublication = null, nativeTransport = null)
    else retained
    val destination = downloads.destinationFor(task.customSaveDir)
    var captured = false
    if (!withPresentationAdmission(accepted) {
            owner.native.admitPlaybackDispatch(accepted) {
                if (sourceCurrent()) captured = true
            }
        } || !captured) throw CancellationException("PGC download click retired")
    // No coroutine or IO was launched during click capture. This immutable
    // view borrows the existing source and global queue; it owns neither.
    return {
        currentCoroutineContext().ensureActive()
        val binding = (owner.invocations.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository).binding
        val caller = binding.capturedDownloadCallerJob()
        binding.assertCurrent()
        if (binding.receipt != receipt || !sourceCurrent())
            throw CancellationException("PGC download caller/source replaced")
        fun requestCurrent() = caller.isActive && sourceCurrent()
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); caller.ensureActive(); binding.assertCurrent()
            if (!requestCurrent()) throw CancellationException("PGC download IO source retired")
            downloads.enqueueOriginal(task, source, destination, downloadAllowed,
                state.seasonDetail.seasonId, episode.id, state.seasonDetail.seasonType == 10,
                ::requestCurrent,
                { action ->
                    var applied = false
                    withPresentationAdmission(accepted) {
                        owner.native.admitPlaybackDispatch(accepted) {
                            caller.ensureActive(); binding.assertCurrent()
                            if (requestCurrent()) { action(); applied = true }
                        }
                    } && applied
                })
            // The real manager persists/schedules an admitted task before
            // returning; the original Entry does not own that global task.
        }
    }
}
