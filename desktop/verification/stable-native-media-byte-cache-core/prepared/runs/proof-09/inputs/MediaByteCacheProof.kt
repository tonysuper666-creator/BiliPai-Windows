package com.bilipai.desktop.player.cache

import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.DesktopNativePlaybackPublication
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.MpvSoftwareTarget
import com.bilipai.desktop.player.PlaybackSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Call
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private var assertions=0
private fun verify(label: String, condition: Boolean) { check(condition){label};assertions++;println("PASS $label") }
private suspend fun waitFor(label: String, condition: ()->Boolean) { withTimeout(10_000){while(!condition())delay(20)};verify(label,true) }

fun main(args: Array<String>) = runBlocking {
    val owned=Path.of(args[0]);Files.createDirectories(owned)
    val movie=Files.readAllBytes(Path.of(args[1]))
    val data=ByteArray(2*1024*1024+333){(it*31+17).toByte()}
    val rootScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val session=DesktopSessionStore(owned.resolve("fixture-session"),persistent=false)
    val repo=DesktopRepository(session)
    val admissionLock=Any()
    val entryAlive=AtomicBoolean(true)
    val authorization=repo.capturePlaybackAuthorization(repo.sessionEpoch){entryAlive.get()}
    val hits=ConcurrentHashMap<String,AtomicInteger>();val bodyHits=ConcurrentHashMap<String,AtomicInteger>()
    val active=AtomicInteger();val slowStarted=CountDownLatch(1)
    val origin=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val originThreads=Executors.newFixedThreadPool(4){r->Thread(r,"fixture-origin").apply{isDaemon=true}}
    origin.executor=originThreads
    origin.createContext("/"){exchange ->
        active.incrementAndGet()
        try {
            val path=exchange.requestURI.path;hits.computeIfAbsent(path){AtomicInteger()}.incrementAndGet()
            val bytes=if(path=="/movie.mp4")movie else data
            val raw=exchange.requestHeaders.getFirst("Range")
            val range=parseMediaRange(raw,bytes.size.toLong())?:error("Fixture received invalid range")
            val start=range.first.toInt();val length=range.second.toInt()
            if(length>1)bodyHits.computeIfAbsent(path){AtomicInteger()}.incrementAndGet()
            exchange.responseHeaders.add("ETag","\"fixture-$path-v1\"")
            exchange.responseHeaders.add("Content-Type","application/octet-stream")
            exchange.responseHeaders.add("Content-Range",if(path=="/bad")"bytes 2-3/${bytes.size}" else "bytes $start-${start+length-1}/${bytes.size}")
            exchange.sendResponseHeaders(if(path=="/ignored")200 else 206,length.toLong())
            exchange.responseBody.use { out ->
                if(path=="/slow"&&length>1) {
                    slowStarted.countDown();var cursor=start
                    while(cursor<start+length){val n=minOf(4096,start+length-cursor);out.write(bytes,cursor,n);out.flush();cursor+=n;Thread.sleep(15)}
                } else out.write(bytes,start,length)
            }
        } catch (_: IOException) { /* fixture observes cancellation below, not server write success */ }
        finally {exchange.close();active.decrementAndGet()}
    }
    origin.start()
    val base="http://127.0.0.1:${origin.address.port}"
    val namespace=mediaDigest("explicit fixture-owned guest partition")
    fun admission(job: Job, stillOwned: ()->Boolean = {entryAlive.get()}): DesktopMediaByteAdmission =
        DesktopMediaByteRepositoryAdmission(repo,authorization,namespace,job,stillOwned){block->synchronized(admissionLock){if(!entryAlive.get())false else{block();true}}}
    val headers=mapOf("Referer" to "https://www.bilibili.com","User-Agent" to "Fixture-owned byte consumer")
    fun track(path:String,key:String=path)=DesktopMediaByteTrack(base+path,emptyList(),key,"local-fixture-$path",headers)
    var cache:DesktopMediaByteCache?=null
    var player:MpvPlayer?=null
    try {
        val requestJob=Job(rootScope.coroutineContext[Job])
        cache=DesktopMediaByteCache(owned.resolve("byte-cache"),rootScope,repo)
        val movieTrack=track("/movie.mp4")
        val bound=cache.bind(admission(requestJob),listOf(movieTrack))
        bound.prefetchRange(movieTrack.url,movieTrack.cacheKey,0,movie.size.toLong(),headers)
        verify("real cold movie range written",cache.stats().spans>0&&cache.stats().diskBytesIncludingStaging>movie.size)
        val beforeWarm=bodyHits["/movie.mp4"]!!.get()
        val remote=PlaybackSource(movieTrack.url,referer=headers.getValue("Referer"),userAgent=headers.getValue("User-Agent"),authorizationReceipt=authorization.receipt,title="Fixture cache origin")
        val carrier=bound.prepareNativeTransport(remote)
        verify("carrier keeps remote semantic address",remote.videoUrl==movieTrack.url&&carrier.videoUri.startsWith("http://127.0.0.1:"))
        verify("loopback binds independently of origin",URI(carrier.videoUri).port!=origin.address.port)
        val target=MpvSoftwareTarget().apply{resize(160,90)}
        player=MpvPlayer(softwareTarget=target,useNullAudioOutput=true)
        val nativeJob=Job(rootScope.coroutineContext[Job])
        val pub=DesktopNativePlaybackPublication{command->
            try{repo.withPlaybackReceiptAdmission(authorization.receipt,{entryAlive.get()}){synchronized(admissionLock){command()}};true}catch(_:CancellationException){false}
        }
        // Explicit fixture bridge: actual66 PlaybackSource has not installed new carrier field.
        // These are actual native input URIs, not a replacement production class/decoder.
        val native=PlaybackSource(carrier.nativeVideo(remote),referer="",userAgent="",authorizationReceipt=authorization.receipt,nativePublication=pub,title="Fixture warmed bytes",startPaused=true)
        val version=player.loadVersioned(native)
        val activePlayer=player
        carrier.attach(version,pub,admission(nativeJob){entryAlive.get()&&activePlayer.ownsSourceVersion(version)&&activePlayer.currentSourceSnapshot()?.source?.nativePublication===pub})
        requestJob.complete()
        player.startSoftwareTransport()
        val first=withTimeout(12_000){target.frames.first{it!=null&&it.sourceVersion==version}}!!
        verify("actual MPV decodes warmed loopback byte stream",first.width==160&&first.height==90&&first.opaqueBgra.isNotEmpty())
        verify("warm native playback makes no new movie origin range",bodyHits["/movie.mp4"]!!.get()==beforeWarm)
        verify("positive actual served cache bytes",cache.stats().servedCacheBytes>0)
        verify("resolver normal completion did not retire native reader",requestJob.isCompleted&&carrier.lease.current())
        waitFor("native is actually ready for publication adoption"){activePlayer.state.value.let{it.ready&&!it.loading&&!it.ended&&it.nativePaused!=null&&it.videoCodec!=null}}
        val newJob=Job(rootScope.coroutineContext[Job])
        val nextPub=DesktopNativePlaybackPublication{command->try{repo.withPlaybackReceiptAdmission(authorization.receipt,{entryAlive.get()}){synchronized(admissionLock){command()}};true}catch(_:CancellationException){false}}
        val expected=player.currentSourceSnapshot()!!
        val replacement=expected.source.copy(nativePublication=nextPub)
        var adopted=false
        repo.withPlaybackReceiptAdmission(authorization.receipt,{entryAlive.get()}){synchronized(admissionLock){
            adopted=activePlayer.adoptPublication(version,expected.source,replacement)&&carrier.adopt(version,pub,nextPub,
                admission(newJob){entryAlive.get()&&activePlayer.ownsSourceVersion(version)&&activePlayer.currentSourceSnapshot()?.source?.nativePublication===nextPub})
        }}
        nativeJob.cancel()
        verify("same native publication transfer keeps byte lease",adopted&&carrier.lease.current())
        player.setPaused(false)
        waitFor("same actor progresses after byte-lease adoption"){activePlayer.state.value.positionSeconds>0.3}
        verify("same token is not a new load",player.currentSourceVersion==version)
        verify("old publication cannot transfer lease again",!carrier.adopt(version,pub,pub,admission(newJob)))
        carrier.retire(version,pub)
        verify("old owner close cannot retire adopted native read lease",carrier.lease.current())
        carrier.retire(version)
        waitFor("exact source retirement closes registration"){!carrier.lease.current()}
        val gone=(URI(carrier.videoUri).toURL().openConnection()as HttpURLConnection).let{c->try{c.responseCode}finally{c.disconnect()}}
        verify("retired capability cannot read bytes",gone==410)
        player.close();player=null

        val dataJob=Job(rootScope.coroutineContext[Job])
        val bulkTrack=track("/bytes")
        val bulk=cache.bind(admission(dataJob),listOf(bulkTrack))
        bulk.prefetchRange(bulkTrack.url,bulkTrack.cacheKey,123,1024*1024+9L,headers)
        verify("multi-span actual interval committed",cache.stats().spans>=3)
        val beforePersistent=bodyHits["/bytes"]!!.get()
        bulk.close();dataJob.cancel();cache.close()
        cache=DesktopMediaByteCache(owned.resolve("byte-cache"),rootScope,repo)
        val persistent=cache.bind(admission(Job(rootScope.coroutineContext[Job])),listOf(bulkTrack))
        persistent.prefetchRange(bulkTrack.url,bulkTrack.cacheKey,123,1024*1024+9L,headers)
        verify("persistent interval reused after actor restart",bodyHits["/bytes"]!!.get()==beforePersistent)
        val beforeGapFill=cache.stats().upstreamBytes
        persistent.prefetchRange(bulkTrack.url,bulkTrack.cacheKey,0,1024*1024+132L,headers)
        verify("prefetch fills only missing committed interval holes",cache.stats().upstreamBytes-beforeGapFill==123L)
        verify("disk budget including file headers remains bounded",cache.stats().diskBytesIncludingStaging<=128L*1024*1024)
        for(path in listOf("/bad","/ignored")) {
            val bad=track(path);val badBound=cache.bind(admission(Job(rootScope.coroutineContext[Job])),listOf(bad))
            val failed=runCatching{badBound.prefetchRange(bad.url,bad.cacheKey,0,10,headers)}
            verify("$path cannot report successful prefetch",failed.isFailure);badBound.close()
        }
        val slowTrack=track("/slow")
        val slowOwner=Job(rootScope.coroutineContext[Job])
        val slow=cache.bind(admission(slowOwner),listOf(slowTrack))
        val spansBefore=cache.stats().spans
        val cancelled=async(Dispatchers.IO){slow.prefetchRange(slowTrack.url,slowTrack.cacheKey,0,1024*1024L,headers)}
        verify("real delayed origin entered",withContext(Dispatchers.IO){slowStarted.await(5,TimeUnit.SECONDS)})
        cancelled.cancelAndJoin()
        verify("caller cancellation did not publish a span",cache.stats().spans==spansBefore)
        verify("cancelled staging bytes cleaned",Files.list(owned.resolve("byte-cache")).use{paths->paths.noneMatch{it.fileName.toString().endsWith(".part")}})
        slow.close();slowOwner.cancel()

        val lru=DesktopMediaByteSpanStore(owned.resolve("bounded-lru"),4096)
        fun writeSmall(id:String,start:Long) {
            val reserve=lru.reserve(1500);lru.begin(reserve,id,start,1500,true).use{it.write(ByteArray(1500){9})};lru.publish(reserve,id,start,1500){it()}
        }
        val keyA=mediaDigest("A");val keyB=mediaDigest("B");val keyC=mediaDigest("C")
        writeSmall(keyA,0);writeSmall(keyB,0)
        val reader=lru.open(keyA,0)!!
        writeSmall(keyC,0)
        verify("LRU preserves pinned reader",lru.covers(keyA,0,1500)&&!lru.covers(keyB,0,1500)&&lru.covers(keyC,0,1500))
        verify("bounded store includes real disk and reservations",lru.stats().first<=4096)
        reader.close();lru.clear();verify("clear deletes only fixture byte spans",lru.stats()==(0L to 0));lru.close()
        val manifest="""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT4S"><Period><AdaptationSet><Representation id="video" bandwidth="123"><BaseURL>${bulkTrack.url}</BaseURL><SegmentBase indexRange="1-9"><Initialization range="0-0"/></SegmentBase></Representation><Representation id="audio"><BaseURL>${movieTrack.url}</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        val rewritten=rewriteMediaManifest(manifest,mapOf(bulkTrack.url to "http://127.0.0.1:1/media/owned/0",movieTrack.url to "http://127.0.0.1:1/media/owned/1")).toString(Charsets.UTF_8)
        verify("complete MPD rewrite retains representation and ranges",rewritten.contains("bandwidth=\"123\"")&&rewritten.contains("indexRange=\"1-9\"")&&rewritten.contains("range=\"0-0\"")&&!rewritten.contains(base))
        verify("XML external entity is rejected",runCatching{rewriteMediaManifest("<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///no-access'>]><MPD><BaseURL>&x;</BaseURL></MPD>",emptyMap())}.isFailure)
        val explicitEmpty=capturedPlaybackMediaHeaders(PlaybackSource(bulkTrack.url,referer="",userAgent="",cookieHeader="",streamHeaders=mapOf("referer" to "", "User-Agent" to "")))
        val tag=DesktopMediaOriginHeaders(explicitEmpty,setOf("http://127.0.0.1:${origin.address.port}"))
        val applied=tag.apply(okhttp3.Request.Builder().url(bulkTrack.url).header("Cookie","borrowed-cookie").header("User-Agent","default").build())
        verify("typed final origin tag preserves empty UA Referer Cookie",applied.header("Cookie")==""&&applied.header("User-Agent")==""&&applied.header("Referer")=="")
        val redirected=tag.apply(applied.newBuilder().url("https://unapproved.invalid/media").header("Authorization","owned-test").build())
        verify("unapproved redirect removes origin credentials",redirected.header("Cookie")==null&&redirected.header("Authorization")==null)
        verify("whole original cache key preserves explicit key and omits signed query",com.android.purebilibili.core.player.buildPlaybackCacheKey("https://fixture.invalid/file?token=owned","chosen")=="chosen"&&com.android.purebilibili.core.player.buildPlaybackCacheKey("https://fixture.invalid/file?token=owned",null)=="https://fixture.invalid/file")
        cache.clear(admission(Job(rootScope.coroutineContext[Job])))
        verify("actual actor clear drains and deletes committed fixture bytes",cache.stats().diskBytesIncludingStaging==0L&&cache.stats().spans==0)
        val revisionBound=cache.bind(admission(Job(rootScope.coroutineContext[Job])),listOf(bulkTrack))
        session.setPlaybackAccountMid(null,repo.sessionEpoch){true}
        verify("actual receipt revision retires captured cache binding",runCatching{revisionBound.prefetchRange(bulkTrack.url,bulkTrack.cacheKey,123,10,headers)}.exceptionOrNull()is CancellationException)
        revisionBound.close()
        persistent.close()
        println("{\"groups\":5,\"assertions\":$assertions,\"actualSnapshot\":66,\"productOverrides\":0,\"nativeWarmBytes\":true,\"rootCarrierFieldInstalled\":false,\"fullAdaptiveMpdNative\":false,\"realAccount\":false,\"window\":false}")
    } finally {
        player?.close();cache?.close();entryAlive.set(false);origin.stop(0);originThreads.shutdownNow();rootScope.cancel()
    }
}
