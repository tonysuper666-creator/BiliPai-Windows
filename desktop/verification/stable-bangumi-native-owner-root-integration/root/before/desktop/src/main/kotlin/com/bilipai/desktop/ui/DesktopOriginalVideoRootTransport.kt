package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Passive, request-local transport metadata from the original protocol result.
 * It does not fetch, select, cache across operations, or own page items. The
 * original raw repository publishes these exact models before returning them.
 */
internal interface DesktopOriginalVideoTransportObserver {
    fun info(value: ViewInfo)
    fun playData(bvid: String, cid: Long, value: PlayUrlData)
}

internal class DesktopOriginalVideoRootRequestTransport(
    private val raw: DesktopOriginalVideoOwnerRequestRepository,
    private val stateAtCapture: PlaybackSessionState,
    initialInfo: ViewInfo?,
    beforeSelection: suspend () -> Unit,
) : DesktopOriginalVideoTransportObserver {
    private val infos = ConcurrentHashMap<String, ViewInfo>()
    private val data = ConcurrentHashMap<Pair<String, Long>, PlayUrlData>()
    init { initialInfo?.let { infos[it.bvid] = it }; raw.observeTransportMetadata(this, beforeSelection) }
    override fun info(value: ViewInfo) { raw.binding.assertCurrent(); if (value.bvid.isNotBlank()) infos[value.bvid] = value }
    override fun playData(bvid: String, cid: Long, value: PlayUrlData) {
        raw.binding.assertCurrent(); require(bvid.isNotBlank() && cid > 0L); data[bvid to cid] = value
    }
    fun infoFor(bvid: String): ViewInfo {
        raw.binding.assertCurrent()
        return infos[bvid] ?: throw IllegalStateException("Original resolved video metadata is required before transport publication")
    }
    fun source(video: String, audio: String?, bvid: String = checkNotNull(stateAtCapture.currentRequest).bvid,
        title: String = infoFor(bvid).title): PlaybackSource {
        raw.binding.assertCurrent()
        // Exact original UseCase media headers. Cookie comes from this Binding's
        // captured playback transport for this URL, including an empty result.
        return raw.binding.authorized(PlaybackSource(video, audio,
            referer = "https://www.bilibili.com",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            cookieHeader = raw.binding.captureMediaCookieHeader(video), title = title))
    }

    fun tracks(source: PlaybackSource, keys: Map<String, String>): List<DesktopMediaByteTrack> {
        raw.binding.assertCurrent()
        val bvid = checkNotNull(stateAtCapture.currentRequest).bvid
        val plays = data.entries.filter { it.key.first == bvid }.map { it.value }
        fun sameKey(url: String, candidate: String): Boolean = url == candidate ||
            (keys[url] != null && keys[url] == keys[candidate])
        val video = plays.flatMap { it.dash?.video.orEmpty() }.firstOrNull { track ->
            sameKey(source.videoUrl, track.getValidUrl()) || track.backupUrl.orEmpty().any { sameKey(source.videoUrl, it) }
        }
        val audio = source.audioUrl?.let { url -> plays.flatMap { it.dash?.audio.orEmpty() }.firstOrNull { track ->
            sameKey(url, track.getValidUrl()) || track.backupUrl.orEmpty().any { sameKey(url, it) }
        } }
        val videoAliases = listOfNotNull(video?.getValidUrl()) + video?.backupUrl.orEmpty() +
            keys.keys.filter { sameKey(source.videoUrl, it) }
        val audioAliases = listOfNotNull(audio?.getValidUrl()) + audio?.backupUrl.orEmpty() +
            source.audioUrl?.let { url -> keys.keys.filter { sameKey(url, it) } }.orEmpty()
        return desktopOriginalLegacyByteTracks(source, videoAliases, audioAliases, keys)
    }
}

/** Concrete original preparation -> same Bound -> same NativeOwner publication.
 * This factory owns no decoder, API, credentials or cache. All three preparations
 * remain CPU work; stream IO happens in the existing byte-cache/MPV authorities.
 */
internal class DesktopOriginalVideoRootMediaFactory(
    private val repository: DesktopRepository,
    private val cache: DesktopMediaByteCache,
    private val gate: DesktopOriginalVideoRootGate,
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly,
    private val onPreparation: (DesktopOriginalMediaCachePreparation) -> Unit,
    private val awaitNativeInitialization: suspend () -> Unit,
) {
    fun request(raw: DesktopOriginalVideoOwnerRequestRepository, state: PlaybackSessionState,
        nativeBaseline: Long, callerJob: Job, native: DesktopOriginalVideoNativeOwner): DesktopOriginalVideoMediaPort {
        val assembly = currentAssembly()
        val info = (assembly.playback.uiState.value as? VideoPlaybackUiState.Success)?.info
            ?.takeIf { it.bvid == state.currentRequest?.bvid }
        val transport = DesktopOriginalVideoRootRequestTransport(raw, state, info, awaitNativeInitialization)
        // Original settings/background invocations may precede the first load.
        // Keep their captured Binding; require a subject only for media publication.
        val capturedRequest = state.currentRequest
        val token = state.currentLoadRequestToken
        return DesktopOriginalVideoCachedMediaFactory(raw.binding.captureMediaBytes(cache),
            legacyOrigin = { video, audio, _ -> transport.source(video, audio) },
            adaptiveOrigin = { adaptive, _ ->
                val video = adaptive.videoTracks.firstOrNull()?.getValidUrl()
                    ?: throw IllegalStateException("Original adaptive source has no video representation")
                transport.source(video, adaptive.audioTracks.firstOrNull()?.getValidUrl())
            },
            progressiveOrigin = { url -> transport.source(url, null) },
            legacyTracks = transport::tracks,
            publish = { source, _ ->
                raw.binding.assertCurrent()
                val request = checkNotNull(capturedRequest) { "Original request is required for a media operation" }
                val resolved = captureDesktopOriginalResolvedMediaRequest(assembly.captureLoadState(), request, token)
                native.publish(resolved, source, nativeBaseline, callerJob) {
                    !callerJob.isCancelled && gate.owns() && runCatching {
                        captureDesktopOriginalResolvedMediaRequest(assembly.captureLoadState(), request, token) == resolved
                    }.getOrDefault(false)
                }
            }, onPreparation = onPreparation, reuseAccepted = null, isRetainedCurrent = native::isCurrent).media
    }

    /** Accepted recovery has its own live source Job. It does not retain a
     * completed resolver/Binding, and preserves the exact remote header fields. */
    fun accepted(expected: DesktopOriginalVideoAcceptedPublication): DesktopOriginalVideoMediaPort {
        val assembly = currentAssembly()
        if (!assembly.native.isCurrent(expected)) throw CancellationException("Original accepted recovery retired")
        val original = expected.nativeSource.source
        val receipt = checkNotNull(original.authorizationReceipt)
        val authorization = repository.capturePlaybackAuthorization(receipt.accountEpoch) { gate.owns() }
        if (authorization.receipt != receipt) throw CancellationException("Original accepted authorization changed")
        val namespace = repository.capturePlaybackCachePartition(receipt, gate::owns)
        val admission = DesktopMediaByteRepositoryAdmission(repository, authorization, namespace,
            checkNotNull(gate.scope.coroutineContext[Job]), gate::owns, gate::commitEntry)
        val request = DesktopOriginalVideoByteCacheRequest(cache, admission) {
            if (!assembly.native.isCurrent(expected)) throw CancellationException("Original recovery replaced")
        }
        fun remote(video: String, audio: String?) = original.copy(videoUrl = video, audioUrl = audio,
            progressiveSegments = emptyList(), nativePublication = null, nativeTransport = null)
        return DesktopOriginalVideoCachedMediaFactory(request,
            legacyOrigin = { video, audio, _ -> remote(video, audio) },
            adaptiveOrigin = { adaptive, _ -> remote(
                adaptive.videoTracks.firstOrNull()?.getValidUrl()
                    ?: throw IllegalStateException("Original adaptive source has no video representation"),
                adaptive.audioTracks.firstOrNull()?.getValidUrl()) },
            progressiveOrigin = { remote(it, null) },
            legacyTracks = { source, keys -> desktopOriginalLegacyByteTracks(source, emptyList(), emptyList(), keys) },
            // NativeOwner.acceptedMedia intercepts accept, so this delegate must
            // never publish a second time. If used outside that wrapper it fails.
            publish = { _, _ -> error("Accepted recovery must use the same NativeOwner.acceptedMedia") },
            onPreparation = onPreparation, reuseAccepted = expected,
            isRetainedCurrent = assembly.native::isCurrent).media
    }

    fun admission(expected: DesktopOriginalVideoAcceptedPublication, stillOwned: () -> Boolean): DesktopMediaByteAdmission {
        val receipt = checkNotNull(expected.nativeSource.source.authorizationReceipt)
        val authorization = repository.capturePlaybackAuthorization(receipt.accountEpoch) { gate.owns() && stillOwned() }
        if (authorization.receipt != receipt) throw CancellationException("Original media authorization retired")
        val namespace = repository.capturePlaybackCachePartition(receipt) { gate.owns() && stillOwned() }
        // Root entry Job is intentionally NOT this operation's resolver Job.
        // NativeOwner supplies the fixed per-source publication predicate.
        return DesktopMediaByteRepositoryAdmission(repository, authorization, namespace,
            checkNotNull(gate.scope.coroutineContext[Job]), { gate.owns() && stillOwned() }, gate::commitEntry)
    }
}
