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
    val receipt = accepted.nativeSource.source.authorizationReceipt
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
            displayed.playUrl == task.videoUrl && displayed.audioUrl.orEmpty() == task.audioUrl
    }
    require(task.bvid == episode.bvid && task.cid == episode.cid && task.aid == episode.aid &&
        task.videoUrl == state.playUrl && task.audioUrl == state.audioUrl.orEmpty())
    val explicitDownloadRight = state.seasonDetail.rights?.allowDownload
    // PUGV's original mapper has no PGC allow_download field. Preserve
    // original course behavior only over this exact accepted URL/receipt,
    // never fabricate a rights value or acquire unrestricted full media.
    val downloadAllowed = explicitDownloadRight == 1 ||
        (explicitDownloadRight == null && state.seasonDetail.seasonType == 10)
    require(downloadAllowed) { "此媒体的服务器权限不允许下载" }
    val plan = desktopOriginalBangumiNativePlan(state.seasonDetail, episode, checkNotNull(state.cachedPlayData),
        task.videoUrl, task.audioUrl.takeIf(String::isNotBlank),
        accepted.nativeSource.source.progressiveSegments.takeIf { it.isNotEmpty() }?.map { it.url },
        accepted.nativeSource.source.referer, null, 0L, true)
    val source = plan.retainedSource(accepted.nativeSource.source, task.videoUrl, task.audioUrl.takeIf(String::isNotBlank))
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
