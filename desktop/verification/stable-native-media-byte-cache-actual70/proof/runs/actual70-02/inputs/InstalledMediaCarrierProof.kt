package com.bilipai.desktop.player.cache

import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.player.ShuffleProgress
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.data.VideoPart
import com.bilipai.desktop.player.*
import com.bilipai.desktop.ui.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private var assertions = 0
private fun verify(label: String, value: Boolean) { check(value) { label }; assertions++; println("PASS $label") }
private suspend fun await(label: String, test: () -> Boolean) {
    withTimeout(15_000) { while (!test()) delay(20) }; verify(label, true)
}
private fun responseCode(url: String): Int = (URI(url).toURL().openConnection() as HttpURLConnection).let {
    try { it.connectTimeout = 3_000; it.readTimeout = 3_000; it.responseCode } finally { it.disconnect() }
}

fun main(args: Array<String>) = runBlocking {
    val owned = Path.of(args[0]); Files.createDirectories(owned)
    val media = Path.of(args[1])
    val files = (0..2).associate { "dash-stream$it.mp4" to Files.readAllBytes(media.resolve("dash-stream$it.mp4")) }
    val root = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = DesktopSessionStore(owned.resolve("fixture-session"), persistent = false)
    val repo = DesktopRepository(store)
    val live = AtomicBoolean(true); val entryLock = Any()
    val auth = repo.capturePlaybackAuthorization(repo.sessionEpoch) { live.get() }
    val namespace = repo.capturePlaybackCachePartition(auth.receipt) { live.get() }
    val requests = CopyOnWriteArrayList<Map<String, String?>>()
    val bodyRanges = AtomicInteger()
    val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val threads = Executors.newFixedThreadPool(4) { Thread(it, "fixture-owned-range-origin").apply { isDaemon = true } }
    origin.executor = threads
    origin.createContext("/") { exchange ->
        try {
            val name = exchange.requestURI.path.substringAfterLast('/')
            val bytes = files.getValue(name)
            val range = checkNotNull(parseMediaRange(exchange.requestHeaders.getFirst("Range"), bytes.size.toLong()))
            requests += linkedMapOf("User-Agent" to exchange.requestHeaders.getFirst("User-Agent"),
                "Referer" to exchange.requestHeaders.getFirst("Referer"), "Cookie" to exchange.requestHeaders.getFirst("Cookie"),
                "Accept-Encoding" to exchange.requestHeaders.getFirst("Accept-Encoding"))
            if (range.second > 1) bodyRanges.incrementAndGet()
            exchange.responseHeaders.add("ETag", "\"owned-$name-v1\"")
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Content-Range", "bytes ${range.first}-${range.first + range.second - 1}/${bytes.size}")
            exchange.sendResponseHeaders(206, range.second)
            exchange.responseBody.use { it.write(bytes, range.first.toInt(), range.second.toInt()) }
        } catch (_: java.io.IOException) { /* cancellation is observed by the actual consumer */ }
        finally { exchange.close() }
    }
    origin.start()
    val base = "http://127.0.0.1:${origin.address.port}"
    // The real same SessionStore deliberately rejects HTTP localhost cookies.
    // This records its actual domain boundary; local wire proof must not claim Bilibili-cookie replay.
    store.saveFromResponse(base.toHttpUrl(), listOf(Cookie.Builder().name("fixture_cookie")
        .value("fixture-owned-value").hostOnlyDomain("127.0.0.1").path("/").build()))
    val headers = capturedPlaybackMediaHeaders(PlaybackSource("$base/dash-stream0.mp4",
        referer = "", userAgent = "", cookieHeader = "", streamHeaders = mapOf("referer" to "", "User-Agent" to "")))
    val tracks = (0..2).map { i -> capturedDesktopMediaByteTrack("$base/dash-stream$i.mp4", emptyList(),
        "fixture-track-$i", "complete-original-representation-$i", headers) }
    fun gate(block: () -> Unit): Boolean = synchronized(entryLock) { if (!live.get()) false else { block(); true } }
    fun admission(job: Job, current: () -> Boolean) = DesktopMediaByteRepositoryAdmission(repo, auth, namespace, job, current, ::gate)
    val cache = DesktopMediaByteCache(owned.resolve("cache"), root, repo)
    val target = MpvSoftwareTarget().apply { resize(160, 90) }
    val player = MpvPlayer(softwareTarget = target, useNullAudioOutput = true)
    val owners = mutableListOf<DesktopOriginalVideoNativeOwner>()
    val sourceJobs = CopyOnWriteArrayList<Job>()
    val lastAccepted = AtomicReference<DesktopOriginalVideoAcceptedPublication?>()
    fun owner(): DesktopOriginalVideoNativeOwner = DesktopOriginalVideoNativeOwner(player,
        DesktopRepositoryPlaybackPublication(repo), { repo.sessionEpoch }, { false }, { live.get() }, ::gate,
        { lastAccepted.set(it) }, { accepted, stillOwned ->
            check(accepted.accountEpoch == auth.receipt.accountEpoch)
            val sourceJob = Job(root.coroutineContext[Job]); sourceJobs += sourceJob
            admission(sourceJob, stillOwned)
        }).also { owners += it }
    val request = PlaybackRequest.create("BV1fixture70", 7001, 7002)
    suspend fun ready(version: Long, audio: Boolean) {
        withTimeout(15_000) { target.frames.first { it?.sourceVersion == version } }
        await("actual native source $version readback ready with real codecs") {
            player.state.value.let { it.ready && !it.loading && !it.ended && it.nativePaused != null &&
                it.videoCodec != null && (!audio || it.audioCodec != null) }
        }
    }
    try {
        verify("actual SessionStore produces stable bounded effective partition", namespace.matches(Regex("[a-f0-9]{64}")) &&
            namespace == repo.capturePlaybackCachePartition(auth.receipt) { live.get() })
        verify("same actual SessionStore rejects out-of-scope localhost cookie",
            store.loadForRequest(base.toHttpUrl()).isEmpty())
        val resolver = Job(root.coroutineContext[Job]); val requestCurrent = AtomicBoolean(true)
        val bound = cache.bind(admission(resolver) { live.get() && requestCurrent.get() }, tracks)
        tracks.forEachIndexed { i, track -> bound.prefetchRange(track.url, track.cacheKey, 0,
            files.getValue("dash-stream$i.mp4").size.toLong(), headers) }
        verify("actual Repository final network tag keeps explicit empty UA Referer Cookie on wire", requests.isNotEmpty() &&
            requests.all { row -> listOf("User-Agent", "Referer", "Cookie").all { row[it] == "" } && row["Accept-Encoding"] == "identity" })
        val warmRanges = bodyRanges.get()
        val remote = PlaybackSource(tracks[0].url, audioUrl = tracks[2].url, referer = "", userAgent = "",
            cookieHeader = "", authorizationReceipt = auth.receipt, startPaused = true, title = "Owned separate streams")
        val carrier = bound.prepareNativeTransport(remote)
        val firstOwner = owner()
        val accepted = firstOwner.publish(request, remote.copy(nativeTransport = carrier), player.currentSourceVersion,
            resolver) { requestCurrent.get() }
        player.startSoftwareTransport(); ready(accepted.sourceVersion, true)
        verify("actual native owner stores remote fields and same nativeTransport object", accepted.nativeSource.source.videoUrl == remote.videoUrl &&
            accepted.nativeSource.source.audioUrl == remote.audioUrl && accepted.nativeSource.source.nativeTransport === carrier &&
            player.currentSourceSnapshot()?.source?.nativeTransport === carrier)
        verify("actual MPV consumes warmed separate video and audio without second body range",
            bodyRanges.get() == warmRanges && cache.stats().servedCacheBytes > 0)
        resolver.complete(); requestCurrent.set(false)
        verify("normal completed resolver is not accepted native source job", resolver.isCompleted && sourceJobs.single().isActive)
        verify("actual ACK replaces transient request guard for live carrier", firstOwner.current() === accepted &&
            responseCode(carrier.videoUri) == 200 && responseCode(checkNotNull(carrier.audioUri)) == 200)
        val oldSourceJob = sourceJobs.single()
        verify("same actual MPV source barrier drains before adoption", player.drainSourceCommands(accepted.sourceVersion))
        val details = VideoDetails("BV1fixture70", 7001, "Fixture owned", "", "", "", 0, 0,
            listOf(VideoPart(7002, "Fixture local media", 4)), raw = ViewInfo(bvid = "BV1fixture70", aid = 7001, cid = 7002))
        val resolved = com.bilipai.desktop.data.PlaybackSource(remote.videoUrl, remote.audioUrl, remote.title, remote.referer,
            authorizationReceipt = auth.receipt)
        val queue = DesktopOrdinaryPlaybackQueueHandoff(listOf(VideoCard(details.bvid, details.title, "", "", 0, 4)),
            0, Any(), ShuffleProgress(), ShuffleProgress())
        val handoff = DesktopOrdinaryPlaybackHandoff(player, details, 0, resolved, checkNotNull(player.currentSourceSnapshot()),
            player.state.value, queue, null, false, null)
        val nextOwner = owner(); val adopted = checkNotNull(nextOwner.adopt(handoff))
        oldSourceJob.cancel(); firstOwner.close()
        verify("actual owner adoption transfers same version carrier and publication identity", adopted.sourceVersion == accepted.sourceVersion &&
            adopted.nativeSource.source.nativeTransport === carrier && adopted.nativeSource.source.nativePublication !== accepted.nativeSource.source.nativePublication)
        verify("old source job cancellation and old owner close preserve adopted read capability",
            nextOwner.current() === adopted && responseCode(carrier.videoUri) == 200 && responseCode(checkNotNull(carrier.audioUri)) == 200)
        player.setPaused(false)
        await("same MPV playback clock advances after real owner adoption") { player.state.value.positionSeconds > 0.25 }
        player.setPaused(true)
        verify("actual adopted reader still uses warmed original ranges", bodyRanges.get() == warmRanges)

        val mpdResolver = Job(root.coroutineContext[Job]); val mpdCurrent = AtomicBoolean(true)
        val mpdBound = cache.bind(admission(mpdResolver) { live.get() && mpdCurrent.get() }, tracks)
        tracks.forEachIndexed { i, track -> mpdBound.prefetchRange(track.url, track.cacheKey, 0,
            files.getValue("dash-stream$i.mp4").size.toLong(), headers) }
        val original = Files.readString(media.resolve("dash.mpd"))
        val manifest = (0..2).fold(original) { text, i -> text.replace(">dash-stream$i.mp4<", ">$base/dash-stream$i.mp4<") }
        val mpdRemote = remote.copy(title = "Owned complete MPD")
        val mpdCarrier = mpdBound.prepareNativeTransport(mpdRemote, manifest)
        val rewritten = checkNotNull(mpdCarrier.lease.manifest).toString(Charsets.UTF_8)
        fun attrs(text: String, name: String) = Regex("$name=\"[^\"]*\"").findAll(text).map { it.value }.toList()
        verify("complete MPD keeps every original representation and exact range table",
            Regex("<Representation\\b").findAll(rewritten).count() == 3 &&
                listOf("id", "bandwidth", "codecs", "range", "mediaRange", "indexRange").all { attrs(manifest, it) == attrs(rewritten, it) } &&
                Regex("<AdaptationSet\\b").findAll(rewritten).count() == 2)
        val mpdAccepted = nextOwner.publish(request, mpdRemote.copy(nativeTransport = mpdCarrier), player.currentSourceVersion,
            mpdResolver) { mpdCurrent.get() }
        ready(mpdAccepted.sourceVersion, true)
        verify("actual new load invalidates previous local capabilities", mpdAccepted.sourceVersion > adopted.sourceVersion &&
            responseCode(carrier.videoUri) == 410 && responseCode(checkNotNull(carrier.audioUri)) == 410)
        verify("actual full MPD consumer keeps remote semantic fields and actual carrier", player.currentSourceSnapshot()?.source?.let {
            it.videoUrl == tracks[0].url && it.audioUrl == tracks[2].url && it.nativeTransport === mpdCarrier } == true && mpdCarrier.audioUri == null)
        mpdResolver.complete(); mpdCurrent.set(false)
        verify("actual complete MPD stays readable after resolver normal completion and real ACK",
            nextOwner.current() === mpdAccepted && responseCode(mpdCarrier.videoUri) == 200)
        verify("actual complete MPD video audio decode reads warmed spans without second body range", bodyRanges.get() == warmRanges &&
            player.state.value.videoCodec != null && player.state.value.audioCodec != null)
        nextOwner.close()
        verify("actual current owner close revokes complete MPD capability", responseCode(mpdCarrier.videoUri) == 410 && nextOwner.current() == null)
        verify("actual bounded cache includes all committed disk headers", cache.stats().diskBytesIncludingStaging <= 128L * 1024 * 1024)
        println("{\"snapshot\":70,\"groups\":4,\"assertions\":$assertions,\"productionOverrides\":0,\"actualNativeCarrier\":true,\"actualStorePartition\":true,\"actualFinalWireHeaders\":true,\"actualOwnerPublishAckAdopt\":true,\"fullAdaptiveMpdNative\":true,\"realAccount\":false,\"MainShell\":false,\"window\":false,\"OSInput\":false}")
    } finally {
        owners.forEach { it.close() }; player.close(); cache.close(); live.set(false)
        sourceJobs.forEach { it.cancel() }; origin.stop(0); threads.shutdownNow(); root.cancel()
    }
}
