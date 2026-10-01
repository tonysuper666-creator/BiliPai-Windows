package com.bilipai.desktop.player.cache

import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun main(args:Array<String>) = runBlocking {
    val owned=Path.of(args[0]);Files.createDirectories(owned)
    val media=Path.of(args[2])
    val files=(0..2).associate { "dash-stream$it.mp4" to Files.readAllBytes(media.resolve("dash-stream$it.mp4")) }
    val origin=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val workers=Executors.newFixedThreadPool(4){r->Thread(r,"fixture-mpd-origin").apply{isDaemon=true}}
    origin.executor=workers
    val bodyHits=AtomicInteger();val reads=ConcurrentHashMap.newKeySet<String>()
    origin.createContext("/"){exchange ->
        try {
            val name=exchange.requestURI.path.substringAfterLast('/');val bytes=files.getValue(name)
            val range=checkNotNull(parseMediaRange(exchange.requestHeaders.getFirst("Range"),bytes.size.toLong()))
            if(range.second>1)bodyHits.incrementAndGet()
            reads+=name
            exchange.responseHeaders.add("ETag","\"fixture-$name-v1\"")
            exchange.responseHeaders.add("Content-Range","bytes ${range.first}-${range.first+range.second-1}/${bytes.size}")
            exchange.sendResponseHeaders(206,range.second)
            exchange.responseBody.use{it.write(bytes,range.first.toInt(),range.second.toInt())}
        }catch(_:java.io.IOException){}finally{exchange.close()}
    }
    origin.start()
    val base="http://127.0.0.1:${origin.address.port}"
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val store=DesktopSessionStore(owned.resolve("session"),persistent=false);val repo=DesktopRepository(store)
    val ownedFlag=AtomicBoolean(true);val gate=Any();val auth=repo.capturePlaybackAuthorization(repo.sessionEpoch){ownedFlag.get()}
    val namespace=mediaDigest("fixture complete MPD effective guest partition")
    fun admission(job:Job,current:()->Boolean)=DesktopMediaByteRepositoryAdmission(repo,auth,namespace,job,current){block->synchronized(gate){if(!ownedFlag.get())false else{block();true}}}
    val request=Job(scope.coroutineContext[Job]);val nativeJob=Job(scope.coroutineContext[Job])
    val headers=mapOf("Referer" to "https://www.bilibili.com/","User-Agent" to "Fixture full MPD")
    val tracks=(0..2).map{index->DesktopMediaByteTrack("$base/dash-stream$index.mp4",emptyList(),"mpd-track-$index","original-full-representation-$index",headers)}
    val cache=DesktopMediaByteCache(owned.resolve("cache"),scope)
    val target=MpvSoftwareTarget().apply{resize(160,90)};val player=MpvPlayer(softwareTarget=target,useNullAudioOutput=true)
    var assertions=0
    fun verify(label:String,value:Boolean){check(value){label};assertions++;println("PASS $label")}
    try {
        val bound=cache.bind(admission(request){ownedFlag.get()},tracks)
        tracks.forEachIndexed{index,track->bound.prefetchRange(track.url,track.cacheKey,0,files.getValue("dash-stream$index.mp4").size.toLong(),headers)}
        val original=Files.readString(media.resolve("dash.mpd"))
        val manifest=(0..2).fold(original){text,index->text.replace(">dash-stream$index.mp4<",">$base/dash-stream$index.mp4<")}
        val remote=PlaybackSource(tracks[0].url,audioUrl=tracks[2].url,referer=headers.getValue("Referer"),userAgent=headers.getValue("User-Agent"),authorizationReceipt=auth.receipt)
        val carrier=bound.prepareNativeTransport(remote,manifest)
        val rewritten=checkNotNull(carrier.lease.manifest).toString(Charsets.UTF_8)
        verify("all three original MPD representations retained",Regex("<Representation\\b").findAll(rewritten).count()==3)
        verify("video/audio adaptation and exact range tables retained",rewritten.contains("mediaRange=\"877-36070\"")&&rewritten.contains("range=\"0-764\"")&&rewritten.contains("codecs=\"mp4a.40.2\""))
        verify("MPD has no external origin transport address",!rewritten.contains(base)&&carrier.audioUri==null)
        val before=bodyHits.get()
        val pub=DesktopNativePlaybackPublication{command->try{repo.withPlaybackReceiptAdmission(auth.receipt,{ownedFlag.get()}){synchronized(gate){command()}};true}catch(_:CancellationException){false}}
        // Explicit actual66 native-input projection; product PlaybackSource carrier field is not installed.
        val version=player.loadVersioned(PlaybackSource(carrier.nativeVideo(remote),referer="",userAgent="",authorizationReceipt=auth.receipt,nativePublication=pub,startPaused=false))
        carrier.attach(version,pub,admission(nativeJob){ownedFlag.get()&&player.ownsSourceVersion(version)&&player.currentSourceSnapshot()?.source?.nativePublication===pub})
        request.complete();player.startSoftwareTransport()
        withTimeout(15_000){target.frames.first{it?.sourceVersion==version}}
        withTimeout(15_000){while(player.state.value.let{!it.ready||it.loading||it.videoCodec==null||it.audioCodec==null||it.positionSeconds<0.3})delay(20)}
        verify("actual same MPV decodes full MPD video and audio",player.state.value.videoCodec!=null&&player.state.value.audioCodec!=null)
        verify("native manifest consumption uses only warmed byte spans",bodyHits.get()==before&&cache.stats().servedCacheBytes>0)
        verify("all three tracks were populated through same owned origin",reads.size==3)
        verify("completed resolver leaves complete MPD reader live",request.isCompleted&&carrier.lease.current())
        carrier.retire(version)
        verify("native MPD capability retires with actual version",!carrier.lease.current())
        println("{\"groups\":1,\"assertions\":$assertions,\"snapshot\":66,\"productOverrides\":0,\"fullAdaptiveMpdNative\":true,\"rootCarrierFieldInstalled\":false,\"realAccount\":false,\"window\":false}")
    } finally {ownedFlag.set(false);player.close();cache.close();origin.stop(0);workers.shutdownNow();scope.cancel()}
}
