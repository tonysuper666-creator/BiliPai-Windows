package com.bilipai.desktop.ui

import com.android.purebilibili.core.player.shouldUsePlaybackMediaCache
import com.android.purebilibili.core.player.buildPlaybackCacheKey
import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import kotlinx.coroutines.*
import okhttp3.Call
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal enum class DesktopOriginalMediaDirectReason { UNSUPPORTED_TRANSPORT, CACHE_IO_FAILURE }

/** A concrete preparation result. Direct is an actual original remote source,
 * never a false successful cache write. Causes are classified without URL logging. */
internal sealed interface DesktopOriginalMediaCachePreparation {
    val source: PlaybackSource
    class Cached internal constructor(override val source: PlaybackSource,
        internal val bound: DesktopBoundMediaByteCache,
        private val wasRetained: Boolean) : DesktopOriginalMediaCachePreparation {
        private val state = AtomicInteger(if (wasRetained) 2 else 0) // prepared/publishing/accepted/failed
        internal fun beginPublish() { if (!wasRetained) check(state.compareAndSet(0, 1)) }
        internal fun accepted(value: DesktopOriginalVideoAcceptedPublication) {
            check(value.nativeSource.source.nativeTransport === source.nativeTransport)
            check(value.nativeSource.source.authorizationReceipt == source.authorizationReceipt)
            state.set(2)
        }
        internal fun failed() { if (!wasRetained) { state.set(3); bound.close() } }
        internal fun discardUnaccepted() { if (!wasRetained && state.compareAndSet(0, 3)) bound.close() }
        override fun toString() = "DesktopOriginalMediaCachePreparation.Cached(retained=$wasRetained)"
    }
    class Direct internal constructor(override val source: PlaybackSource,
        val reason: DesktopOriginalMediaDirectReason) : DesktopOriginalMediaCachePreparation {
        override fun toString() = "DesktopOriginalMediaCachePreparation.Direct(reason=$reason)"
    }
}

/** The exact RepositoryBinding request admission and Job. No mutable latest
 * credential/source lookup. The existing native owner supplies accepted Jobs. */
internal class DesktopOriginalVideoByteCacheRequest(
    private val cache: DesktopMediaByteCache,
    private val admission: DesktopMediaByteAdmission,
    private val assertCaptured: () -> Unit,
) {
    val receipt get() = admission.receipt
    fun assertCurrent() { admission.check(); assertCaptured() }
    fun prepare(source: PlaybackSource, tracks: List<DesktopMediaByteTrack>, fullAdaptiveManifest: String?,
        retained: DesktopOriginalVideoAcceptedPublication?, isRetainedCurrent: (DesktopOriginalVideoAcceptedPublication) -> Boolean): DesktopOriginalMediaCachePreparation {
        assertCurrent(); require(source.authorizationReceipt == receipt); require(source.nativeTransport == null)
        val urls = source.progressiveSegments.map { it.url }.ifEmpty { listOfNotNull(source.videoUrl, source.audioUrl) }
        if (urls.any { !shouldUsePlaybackMediaCache(it) }) return DesktopOriginalMediaCachePreparation.Direct(source,
            DesktopOriginalMediaDirectReason.UNSUPPORTED_TRANSPORT)
        require(tracks.isNotEmpty()); require(tracks.map { it.url }.distinct().size == tracks.size)
        val prior = retained?.nativeSource?.source?.nativeTransport
        if (prior != null && isRetainedCurrent(checkNotNull(retained))) {
            val matches = runCatching { prior.validate(source); prior.bound.matchesCapturedPlan(tracks, fullAdaptiveManifest) }.getOrElse { false }
            if (matches) return DesktopOriginalMediaCachePreparation.Cached(source.copy(nativeTransport = prior), prior.bound, true)
        }
        var bound: DesktopBoundMediaByteCache? = null
        try {
            bound = cache.bind(admission, tracks)
            val carrier = bound.prepareNativeTransport(source, fullAdaptiveManifest)
            return DesktopOriginalMediaCachePreparation.Cached(source.copy(nativeTransport = carrier), bound, false).also { value ->
                admission.ownerJob.invokeOnCompletion { value.discardUnaccepted() }
            }
        } catch (cancelled: CancellationException) { bound?.close(); throw cancelled }
        catch (_: IOException) {
            bound?.close(); assertCurrent()
            return DesktopOriginalMediaCachePreparation.Direct(source, DesktopOriginalMediaDirectReason.CACHE_IO_FAILURE)
        } catch (failure: Throwable) { bound?.close(); throw failure }
    }
}

/** Full raw adaptive DTO closure, including all representations and mirrors.
 * Explicit keys win; the sole original cache-key helper handles absent keys.
 * The selected source final header map is identical across native/probe/prefetch. */
private fun originalRoleTracks(urls: List<String>, role: String, keys: Map<String, String>,
    headers: Map<String, String>): List<DesktopMediaByteTrack> = urls.filter(String::isNotBlank).distinct()
    .groupBy { buildPlaybackCacheKey(it, keys[it]) }.map { (key, aliases) ->
        capturedDesktopMediaByteTrack(aliases.first(), aliases.drop(1), key, role, headers)
    }
internal fun desktopOriginalLegacyByteTracks(source: PlaybackSource, videoAliases: List<String>,
    audioAliases: List<String>, keys: Map<String, String>): List<DesktopMediaByteTrack> {
    val headers = capturedPlaybackMediaHeaders(source)
    return originalRoleTracks(listOf(source.videoUrl) + videoAliases, "video", keys, headers) +
        source.audioUrl?.let { originalRoleTracks(listOf(it) + audioAliases, "audio", keys, headers) }.orEmpty()
}
internal fun desktopOriginalAdaptiveByteTracks(source: AdaptiveDashPlaybackSource,
    keys: Map<String, String>, headers: Map<String, String>): List<DesktopMediaByteTrack> = buildList {
    source.videoTracks.forEach { raw ->
        val url = raw.getValidUrl(); require(url.isNotBlank())
        addAll(originalRoleTracks(listOf(url) + raw.backupUrl.orEmpty(), "video", keys, headers))
    }
    source.audioTracks.forEach { raw ->
        val url = raw.getValidUrl(); require(url.isNotBlank())
        addAll(originalRoleTracks(listOf(url) + raw.backupUrl.orEmpty(), "audio", keys, headers))
    }
}
internal fun desktopOriginalProgressiveByteTracks(source: PlaybackSource, keys: Map<String, String>): List<DesktopMediaByteTrack> {
    val headers = capturedPlaybackMediaHeaders(source)
    return source.progressiveSegments.map { it.url }.ifEmpty { listOf(source.videoUrl) }.distinct().map { url ->
        capturedDesktopMediaByteTrack(url, emptyList(), keys[url], "progressive", headers)
    }
}

/** Decorates the three exact original CPU preparations and actual native publish.
 * Intent remains owned by the sole original lexical intent view. A direct-cache
 * decision is reported through a required typed observer, never Unit success. */
internal class DesktopOriginalVideoCachedMediaFactory(
    private val request: DesktopOriginalVideoByteCacheRequest,
    private val legacyOrigin: (String, String?, Map<String, String>) -> PlaybackSource,
    private val adaptiveOrigin: (AdaptiveDashPlaybackSource, Map<String, String>) -> PlaybackSource?,
    private val progressiveOrigin: (String) -> PlaybackSource,
    private val legacyTracks: (PlaybackSource, Map<String, String>) -> List<DesktopMediaByteTrack>,
    private val publish: (PlaybackSource, DesktopOriginalVideoMediaIntent) -> DesktopOriginalVideoAcceptedPublication,
    private val onPreparation: (DesktopOriginalMediaCachePreparation) -> Unit,
    private val reuseAccepted: DesktopOriginalVideoAcceptedPublication?,
    private val isRetainedCurrent: (DesktopOriginalVideoAcceptedPublication) -> Boolean,
) {
    private val preparations = java.util.IdentityHashMap<DesktopNativeMediaTransport, DesktopOriginalMediaCachePreparation.Cached>()
    private fun cacheable(source: PlaybackSource) = source.progressiveSegments.map { it.url }
        .ifEmpty { listOfNotNull(source.videoUrl, source.audioUrl) }.all(::shouldUsePlaybackMediaCache)
    private fun prepared(source: PlaybackSource, tracks: List<DesktopMediaByteTrack>, manifest: String?): PlaybackSource {
        val result = request.prepare(source, tracks, manifest, reuseAccepted, isRetainedCurrent)
        if (result is DesktopOriginalMediaCachePreparation.Cached) synchronized(preparations) {
            preparations[checkNotNull(result.source.nativeTransport)] = result
        }
        onPreparation(result); return result.source
    }
    val media: DesktopOriginalVideoMediaPort = DesktopOriginalVideoMediaIntentView(
        legacy = { video, audio, keys -> request.assertCurrent(); val raw = legacyOrigin(video, audio, keys)
            prepared(raw, if (cacheable(raw)) legacyTracks(raw, keys) else emptyList(), null) },
        adaptive = { adaptive, keys -> request.assertCurrent(); adaptiveOrigin(adaptive, keys)?.let { raw ->
            val allHttp = (adaptive.videoTracks.map { it.getValidUrl() } + adaptive.audioTracks.map { it.getValidUrl() })
                .all(::shouldUsePlaybackMediaCache)
            if (!allHttp) { onPreparation(DesktopOriginalMediaCachePreparation.Direct(raw,
                DesktopOriginalMediaDirectReason.UNSUPPORTED_TRANSPORT)); null }
            else prepared(raw, desktopOriginalAdaptiveByteTracks(adaptive, keys, capturedPlaybackMediaHeaders(raw)),
                adaptive.manifest).takeIf { it.nativeTransport != null } // original UseCase chooses its legacy fallback
        } },
        progressive = { url -> request.assertCurrent(); val raw = progressiveOrigin(url)
            if (!cacheable(raw)) {
                onPreparation(DesktopOriginalMediaCachePreparation.Direct(raw, DesktopOriginalMediaDirectReason.UNSUPPORTED_TRANSPORT)); raw
            } else prepared(raw, desktopOriginalProgressiveByteTracks(raw, emptyMap()), null) },
        publish = { source, intent ->
            request.assertCurrent()
            val selection = source.nativeTransport?.let { synchronized(preparations) { preparations[it] } }
            if (source.nativeTransport != null) requireNotNull(selection) { "Native cache transport must belong to captured preparation" }
            selection?.beginPublish()
            try { selection?.accepted(publish(source, intent)) ?: publish(source, intent) }
            catch (failure: Throwable) { selection?.failed(); throw failure }
        },
    )
}

/** Captured once before launching original SIDX/segment work. Each range reaches
 * the exact bound used by native playback; no fresh invocation/client/Bound. */
internal class DesktopOriginalCapturedMediaRanges(
    private val bound: DesktopBoundMediaByteCache,
    val headers: Map<String, String>,
    private val stillCurrent: () -> Boolean,
) : DesktopOriginalCdnRangeCache {
    fun assertCurrent() { if (!stillCurrent()) throw CancellationException("Captured CDN source replaced") }
    fun calls(callerJob: Job): Call.Factory { assertCurrent(); return bound.probeCalls(callerJob, stillCurrent) }
    override suspend fun prefetchRange(url: String, cacheKey: String, position: Long, length: Long, headers: Map<String, String>) {
        currentCoroutineContext().ensureActive(); assertCurrent()
        require(headers == this.headers) { "CDN cache headers differ from native source" }
        bound.prefetchRange(url, cacheKey, position, length, headers)
        currentCoroutineContext().ensureActive(); assertCurrent()
    }
}
internal class DesktopOriginalCdnRangeCapture(
    private val expected: DesktopOriginalVideoAcceptedPublication,
    private val isCurrent: (DesktopOriginalVideoAcceptedPublication) -> Boolean,
) : DesktopOriginalCdnRangeCache {
    private val carrier = checkNotNull(expected.nativeSource.source.nativeTransport) { "Accepted source has no byte-cache carrier" }
    private val ranges = DesktopOriginalCapturedMediaRanges(carrier.bound,
        capturedPlaybackMediaHeaders(expected.nativeSource.source)) { isCurrent(expected) }
    val headers get() = ranges.headers
    fun assertCurrent() = ranges.assertCurrent()
    fun calls(callerJob: Job): Call.Factory = ranges.calls(callerJob)
    override suspend fun prefetchRange(url: String, cacheKey: String, position: Long, length: Long, headers: Map<String, String>) =
        ranges.prefetchRange(url, cacheKey, position, length, headers)
}

/** Exact raw request head consumer. Wi-Fi and 1536/256 limits remain in original
 * helper; only total-size clipping is delegated to the actual byte actor. */
internal class DesktopOriginalPortraitByteCache(
    private val request: DesktopOriginalVideoByteCacheRequest,
    private val source: PlaybackSource,
    private val tracks: List<DesktopMediaByteTrack>,
    private val preparation: DesktopOriginalMediaCachePreparation.Cached,
) : AutoCloseable {
    val urls get() = listOfNotNull(source.videoUrl, source.audioUrl)
    suspend fun prefetchHead(url: String, limit: Long) {
        currentCoroutineContext().ensureActive(); request.assertCurrent()
        val track = tracks.firstOrNull { url in it.urls } ?: error("Portrait head not captured")
        preparation.bound.prefetchHeadRange(url, track.cacheKey, limit, track.headers)
        currentCoroutineContext().ensureActive(); request.assertCurrent()
    }
    override fun close() { preparation.discardUnaccepted() }
}

internal fun captureDesktopOriginalPortraitByteCache(request: DesktopOriginalVideoByteCacheRequest,
    source: PlaybackSource, tracks: List<DesktopMediaByteTrack>): DesktopOriginalPortraitByteCache {
    val preparation = request.prepare(source, tracks, null, null) { false }
    if (preparation !is DesktopOriginalMediaCachePreparation.Cached) {
        throw IOException("Portrait byte cache unavailable: ${(preparation as DesktopOriginalMediaCachePreparation.Direct).reason}")
    }
    return DesktopOriginalPortraitByteCache(request, source, tracks, preparation)
}

/** Real one-shot direct recovery consumes the owned readback and existing native
 * acceptedMedia publication. It never recreates a cache after that failure. */
internal fun recoverDesktopOriginalVideoDirectAfterCacheError(owner: DesktopOriginalVideoNativeOwner,
    expected: DesktopOriginalVideoAcceptedPublication, intent: DesktopOriginalVideoMediaIntent,
    expectedFailureAttemptId: Long): Boolean {
    if (!owner.isCurrent(expected) || expected.nativeSource.source.nativeTransport == null) return false
    return owner.recoverDirectAfterCacheError(expected, intent.startPositionMs / 1000.0,
        !intent.playWhenReady, expectedFailureAttemptId)
}
