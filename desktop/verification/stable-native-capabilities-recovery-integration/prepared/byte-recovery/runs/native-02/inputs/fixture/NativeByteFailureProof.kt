package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DashAudio
import com.android.purebilibili.data.model.response.DashVideo
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.policy.PlaybackQualityMode
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopBlockedUpStore
import com.bilipai.desktop.data.DesktopDiscoveryRepository
import com.bilipai.desktop.data.DesktopDiscoveryPreferences
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.cache.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

private var checks = 0
private fun verify(label: String, value: Boolean) { check(value) { label }; checks++; println("PASS $label") }
private suspend fun await(label: String, condition: () -> Boolean) {
    withTimeout(20_000) { while (!condition()) delay(25) }; verify(label, true)
}
private fun status(url: String): Int = (URI(url).toURL().openConnection() as HttpURLConnection).let {
    try { it.connectTimeout=3_000;it.readTimeout=3_000;it.responseCode } finally { it.disconnect() }
}

fun main(args: Array<String>) = runBlocking {
    val owned=Path.of(args[0]);Files.createDirectories(owned)
    val media=Path.of(args[1]);val snapshot=args[2].toInt()
    val files=(0..2).associate { "dash-stream$it.mp4" to Files.readAllBytes(media.resolve("dash-stream$it.mp4")) }
    val root=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val entryJob=Job(root.coroutineContext[Job]);val live=AtomicBoolean(true);val entryLock=Any()
    fun gate(action:()->Unit):Boolean=synchronized(entryLock) { if (!live.get() || !entryJob.isActive) false else { action();true } }
    val store=DesktopSessionStore(owned.resolve("session"),persistent=false)
    val repo=DesktopRepository(store)
    val auth=repo.capturePlaybackAuthorization(repo.sessionEpoch) { live.get() && entryJob.isActive }
    val partition=repo.capturePlaybackCachePartition(auth.receipt) { live.get() && entryJob.isActive }
    val cache=DesktopMediaByteCache(owned.resolve("cache"),root,repo)
    val origin=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val threads=Executors.newFixedThreadPool(4) { Thread(it,"owned-byte-failure-origin").apply { isDaemon=true } }
    origin.executor=threads
    val failBody=AtomicBoolean(false);val corruptBody=AtomicBoolean(false);val failures=AtomicInteger();val bodyRanges=AtomicInteger()
    val wire=CopyOnWriteArrayList<Map<String,String?>>()
    origin.createContext("/") { exchange ->
        try {
            val name=exchange.requestURI.path.substringAfterLast('/');val bytes=files.getValue(name)
            val rangeHeader=exchange.requestHeaders.getFirst("Range")
            val range=checkNotNull(parseMediaRange(rangeHeader,bytes.size.toLong()))
            wire+=mapOf("User-Agent" to exchange.requestHeaders.getFirst("User-Agent"),
                "Referer" to exchange.requestHeaders.getFirst("Referer"),"Cookie" to exchange.requestHeaders.getFirst("Cookie"))
            if (range.second>1) bodyRanges.incrementAndGet()
            val corrupt=exchange.requestURI.path.startsWith("/fail/") && corruptBody.get()
            if (exchange.requestURI.path.startsWith("/fail/") && failBody.get() && range.second>1) {
                failures.incrementAndGet();exchange.sendResponseHeaders(503,-1)
            } else {
                exchange.responseHeaders.add("ETag","\"owned-${exchange.requestURI.path}-${if(corrupt)"corrupt-v1"else"healthy-v2"}\"")
                exchange.responseHeaders.add("Content-Type","video/mp4")
                if (rangeHeader!=null) exchange.responseHeaders.add("Content-Range","bytes ${range.first}-${range.first+range.second-1}/${bytes.size}")
                exchange.sendResponseHeaders(if (rangeHeader==null)200 else 206,range.second)
                exchange.responseBody.use { if(corrupt) { failures.incrementAndGet();it.write(ByteArray(range.second.toInt())) }
                    else it.write(bytes,range.first.toInt(),range.second.toInt()) }
            }
        } catch (_:java.io.IOException) { } finally { exchange.close() }
    }
    origin.start();val base="http://127.0.0.1:${origin.address.port}"
    val target=MpvSoftwareTarget().apply { resize(160,90) }
    val player=MpvPlayer(softwareTarget=target,useNullAudioOutput=true)
    val sourceJobs=ConcurrentHashMap<Long,Job>()
    val acceptedRef=AtomicReference<DesktopOriginalVideoAcceptedPublication?>()
    val decisions=CopyOnWriteArrayList<DesktopOriginalMediaCachePreparation>()
    val owner=DesktopOriginalVideoNativeOwner(player,DesktopRepositoryPlaybackPublication(repo),
        { repo.sessionEpoch },{ player.state.value.muted },{ live.get() && entryJob.isActive },::gate,
        { acceptedRef.set(it) },{ value,stillOwned ->
            check(value.accountEpoch==auth.receipt.accountEpoch)
            val sourceJob=sourceJobs.computeIfAbsent(value.sourceVersion) { Job(entryJob) }
            DesktopMediaByteRepositoryAdmission(repo,auth,partition,sourceJob,stillOwned,::gate)
        })
    val subject=PlaybackRequest.create("BV1fixture73",7301,7302)
    fun observe(label:String) {
        val native=player.state.value;val current=owner.current()
        val value="{\"event\":\"$label\",\"sourceVersion\":${current?.sourceVersion},\"cacheCarrier\":${current?.nativeSource?.source?.nativeTransport!=null},"+
            "\"ready\":${native.ready},\"loading\":${native.loading},\"ended\":${native.ended},\"positionSeconds\":${native.positionSeconds},"+
            "\"requestedPause\":${native.paused},\"nativePaused\":${native.nativePaused},\"failureAttemptId\":${native.failure?.attemptId},"+
            "\"registrations\":${cache.stats().registrations},\"sourceJobActive\":${current?.sourceVersion?.let { sourceJobs[it]?.isActive }}}\n"
        Files.writeString(owned.parent.resolve("native-observations.jsonl"),value,
            java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
    }
    suspend fun binding():DesktopOriginalVideoRepositoryBinding=DesktopOriginalVideoRepositoryBinding.capture(
        repo,repo.sessionEpoch,entryJob,{ live.get() },::gate,PlayerPreferences(),null,emptySet(),false,
        { false },{ false },{ false },{ false },{ _,_->false })
    fun semantic(video:String,audio:String?):PlaybackSource=PlaybackSource(video,audio,referer="",userAgent="",
        cookieHeader="",title="Fixture owned consumer",authorizationReceipt=auth.receipt)
    fun factory(request:DesktopOriginalVideoByteCacheRequest,reuse:DesktopOriginalVideoAcceptedPublication?,
        publish:(PlaybackSource,DesktopOriginalVideoMediaIntent)->DesktopOriginalVideoAcceptedPublication):DesktopOriginalVideoCachedMediaFactory =
        DesktopOriginalVideoCachedMediaFactory(request,
            { video,audio,_->semantic(video,audio) },
            { adaptive,_->semantic(adaptive.videoTracks.first().getValidUrl(),adaptive.audioTracks.firstOrNull()?.getValidUrl()) },
            { url->semantic(url,null) },
            { raw,keys->desktopOriginalLegacyByteTracks(raw,emptyList(),emptyList(),keys) },
            publish,{ decisions+=it },reuse,owner::isCurrent)
    fun acceptedRequest(value:DesktopOriginalVideoAcceptedPublication):DesktopOriginalVideoByteCacheRequest {
        val version=value.sourceVersion;val job=checkNotNull(sourceJobs[version])
        fun current():Boolean=live.get() && entryJob.isActive && player.ownsSourceVersion(version)
        val admission=DesktopMediaByteRepositoryAdmission(repo,auth,partition,job,::current,::gate)
        return DesktopOriginalVideoByteCacheRequest(cache,admission) {
            admission.check();if (!current()) throw CancellationException("Fixture accepted source retired")
        }
    }
    val pluginStore = DesktopPluginStore(owned.resolve("plugins"))
    val pluginContext = DesktopPluginContext(pluginStore)
    val blocked = DesktopBlockedUpStore(pluginContext)
    val community = DesktopCommunityRepository(repo, blocked)
    val discovery = DesktopDiscoveryRepository(repo, DesktopDiscoveryPreferences(owned.resolve("discovery"), blocked))
    val runtime = DesktopPluginRuntime(pluginStore, repo, community, discovery)
    val statusBinding = binding()
    val statuses = object : DesktopOriginalVideoPlaybackStatus {
        override fun isPlaybackLoggedIn() = statusBinding.rawRepository.isPlaybackLoggedIn()
        override fun isPlaybackVip() = statusBinding.rawRepository.isPlaybackVip()
        override fun isUsingDedicatedPlaybackAccount() = statusBinding.rawRepository.isUsingDedicatedPlaybackAccount()
        override fun isAppApiCoolingDown() = statusBinding.rawRepository.isAppApiCoolingDown()
    }
    val invocations = DesktopOriginalVideoPlaybackInvocationPorts(CoroutineScope(entryJob + Dispatchers.Default),
        { live.get() }, {
            val b = binding(); val requestJob = checkNotNull(currentCoroutineContext()[Job])
            val mediaPort = factory(b.captureMediaBytes(cache), null) { source, _ ->
                owner.publish(subject, source, player.currentSourceVersion, requestJob) { live.get() }
            }.media
            DesktopOriginalVideoPlaybackInvocation(b.rawRepository, mediaPort, b::assertCurrent)
        }, statuses, { owner.acceptedMedia { expected -> factory(acceptedRequest(expected), expected) { _, _ ->
            error("accepted NativeOwner, not delegate publisher, owns recovery")
        }.media } })
    // No Runtime onVideoLoad was performed: this is the actual absent generation,
    // while observer is intentionally independent of enabled plugins/dispatch.
    val runtimeGeneration = AtomicReference<Long?>()
    val bridge = DesktopOriginalVideoOwnerPluginBridge(runtime, owner, invocations,
        { runtimeGeneration.get() }, { statusBinding::admitCurrentMutation }, { expected ->
            repo.ownedPlaybackCallFactory(auth) { owner.isCurrent(expected) }
        })
    suspend fun ready(value:DesktopOriginalVideoAcceptedPublication,audio:Boolean) {
        withTimeout(20_000) { target.frames.first { it?.sourceVersion==value.sourceVersion } }
        await("real native ACK/codecs/readback for source ${value.sourceVersion}") {
            owner.isCurrent(value) && player.state.value.let { it.ready && !it.loading && !it.ended && it.error==null &&
                it.failure==null && it.nativePaused!=null && it.videoCodec!=null && (!audio || it.audioCodec!=null) }
        }
    }
    try {
        player.startSoftwareTransport()
        failBody.set(true)
        val resolver = async(Dispatchers.IO) {
            val b = binding(); val job = checkNotNull(currentCoroutineContext()[Job])
            val f = factory(b.captureMediaBytes(cache), null) { source, _ ->
                owner.publish(subject, source, player.currentSourceVersion, job) { live.get() }
            }
            f.media.withPlaybackIntent(1_375, false) {
                f.media.accept(f.media.prepareProgressive("$base/fail/dash-stream0.mp4"))
            }
            checkNotNull(owner.current())
        }
        val failed = resolver.await()
        val carrier = checkNotNull(failed.nativeSource.source.nativeTransport)
        await("real HTTP503 byte read publishes a typed current native lease event") {
            failures.get() > 0 && carrier.lease.latestNativeFailure() != null
        }
        val event = checkNotNull(carrier.lease.latestNativeFailure())
        val readback = player.state.value
        observe("real503EventBeforeObserver")
        verify("503 event is body IO, not manufactured MPV demux failure", event.stage == DesktopNativeByteFailureStage.BODY_READ && readback.failure == null)
        verify("normal completed resolver leaves accepted source job and failure frame live", resolver.isCompleted && owner.current() === failed && checkNotNull(sourceJobs[failed.sourceVersion]).isActive)
        verify("event fixes exact source version, publication, receipt and current lease", event.sourceVersion == failed.sourceVersion &&
            event.stamp.publication === failed.nativeSource.source.nativePublication && event.stamp.receipt == auth.receipt && carrier.lease.ownsNativeFailure(event))
        verify("bounded event carries no origin URL, cookies or exception text", !event.toString().contains(base) && !event.toString().contains("Cookie") && !event.toString().contains("Origin did not"))
        verify("buffering Reader has real requested pause and valid position", readback.paused && readback.positionSeconds.isFinite() && readback.positionSeconds >= 0.0)

        failBody.set(false)
        // Exercise actual Bridge entry through its actual captured Invocation
        // scope, like the audited original observer before plugin/isPlaying tests.
        val observer = invocations.launch { bridge.observeInheritedPluginMute() }
        observer.join()
        verify("actual Bridge observer recovers with no native failure attempt", !observer.isCancelled && owner.current() !== failed)
        val direct = checkNotNull(owner.current()); ready(direct, false)
        observe("503DirectRealAck")
        verify("direct native ACK keeps semantic URL, source version and authorization", direct.sourceVersion == failed.sourceVersion &&
            direct.nativeSource.source.nativeTransport == null && direct.nativeSource.source.videoUrl == failed.nativeSource.source.videoUrl &&
            direct.nativeSource.source.authorizationReceipt == failed.nativeSource.source.authorizationReceipt)
        verify("direct source uses actual buffered position and desired pause", direct.nativeSource.source.startPositionSeconds == readback.positionSeconds && direct.nativeSource.source.startPaused == readback.paused)
        verify("real native readback preserves requested pause and position", player.state.value.nativePaused == readback.paused && abs(player.state.value.positionSeconds - readback.positionSeconds) < 0.20)
        verify("recover retires failed capability and event, without cache retry", status(carrier.videoUri) == 410 && cache.stats().registrations == 0 && carrier.lease.latestNativeFailure() == null)
        verify("same failure cannot recover twice or target direct source", !owner.recoverDirectAfterByteFailure(failed, event) && !owner.recoverDirectAfterByteFailure(direct, event))
        val before = player.currentSourceSnapshot()
        bridge.observeInheritedPluginMute()
        verify("observer after direct ACK cannot recreate cache/load loop", player.currentSourceSnapshot() == before && owner.current() === direct && cache.stats().registrations == 0)

        // A second real failure is captured before a new native source replaces
        // it. The old event must not mutate the subsequent healthy publication.
        failBody.set(true)
        val second = async(Dispatchers.IO) {
            val b = binding(); val job = checkNotNull(currentCoroutineContext()[Job])
            val f = factory(b.captureMediaBytes(cache), null) { source, _ -> owner.publish(subject, source, player.currentSourceVersion, job) { live.get() } }
            f.media.withPlaybackIntent(500, false) { f.media.accept(f.media.prepareProgressive("$base/fail/dash-stream1.mp4")) }
            checkNotNull(owner.current())
        }.await()
        val oldCarrier = checkNotNull(second.nativeSource.source.nativeTransport)
        await("second real failed IO produces its own fixed publication event") { oldCarrier.lease.latestNativeFailure() != null }
        val oldEvent = checkNotNull(oldCarrier.lease.latestNativeFailure())
        failBody.set(false)
        val finalResolver = async(Dispatchers.IO) {
            val b = binding(); val job = checkNotNull(currentCoroutineContext()[Job])
            val f = factory(b.captureMediaBytes(cache), null) { source, _ -> owner.publish(subject, source, player.currentSourceVersion, job) { live.get() } }
            f.media.withPlaybackIntent(500, false) { f.media.accept(f.media.prepareProgressive("$base/dash-stream1.mp4")) }
            checkNotNull(owner.current())
        }
        val final = finalResolver.await(); ready(final, false)
        verify("new load retires old typed event and local capability", final.sourceVersion > second.sourceVersion && status(oldCarrier.videoUri) == 410 && oldCarrier.lease.latestNativeFailure() == null)
        verify("retired event cannot recover newer accepted identity", !owner.recoverDirectAfterByteFailure(final, oldEvent) && !owner.recoverDirectAfterByteFailure(second, oldEvent) && owner.current() === final)
        owner.close()
        verify("closed owner cannot recover retained old IO event", !owner.recoverDirectAfterByteFailure(second, oldEvent) && owner.current() == null)
        observe("ownerRetired")
        println("{\"snapshot\":$snapshot,\"groups\":3,\"assertions\":$checks,\"declaredProductionFamilies\":3,\"real503\":true,\"actualBridge\":true,\"actualDirectNativeAck\":true,\"wholeRootVM\":false,\"MainShell\":false,\"realAccount\":false,\"HTTPExternal\":false,\"OSInput\":false}")
    } finally {
        owner.close();player.close();cache.close();live.set(false);entryJob.cancel();sourceJobs.values.forEach { it.cancel() }
        invocations.close();runtime.shutdownForRestore();origin.stop(0);threads.shutdownNow();root.cancel()
    }
}
