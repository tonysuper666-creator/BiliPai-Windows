package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** One original task construction's immutable, single-use transport view. This
 * weak identity table neither caches play URLs nor owns a playlist/task list.
 * Explicit batch results enter here before the original local reply is discarded;
 * current/audio tasks instead require the exact accepted source and Success.
 */
internal class DesktopOriginalVideoOwnerDownloadResolver(
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
    private val captureCookieHeader: (DesktopOriginalVideoRepositoryBinding, String) -> String,
) {
    private class Key(value: DownloadTask, queue: ReferenceQueue<DownloadTask>? = null) :
        WeakReference<DownloadTask>(value, queue) {
        private val identityHash = System.identityHashCode(value)
        override fun hashCode() = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Key && get() != null && get() === other.get())
    }
    private class Pending(val capture: DesktopOriginalVideoOwnerDownloadCapture) {
        var completion: DisposableHandle? = null
    }
    private val collected = ReferenceQueue<DownloadTask>()
    private val pending = HashMap<Key, Pending>()
    private fun cleanCollected() {
        while (true) {
            val key = collected.poll() as? Key ?: break
            pending.remove(key)?.completion?.dispose()
        }
    }

    fun captureTask(task: DownloadTask, explicitReply: PlayUrlData?): DownloadTask {
        val capture = capture(task, explicitReply)
        val key = Key(task, collected)
        val value = Pending(capture)
        synchronized(pending) {
            cleanCollected()
            check(!pending.containsKey(key)) { "Original task was captured twice" }
            pending[key] = value
            value.completion = capture.callerJob.invokeOnCompletion {
                synchronized(pending) { if (pending[key] === value) pending.remove(key) }
            }
        }
        return task
    }

    fun resolveCaptured(task: DownloadTask): DesktopOriginalVideoOwnerDownloadCapture {
        val captured = synchronized(pending) {
            cleanCollected()
            pending.remove(Key(task))?.also { it.completion?.dispose() }?.capture
        } ?: if (task.isAudioOnly) capture(task, null) // Immutable audio click, strict accepted path only.
            else error("Original video task requires its single-use construction capture")
        captured.callerJob.ensureActive()
        if (!captured.isCurrent()) throw CancellationException("Original download construction retired")
        return captured
    }

    private fun capture(task: DownloadTask, explicitReply: PlayUrlData?): DesktopOriginalVideoOwnerDownloadCapture {
        require(task.bvid.isNotBlank() && task.cid > 0L)
        val assembly = currentAssembly()?.takeIf { it.owns() }
            ?: throw CancellationException("Original download assembly retired")
        val request = assembly.invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository
            ?: error("Original download requires this invocation's actual repository")
        val binding = request.binding
        binding.assertCurrent()
        val caller: Job = binding.capturedDownloadCallerJob()
        caller.ensureActive()
        var expected: DesktopOriginalVideoAcceptedPublication? = null
        val source: PlaybackSource
        if (explicitReply != null) {
            require(!task.isAudioOnly) { "Audio click has no explicit batch reply" }
            val dash = requireNotNull(explicitReply.dash) { "Original download selection requires DASH" }
            // Original selection may fall back to another quality while task.quality
            // retains the requested value. Preserve that algorithm and task schema.
            require(dash.video.any { track ->
                task.videoUrl in (listOf(track.baseUrl) + track.backupUrl.orEmpty()) }) {
                "Original video task does not match its exact reply/quality"
            }
            val audio = dash.audio.orEmpty() + dash.dolby?.audio.orEmpty() + listOfNotNull(dash.flac?.audio)
            require(audio.any { task.audioUrl in (listOf(it.baseUrl) + it.backupUrl.orEmpty()) }) {
                "Original audio task does not match its exact reply"
            }
            // Original ordinary VideoRepository/ViewInfo have no PGC rights/episode
            // fields. The full ordinary VM's DownloadManager path permits UGC.
            // PGC/PUGV use existing MediaScreens' captured BangumiPlaybackInfo path.
            source = binding.authorized(PlaybackSource(task.videoUrl, task.audioUrl,
                referer = "https://www.bilibili.com/video/${task.bvid}",
                cookieHeader = captureCookieHeader(binding, task.videoUrl), title = task.title))
        } else {
            val accepted = assembly.native.current()
                ?: throw CancellationException("Original current download source is unavailable")
            val success = assembly.playback.uiState.value as? VideoPlaybackUiState.Success
                ?: throw CancellationException("Original current download detail is unavailable")
            require(accepted.request.bvid == task.bvid && accepted.request.cid == task.cid &&
                success.info.bvid == task.bvid && success.info.cid == task.cid &&
                accepted.nativeSource.source.authorizationReceipt == binding.receipt) {
                "Original current download task differs from accepted subject/authorization"
            }
            val acceptedSource = accepted.nativeSource.source
            require(success.playUrl == acceptedSource.videoUrl && success.audioUrl.orEmpty() == acceptedSource.audioUrl.orEmpty()) {
                "Original current download detail differs from its accepted media"
            }
            if (task.isAudioOnly) {
                require(task.videoUrl.isBlank() && task.audioUrl.isNotBlank() && task.audioUrl == acceptedSource.audioUrl)
                source = acceptedSource.copy(videoUrl = task.audioUrl, audioUrl = null,
                    title = task.title, progressiveSegments = emptyList(), nativePublication = null, nativeTransport = null)
            } else {
                val videoMatches = (task.quality == success.currentQuality && task.videoUrl == acceptedSource.videoUrl) ||
                    success.cachedDashVideos.any { it.id == task.quality && task.videoUrl in (listOf(it.baseUrl) + it.backupUrl.orEmpty()) }
                val audioMatches = task.audioUrl == acceptedSource.audioUrl ||
                    success.cachedDashAudios.any { task.audioUrl in (listOf(it.baseUrl) + it.backupUrl.orEmpty()) }
                require(videoMatches && audioMatches && task.videoUrl.isNotBlank() && task.audioUrl.isNotBlank()) {
                    "Original direct download does not match accepted raw quality/tracks"
                }
                source = acceptedSource.copy(videoUrl = task.videoUrl, audioUrl = task.audioUrl,
                    title = task.title, nativePublication = null, nativeTransport = null)
            }
            expected = accepted
        }
        binding.assertCurrent(); caller.ensureActive()
        val owns = {
            caller.isActive && currentAssembly() === assembly && assembly.owns() &&
                runCatching { binding.assertCurrent(); true }.getOrElse {
                    if (it is CancellationException) false else throw it
                } && (expected == null || assembly.native.isCurrent(expected))
        }
        return DesktopOriginalVideoOwnerDownloadCapture(source, true, 0L, 0L, false, caller, owns)
    }
}
