package com.bilipai.desktop.ui

import com.android.purebilibili.feature.download.DownloadOptions
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlinx.coroutines.Job

/** The original selected-quality producer requires split video/audio. Keep the
 * accepted progressive/durl route on its existing playing-quality download. */
internal fun desktopWindowsVideoCanChooseDownloadQuality(success: VideoPlaybackUiState.Success): Boolean =
    !success.audioUrl.isNullOrBlank() || success.cachedDashAudios.firstOrNull()?.getValidUrl()?.isNotBlank() == true

/** One foreground chooser, borrowing the existing accepted source and page Job.
 * The original VM/Binding still authorize and construct the actual queued task. */
internal class DesktopWindowsVideoDownloadSelection private constructor(
    val title: String,
    val qualityOptions: List<Pair<Int, String>>,
    val currentQuality: Int,
    private val pageJob: Job,
    private val stillCurrent: () -> Boolean,
    private val download: (Int, DownloadOptions, () -> Boolean) -> Unit,
) {
    private var consumed = false
    // This predicate also travels into the original asynchronous task admission.
    // Consuming the popup must not revoke a correctly submitted download request.
    private fun ownsSource(): Boolean = pageJob.isActive && stillCurrent()
    fun isCurrent(): Boolean = !consumed && ownsSource()
    fun dismiss() { consumed = true }
    fun select(quality: Int, options: DownloadOptions): Boolean {
        if (!isCurrent() || qualityOptions.none { it.first == quality }) return false
        consumed = true
        download(quality, options, ::ownsSource)
        return true
    }

    companion object {
        fun capture(
            success: VideoPlaybackUiState.Success,
            pageJob: Job,
            stillCurrent: () -> Boolean,
            download: (Int, DownloadOptions, () -> Boolean) -> Unit,
        ): DesktopWindowsVideoDownloadSelection? {
            if (!pageJob.isActive || !stillCurrent() || success.isQualitySwitching ||
                !desktopWindowsVideoCanChooseDownloadQuality(success) ||
                success.info.bvid.isBlank() || success.info.cid <= 0L) return null
            // Same advertised ordering/highest default as v0.3.3. If the response
            // omits labels, preserve the previously available playing-quality download.
            val advertised = success.qualityIds.zip(success.qualityLabels)
                .filter { it.first > 0 && it.second.isNotBlank() }
                .distinctBy { it.first }.sortedByDescending { it.first }
            val options = advertised.ifEmpty {
                if (success.currentQuality > 0) listOf(success.currentQuality to "当前画质") else emptyList()
            }
            if (options.isEmpty()) return null
            return DesktopWindowsVideoDownloadSelection(success.info.title, options,
                options.first().first, pageJob, stillCurrent, download)
        }
    }
}

/** Original part/collection candidates, with the same foreground source lifetime.
 * Selection changes only the original selected flags; task construction and
 * single-use reply/account admission remain in the original VM and Binding. */
internal class DesktopWindowsVideoBatchDownloadSelection private constructor(
    val title: String,
    val candidates: List<com.android.purebilibili.feature.download.BatchDownloadCandidate>,
    val qualityOptions: List<Pair<Int, String>>,
    val currentQuality: Int,
    private val pageJob: Job,
    private val stillCurrent: () -> Boolean,
    private val download: (Int, DownloadOptions, List<com.android.purebilibili.feature.download.BatchDownloadCandidate>, () -> Boolean) -> Unit,
) {
    private var consumed = false
    private fun ownsSource(): Boolean = pageJob.isActive && stillCurrent()
    fun isCurrent(): Boolean = !consumed && ownsSource()
    fun dismiss() { consumed = true }
    fun select(quality: Int, options: DownloadOptions,
        selection: List<com.android.purebilibili.feature.download.BatchDownloadCandidate>): Boolean {
        if (!isCurrent() || qualityOptions.none { it.first == quality } ||
            selection.map { it.id }.distinct().size != selection.size) return false
        val originalById = candidates.associateBy { it.id }
        if (selection.any { choice ->
                val original = originalById[choice.id]
                original == null || choice.copy(selected = original.selected) != original
            }) return false
        val chosen = selection.filter { it.selected }.map { originalById.getValue(it.id).copy(selected = true) }
        if (chosen.isEmpty()) return false
        consumed = true
        download(quality, options, chosen, ::ownsSource)
        return true
    }

    companion object {
        fun capture(success: VideoPlaybackUiState.Success, pageJob: Job, stillCurrent: () -> Boolean,
            download: (Int, DownloadOptions, List<com.android.purebilibili.feature.download.BatchDownloadCandidate>, () -> Boolean) -> Unit,
        ): DesktopWindowsVideoBatchDownloadSelection? {
            if (!pageJob.isActive || !stillCurrent() || success.isQualitySwitching ||
                success.info.bvid.isBlank() || success.info.cid <= 0L) return null
            val candidates = com.android.purebilibili.feature.download.resolveBatchDownloadCandidates(success.info)
            if (candidates.size <= 1) return null
            val advertised = success.qualityIds.zip(success.qualityLabels)
                .filter { it.first > 0 && it.second.isNotBlank() }
                .distinctBy { it.first }.sortedByDescending { it.first }
            val qualities = advertised.ifEmpty {
                if (success.currentQuality > 0) listOf(success.currentQuality to "当前画质") else emptyList()
            }
            if (qualities.isEmpty()) return null
            return DesktopWindowsVideoBatchDownloadSelection(success.info.title, candidates, qualities,
                qualities.first().first, pageJob, stillCurrent, download)
        }
    }
}
