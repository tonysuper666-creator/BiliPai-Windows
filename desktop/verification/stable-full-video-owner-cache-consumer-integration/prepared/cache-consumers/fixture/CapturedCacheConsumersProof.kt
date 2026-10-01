package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.plugin.*
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.policy.PlaybackQualityMode
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.cache.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.Request
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private var assertions = 0
private fun verify(label: String, condition: Boolean) { check(condition) { label }; assertions++; println("PASS $label") }

fun main(args: Array<String>) = runBlocking {
    val owned = Path.of(args[0]); Files.createDirectories(owned)
    val media = Path.of(args[1])
    val bytes = (0..2).associate { "dash-stream$it.mp4" to Files.readAllBytes(media.resolve("dash-stream$it.mp4")) }
    val root = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val entryJob = Job(root.coroutineContext[Job]); val alive = AtomicBoolean(true); val entryLock = Any()
    val store = DesktopSessionStore(owned.resolve("session"), persistent = false); val repo = DesktopRepository(store)
    val cache = DesktopMediaByteCache(owned.resolve("cache"), root, repo)
    val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val workers = Executors.newFixedThreadPool(4) { Thread(it, "owned-consumer-origin").apply { isDaemon = true } }
    origin.executor = workers
    val wires = CopyOnWriteArrayList<Map<String, String?>>()
    val slowHeaders = CountDownLatch(1)
    origin.createContext("/") { exchange ->
        try {
            val slow = exchange.requestURI.path == "/slow.mp4"
            val file = if (slow) bytes.getValue("dash-stream0.mp4") else bytes.getValue(exchange.requestURI.path.substringAfterLast('/'))
            val range = checkNotNull(parseMediaRange(exchange.requestHeaders.getFirst("Range"), file.size.toLong()))
            wires += mapOf("Referer" to exchange.requestHeaders.getFirst("Referer"),
                "User-Agent" to exchange.requestHeaders.getFirst("User-Agent"), "Cookie" to exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("ETag", "\"owned-fixed-file\"")
            exchange.responseHeaders.add("Content-Range", "bytes ${range.first}-${range.first + range.second - 1}/${file.size}")
            exchange.sendResponseHeaders(206, range.second)
            exchange.responseBody.use { out ->
                if (slow) { slowHeaders.countDown(); Thread.sleep(350) }
                out.write(file, range.first.toInt(), range.second.toInt())
            }
        } catch (_: java.io.IOException) { } finally { exchange.close() }
    }
    origin.start()
    val base = "http://127.0.0.1:${origin.address.port}"
    fun gate(block: () -> Unit): Boolean = synchronized(entryLock) { if (!alive.get()) false else { block(); true } }
    var preparedCarrier: DesktopNativeMediaTransport? = null
    try {
        withContext(Dispatchers.IO) {
            // Explicit fixture policy inputs: no hardware or external API is exercised.
            val binding = DesktopOriginalVideoRepositoryBinding.capture(repo, repo.sessionEpoch, entryJob,
                { alive.get() }, ::gate, PlayerPreferences(), null, emptySet(), false,
                { false }, { false }, { false }, { false }, { _, _ -> false })
            val request = binding.captureMediaBytes(cache)
            val source = binding.authorized(PlaybackSource("$base/dash-stream0.mp4?signature=owned", audioUrl = "$base/dash-stream2.mp4",
                referer = "", userAgent = "", cookieHeader = "", title = "Fixture only"))
            val keys = mapOf(source.videoUrl to buildCdnTrackCacheKey("video", source.videoUrl),
                "$base/dash-stream0.mp4?signature=mirror" to buildCdnTrackCacheKey("video", source.videoUrl))
            val tracks = desktopOriginalLegacyByteTracks(source, listOf("$base/dash-stream0.mp4?signature=mirror"), emptyList(), keys)
            verify("raw captured request exposes real sameStore byte receipt", request.receipt == binding.receipt)
            verify("explicit key groups registered signed mirrors without changing key", tracks[0].cacheKey == keys[source.videoUrl] && tracks[0].urls.size == 2)
            val selection = request.prepare(source, tracks, null, null) { false } as DesktopOriginalMediaCachePreparation.Cached
            preparedCarrier = selection.source.nativeTransport
            verify("source carrier holds the exact prepared Bound", preparedCarrier?.bound === selection.bound)
            verify("same separate track plan can be retained", selection.bound.matchesCapturedPlan(tracks, null))
            val changedMirror = desktopOriginalLegacyByteTracks(source, listOf("$base/dash-stream0.mp4?signature=changed"), emptyList(), keys)
            verify("changed mirror or explicit cache key refuses old native plan", !selection.bound.matchesCapturedPlan(changedMirror, null) &&
                !selection.bound.matchesCapturedPlan(desktopOriginalLegacyByteTracks(source, emptyList(), emptyList(), emptyMap()), null))
            val ranges = DesktopOriginalCapturedMediaRanges(selection.bound, capturedPlaybackMediaHeaders(source)) { alive.get() }
            val result = CdnDashSegmentPrefetcher(ranges, ranges.calls(checkNotNull(currentCoroutineContext()[Job])), ranges.headers).prefetch(
                CdnDashPrefetchRequest(listOf(source.videoUrl), CdnByteRange(877, 928), checkNotNull(keys[source.videoUrl]), 20_000, 0))
            verify("original SIDX prefetch writes real selected interval into same native Bound", result.plannedSegments == 1 && result.cachedSegments == 1 && cache.stats().spans == 1)
            verify("original worker uses final native headers on both probe and cached range wire", wires.isNotEmpty() && wires.all { it.values.all { value -> value == "" } })
            val beforeHead = cache.stats().upstreamBytes
            selection.bound.prefetchHeadRange(source.videoUrl, tracks[0].cacheKey, 1536L * 1024, tracks[0].headers)
            verify("head clipping retains existing SIDX byte coverage instead of duplicate full range", cache.stats().upstreamBytes - beforeHead < bytes.getValue("dash-stream0.mp4").size)
            val mismatched = runCatching { ranges.prefetchRange(source.videoUrl, tracks[0].cacheKey, 0, 1, mapOf("Referer" to "wrong")) }
            verify("consumer refuses mismatched final headers rather than false success", mismatched.isFailure)
            verify("captured probe refuses an uncaptured URL", runCatching { ranges.calls(checkNotNull(currentCoroutineContext()[Job])).newCall(
                Request.Builder().url("$base/not-captured.mp4").header("Range", "bytes=0-0").build()) }.isFailure)

            val mpd = (0..2).fold(Files.readString(media.resolve("dash.mpd"))) { t, i -> t.replace(">dash-stream$i.mp4<", ">$base/dash-stream$i.mp4<") }
            val adaptive = AdaptiveDashPlaybackSource(mpd,
                listOf(DashVideo(id = 80, baseUrl = "$base/dash-stream0.mp4"), DashVideo(id = 64, baseUrl = "$base/dash-stream1.mp4")),
                listOf(DashAudio(id = 30280, baseUrl = "$base/dash-stream2.mp4")), PlaybackQualityMode.AUTO)
            val all = desktopOriginalAdaptiveByteTracks(adaptive, emptyMap(), ranges.headers)
            verify("full adaptive plan captures both video representations and audio", all.size == 3 && all.map { it.representation } == listOf("video", "video", "audio"))
            val adaptiveSource = source.copy(videoUrl = "$base/dash-stream0.mp4")
            val adaptivePrepared = request.prepare(adaptiveSource, all, adaptive.manifest, null) { false } as DesktopOriginalMediaCachePreparation.Cached
            verify("full manifest preparation retains all original representation and range rows",
                adaptivePrepared.source.nativeTransport!!.lease.manifest!!.toString(Charsets.UTF_8).let { text ->
                    Regex("<Representation\\b").findAll(text).count() == 3 && text.contains("mediaRange=\"877-36070\"") && text.contains("range=\"0-764\"") })
            verify("same full adaptive plan retains original representation identity", adaptivePrepared.bound.matchesCapturedPlan(all, adaptive.manifest))
            verify("changed MPD range refuses old native plan", !adaptivePrepared.bound.matchesCapturedPlan(all,
                adaptive.manifest.replace("877-36070", "877-36071")))
            verify("separate and full MPD plans never reuse each other's carrier", !selection.bound.matchesCapturedPlan(tracks, adaptive.manifest) &&
                !adaptivePrepared.bound.matchesCapturedPlan(all, null))
            val feedback = AtomicReference<DesktopOriginalMediaCachePreparation?>()
            val factory = DesktopOriginalVideoCachedMediaFactory(request,
                { video, audio, _ -> source.copy(videoUrl = video, audioUrl = audio) },
                { _, _ -> adaptiveSource }, { url -> source.copy(videoUrl = url, audioUrl = null) },
                { raw, explicit -> desktopOriginalLegacyByteTracks(raw, emptyList(), emptyList(), explicit) },
                { _, _ -> error("Fixture does not invoke native publication; Root callback is required") },
                { feedback.set(it) }, null, { false })
            verify("cache factory refuses preparation outside original lexical intent", runCatching { factory.media.prepareLegacyDash(source.videoUrl, source.audioUrl, keys) }.isFailure)
            lateinit var stamped: PlaybackSource
            factory.media.withPlaybackIntent(12_345, false) { stamped = factory.media.prepareLegacyDash(source.videoUrl, source.audioUrl, keys) }
            verify("cache decorator preserves exact original lexical seek pause and receipt", stamped.startPositionSeconds == 12.345 && stamped.startPaused && stamped.authorizationReceipt == binding.receipt && stamped.nativeTransport != null)
            val registrations = cache.stats().registrations
            factory.media.withPlaybackIntent(0, true) { stamped = factory.media.prepareProgressive("file:///C:/fixture-only.mp4") }
            verify("unsupported transport is explicit typed Direct with unchanged original source", feedback.get().let { it is DesktopOriginalMediaCachePreparation.Direct && it.reason == DesktopOriginalMediaDirectReason.UNSUPPORTED_TRANSPORT } && stamped.nativeTransport == null && stamped.videoUrl == "file:///C:/fixture-only.mp4")
            verify("unsupported preparation does not allocate native byte capability", cache.stats().registrations == registrations)
            factory.media.withPlaybackIntent(0, true) { stamped = factory.media.prepareLegacyDash("file:///C:/fixture-legacy.mp4", null, emptyMap()) }
            verify("unsupported legacy input returns typed Direct before HTTP track construction", stamped.nativeTransport == null &&
                feedback.get() is DesktopOriginalMediaCachePreparation.Direct)
            var unsupportedAdaptive: PlaybackSource? = stamped
            factory.media.withPlaybackIntent(0, true) { unsupportedAdaptive = factory.media.prepareAdaptiveDash(
                adaptive.copy(videoTracks = listOf(DashVideo(baseUrl = "file:///C:/fixture-adaptive.mp4"))), emptyMap()) }
            verify("unsupported full adaptive path preserves original legacy fallback decision", unsupportedAdaptive == null &&
                feedback.get().let { it is DesktopOriginalMediaCachePreparation.Direct && it.reason == DesktopOriginalMediaDirectReason.UNSUPPORTED_TRANSPORT })

            val slowSource = source.copy(videoUrl = "$base/slow.mp4", audioUrl = null)
            val slowTracks = desktopOriginalLegacyByteTracks(slowSource, emptyList(), emptyList(), emptyMap())
            val slow = request.prepare(slowSource, slowTracks, null, null) { false } as DesktopOriginalMediaCachePreparation.Cached
            val responseReady = CompletableDeferred<okhttp3.Response>()
            val job = async(Dispatchers.IO) {
                val caller = checkNotNull(currentCoroutineContext()[Job])
                val response = slow.bound.probeCalls(caller) { alive.get() }.newCall(Request.Builder().url(slowSource.videoUrl).header("Range", "bytes=0-4095").build()).execute()
                responseReady.complete(response)
                try { delay(10_000); response.body.bytes() } finally { response.close() }
            }
            val response = withTimeout(5_000) { responseReady.await() }
            verify("real delayed probe delivered headers before body read", slowHeaders.await(2, TimeUnit.SECONDS))
            job.cancel()
            verify("actual CallerJob retirement rejects later response body read", runCatching { response.body.bytes() }.exceptionOrNull() is CancellationException)
            response.close(); job.cancelAndJoin()
            val leaseProbe = request.prepare(slowSource, slowTracks, null, null) { false } as DesktopOriginalMediaCachePreparation.Cached
            val leaseCall = leaseProbe.bound.probeCalls(checkNotNull(currentCoroutineContext()[Job])) { alive.get() }
                .newCall(Request.Builder().url(slowSource.videoUrl).header("Range", "bytes=0-4095").build())
            val leaseResponse = leaseCall.execute()
            leaseProbe.bound.close()
            verify("actual lease retirement immediately cancels the in-flight probe Call", leaseCall.isCanceled())
            verify("actual lease retirement rejects later response body read", runCatching { leaseResponse.body.bytes() }.exceptionOrNull() is CancellationException)
            leaseResponse.close()
            alive.set(false)
            verify("request entry retirement rejects late cache preparation", runCatching { request.prepare(source, tracks, null, null) { false } }.exceptionOrNull() is CancellationException)
        }
        verify("completed unaccepted request retires its prepared registration", preparedCarrier?.lease?.current() == false)
        println("{\"groups\":5,\"assertions\":$assertions,\"runtimeSnapshot\":70,\"explicitCandidateFamilyOverrides\":true,\"RootInstalledAcceptance\":false,\"newConsumerLocalIO\":true,\"nativeReplayed\":false,\"HTTPExternal\":false,\"account\":false,\"window\":false}")
    } finally { alive.set(false); entryJob.cancel(); cache.close(); origin.stop(0); workers.shutdownNow(); root.cancel() }
}
