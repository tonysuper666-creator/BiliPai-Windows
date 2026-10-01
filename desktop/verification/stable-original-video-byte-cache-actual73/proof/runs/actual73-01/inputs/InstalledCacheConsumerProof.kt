package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DashAudio
import com.android.purebilibili.data.model.response.DashVideo
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.policy.PlaybackQualityMode
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
    val threads=Executors.newFixedThreadPool(4) { Thread(it,"owned73-local-origin").apply { isDaemon=true } }
    origin.executor=threads
    val failBody=AtomicBoolean(false);val failures=AtomicInteger();val bodyRanges=AtomicInteger()
    val wire=CopyOnWriteArrayList<Map<String,String?>>()
    origin.createContext("/") { exchange ->
        try {
            val name=exchange.requestURI.path.substringAfterLast('/');val bytes=files.getValue(name)
            val rangeHeader=exchange.requestHeaders.getFirst("Range")
            val range=checkNotNull(parseMediaRange(rangeHeader,bytes.size.toLong()))
            wire+=mapOf("User-Agent" to exchange.requestHeaders.getFirst("User-Agent"),
                "Referer" to exchange.requestHeaders.getFirst("Referer"),"Cookie" to exchange.requestHeaders.getFirst("Cookie"))
            if (range.second>1) bodyRanges.incrementAndGet()
            if (exchange.requestURI.path.startsWith("/fail/") && failBody.get() && range.second>1) {
                failures.incrementAndGet();exchange.sendResponseHeaders(503,-1)
            } else {
                exchange.responseHeaders.add("ETag","\"owned-${exchange.requestURI.path}-v1\"")
                exchange.responseHeaders.add("Content-Type","video/mp4")
                if (rangeHeader!=null) exchange.responseHeaders.add("Content-Range","bytes ${range.first}-${range.first+range.second-1}/${bytes.size}")
                exchange.sendResponseHeaders(if (rangeHeader==null)200 else 206,range.second)
                exchange.responseBody.use { it.write(bytes,range.first.toInt(),range.second.toInt()) }
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
    suspend fun ready(value:DesktopOriginalVideoAcceptedPublication,audio:Boolean) {
        withTimeout(20_000) { target.frames.first { it?.sourceVersion==value.sourceVersion } }
        await("real native ACK/codecs/readback for source ${value.sourceVersion}") {
            owner.isCurrent(value) && player.state.value.let { it.ready && !it.loading && !it.ended && it.error==null &&
                it.failure==null && it.nativePaused!=null && it.videoCodec!=null && (!audio || it.audioCodec!=null) }
        }
    }
    try {
        player.startSoftwareTransport()
        val current=AtomicBoolean(true);val initialDecision=AtomicReference<DesktopOriginalMediaCachePreparation.Cached?>()
        val resolver=async(Dispatchers.IO) {
            val binding=binding();val job=checkNotNull(currentCoroutineContext()[Job])
            val f=factory(binding.captureMediaBytes(cache),null) { source,_->
                owner.publish(subject,source,player.currentSourceVersion,job) { current.get() }
            }
            var value:DesktopOriginalVideoAcceptedPublication?=null
            f.media.withPlaybackIntent(1_250,false) {
                val source=f.media.prepareLegacyDash("$base/dash-stream0.mp4","$base/dash-stream2.mp4",emptyMap())
                val cached=decisions.last() as DesktopOriginalMediaCachePreparation.Cached;initialDecision.set(cached)
                runBlocking { desktopOriginalLegacyByteTracks(source,emptyList(),emptyList(),emptyMap()).forEach { track ->
                    cached.bound.prefetchRange(track.url,track.cacheKey,0,files.getValue(track.url.substringAfterLast('/')).size.toLong(),track.headers)
                } }
                f.media.accept(source);value=owner.current()
            }
            val accepted=checkNotNull(value);ready(accepted,true);accepted
        }
        val initial=resolver.await();current.set(false)
        observe("initialRealAckResolverCompleted")
        val carrier=checkNotNull(initial.nativeSource.source.nativeTransport)
        verify("real cache factory lexical intent reaches NativeOwner publication unchanged", initial.nativeSource.source.startPositionSeconds==1.25 &&
            initial.nativeSource.source.startPaused && carrier.bound===checkNotNull(initialDecision.get()).bound)
        verify("actual paused native readback retains initial position", player.state.value.nativePaused==true && abs(player.state.value.positionSeconds-1.25)<0.20)
        verify("normal resolver completion preserves accepted carrier and real source job", resolver.isCompleted &&
            checkNotNull(sourceJobs[initial.sourceVersion]).isActive && owner.current()===initial && status(carrier.videoUri)==200)
        val initialBody=bodyRanges.get()
        owner.player.setPaused(false)
        await("real native resume advances the accepted source clock") { player.state.value.nativePaused==false && player.state.value.positionSeconds>1.5 }
        owner.player.setPaused(true)
        await("real native pause readback settles") { player.state.value.nativePaused==true }
        val clock=player.state.value
        val active=owner.acceptedMedia { expected->factory(acceptedRequest(expected),expected) { _,_->
            error("acceptedMedia owns actual recovery publication; delegate publisher must not be invoked")
        }.media }
        active.withPlaybackIntent((clock.positionSeconds*1000).toLong(),!clock.paused) {
            val source=active.prepareLegacyDash(initial.nativeSource.source.videoUrl,initial.nativeSource.source.audioUrl,emptyMap())
            verify("identical captured plan reuses exact accepted Bound and carrier", source.nativeTransport===carrier)
            active.accept(source)
        }
        val reused=checkNotNull(owner.current());ready(reused,true)
        observe("sameBoundRealRecovery")
        verify("actual accepted recovery keeps source version while replacing publication", reused.sourceVersion==initial.sourceVersion &&
            reused.nativeSource.source.nativeTransport===carrier && reused.nativeSource.source.nativePublication!==initial.nativeSource.source.nativePublication)
        verify("same Bound native read after recovery has no second origin body range", bodyRanges.get()==initialBody && cache.stats().servedCacheBytes>0)

        val manifest=(0..2).fold(Files.readString(media.resolve("dash.mpd"))) { text,i->text.replace(">dash-stream$i.mp4<",">$base/dash-stream$i.mp4<") }
        val adaptive=AdaptiveDashPlaybackSource(manifest,listOf(DashVideo(id=80,baseUrl="$base/dash-stream0.mp4"),
            DashVideo(id=64,baseUrl="$base/dash-stream1.mp4")),listOf(DashAudio(id=30280,baseUrl="$base/dash-stream2.mp4")),PlaybackQualityMode.AUTO)
        val mpdResolver=async(Dispatchers.IO) {
            val binding=binding();val job=checkNotNull(currentCoroutineContext()[Job])
            val f=factory(binding.captureMediaBytes(cache),null) { source,_->owner.publish(subject,source,player.currentSourceVersion,job) { live.get() } }
            f.media.withPlaybackIntent(750,false) {
                val source=checkNotNull(f.media.prepareAdaptiveDash(adaptive,emptyMap()))
                val cached=decisions.last() as DesktopOriginalMediaCachePreparation.Cached
                runBlocking { desktopOriginalAdaptiveByteTracks(adaptive,emptyMap(),capturedPlaybackMediaHeaders(source)).forEach { track ->
                    cached.bound.prefetchRange(track.url,track.cacheKey,0,files.getValue(track.url.substringAfterLast('/')).size.toLong(),track.headers)
                } }
                f.media.accept(source)
            }
            val value=checkNotNull(owner.current());ready(value,true);value
        }
        val mpd=mpdResolver.await();val mpdCarrier=checkNotNull(mpd.nativeSource.source.nativeTransport)
        verify("new actual factory load retires previous native capability", status(carrier.videoUri)==410 && mpd.sourceVersion>reused.sourceVersion)
        val mpdClock=player.state.value;val mpdBody=bodyRanges.get()
        val repeated=owner.acceptedMedia { expected->factory(acceptedRequest(expected),expected) { _,_->error("actual acceptedMedia owns recovery") }.media }
        repeated.withPlaybackIntent((mpdClock.positionSeconds*1000).toLong(),!mpdClock.paused) {
            val source=checkNotNull(repeated.prepareAdaptiveDash(adaptive,emptyMap()))
            verify("identical full MPD and complete tracks reuse actual native carrier", source.nativeTransport===mpdCarrier)
            repeated.accept(source)
        }
        val mpdReused=checkNotNull(owner.current());ready(mpdReused,true)
        observe("fullMpdSameBoundRealRecovery")
        val all=desktopOriginalAdaptiveByteTracks(adaptive,emptyMap(),capturedPlaybackMediaHeaders(mpdReused.nativeSource.source))
        verify("changed full MPD ranges cannot match installed native plan", !mpdCarrier.bound.matchesCapturedPlan(all,manifest.replace("877-36070","877-36071")))
        verify("full adaptive consumer retains all representations and original range rows", mpdCarrier.lease.manifest!!.toString(Charsets.UTF_8).let {
            Regex("<Representation\\b").findAll(it).count()==3 && it.contains("mediaRange=\"877-36070\"") && it.contains("range=\"0-764\"") })
        verify("same complete MPD native consumer reads warmed spans without another body range", bodyRanges.get()==mpdBody)

        // Legitimate failure injection is ONLY fixture-owned origin IO. No product
        // StateFlow/setter/reflection substitutes a native attempt or ACK.
        failBody.set(true)
        val failResolver=async(Dispatchers.IO) {
            val binding=binding();val job=checkNotNull(currentCoroutineContext()[Job])
            val f=factory(binding.captureMediaBytes(cache),null) { source,_->owner.publish(subject,source,player.currentSourceVersion,job) { live.get() } }
            f.media.withPlaybackIntent(1_375,false) {
                val source=f.media.prepareProgressive("$base/fail/dash-stream0.mp4");f.media.accept(source)
            }
            await("real local origin failure is published by MPV with an actual attempt") { player.state.value.failure?.attemptId?.let { it>0 }==true }
            checkNotNull(owner.current())
        }
        val failed=failResolver.await();val failure=checkNotNull(player.state.value.failure)
        val failedState=player.state.value;val failedCarrier=checkNotNull(failed.nativeSource.source.nativeTransport)
        observe("actualNativeFailedAttempt")
        verify("native failure came from actual captured byte IO", failures.get()>0 && failure.attemptId>0 && failedState.error!=null)
        verify("actual failed Reader position is valid without a source-position guess", failedState.positionSeconds.isFinite() && failedState.positionSeconds>=0.0)
        val recoveryIntent=DesktopOriginalVideoMediaIntent((failedState.positionSeconds*1000).toLong(),!failedState.paused)
        verify("wrong native failure attempt cannot recover or mutate current source", !recoverDesktopOriginalVideoDirectAfterCacheError(owner,failed,recoveryIntent,failure.attemptId+1) && owner.current()===failed)
        failBody.set(false)
        val registeredBefore=cache.stats().registrations
        verify("real owned native cache-error recovery queues original remote source", recoverDesktopOriginalVideoDirectAfterCacheError(owner,failed,recoveryIntent,failure.attemptId))
        val direct=checkNotNull(owner.current());ready(direct,false)
        observe("actualDirectRemoteAck")
        verify("direct native ACK retains source version and exact real Reader intent", direct.sourceVersion==failed.sourceVersion &&
            direct.nativeSource.source.nativeTransport==null && direct.nativeSource.source.videoUrl==failed.nativeSource.source.videoUrl &&
            direct.nativeSource.source.startPositionSeconds==recoveryIntent.startPositionMs/1000.0 && direct.nativeSource.source.startPaused==!recoveryIntent.playWhenReady)
        verify("direct native readback preserves actual requested pause and position", player.state.value.nativePaused==!recoveryIntent.playWhenReady &&
            abs(player.state.value.positionSeconds-recoveryIntent.startPositionMs/1000.0)<0.20)
        verify("cache-error direct recovery retires old local capability", status(failedCarrier.videoUri)==410 && status(mpdCarrier.videoUri)==410)
        verify("direct recovery allocates no cache retry registration", cache.stats().registrations<registeredBefore && direct.nativeSource.source.nativeTransport==null)
        verify("old failure/source identity cannot start a second recovery", !recoverDesktopOriginalVideoDirectAfterCacheError(owner,failed,recoveryIntent,failure.attemptId) &&
            !recoverDesktopOriginalVideoDirectAfterCacheError(owner,direct,recoveryIntent,failure.attemptId) && owner.current()===direct)
        verify("installed same Repository tag preserves deliberately empty cached origin headers", wire.isNotEmpty() && wire.take(initialBody).all { row->row.values.all { it=="" } })
        owner.close()
        observe("actualOwnerClosed")
        verify("entry close retires actual accepted native ownership", owner.current()==null)
        println("{\"snapshot\":$snapshot,\"groups\":5,\"assertions\":$checks,\"productionOverrides\":0,\"actualCachedFactory\":true,\"actualLexicalIntent\":true,\"actualNativeFailureAttempt\":true,\"actualDirectRecoveryAck\":true,\"fullMpd\":true,\"MainShell\":false,\"realAccount\":false,\"HTTPExternal\":false,\"window\":false,\"OSInput\":false}")
    } finally { owner.close();player.close();cache.close();live.set(false);entryJob.cancel();sourceJobs.values.forEach { it.cancel() };origin.stop(0);threads.shutdownNow();root.cancel() }
}
