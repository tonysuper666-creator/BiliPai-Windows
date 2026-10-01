package com.bilipai.desktop

import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.download.*
import com.bilipai.desktop.cast.DesktopCastPublicationFrame
import com.bilipai.desktop.audio.*
import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import kotlinx.coroutines.flow.MutableStateFlow
import com.android.purebilibili.feature.download.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.swing.Swing
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.security.MessageDigest
import su.litvak.chromecast.api.v2.DesktopCastWriter
import kotlin.coroutines.CoroutineContext
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource

private var assertions=0
private fun expect(value:Boolean,message:String){check(value){message};assertions++}
private fun seed():Pair<DesktopSessionStore,DesktopRepository>{
    val store=DesktopSessionStore.temporary()
    store.saveAccount(mapOf("SESSDATA" to "synthetic-two","bili_jct" to "synthetic-csrf2"),AccountSummary(2,"Synthetic two",""),imported=true)
    store.saveAccount(mapOf("SESSDATA" to "synthetic-one","bili_jct" to "synthetic-csrf1"),AccountSummary(1,"Synthetic one",""),imported=true)
    return store to DesktopRepository(store)
}
private fun receipt(repo:DesktopRepository)=repo.capturePlaybackAuthorization(repo.sessionEpoch){true}.receipt
private fun source(repo:DesktopRepository,name:String)=PlaybackSource("https://fixture.invalid/$name.mp4",title=name,authorizationReceipt=receipt(repo))
private suspend fun waitFor(message:String,condition:()->Boolean)=withTimeout(5000){while(!condition())delay(10);expect(condition(),message)}
private fun storeMonitor(store:DesktopSessionStore)=store.javaClass.getDeclaredField("lock").apply{isAccessible=true}.get(store)
private suspend fun swing(block:()->Unit)=withContext(Dispatchers.Swing){block()}
private val info=VideoDetails("BV1xx411c7mD",99,"Synthetic video","","","",0,0,listOf(VideoPart(101,"Part",10)))
private fun resolved(repo:DesktopRepository,quality:Int)=ResolvedSource("https://fixture.invalid/video-$quality.mp4",null,"Synthetic","https://www.bilibili.com/",quality=quality,authorizationReceipt=receipt(repo))

private suspend fun controllerRace(root:Path){
    val (store,repo)=seed();val player=MpvPlayer();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing)
    var pending=false;val ready=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
    val backend=object:DesktopPlaybackDataSource{
        override val sessionEpoch get()=repo.sessionEpoch
        override suspend fun videoDetails(bvid:String)=info
        override suspend fun related(bvid:String)=emptyList<VideoCard>()
        override suspend fun playback(details:VideoDetails,index:Int,quality:Int,codecOverride:String?,forceRefresh:Boolean):ResolvedSource{
            calls.incrementAndGet();val result=resolved(repo,quality)
            if(pending){ready.complete(Unit);release.await()};return result
        }
    }
    val pub=DesktopRepositoryPlaybackPublication(repo)
    val controller=DesktopPlaybackController(repo,player,null,null,DesktopLibrary(root.resolve("library")){false},
        {PlayerPreferences()},scope,dataSource=backend,currentDanmakuSettings={com.bilipai.desktop.danmaku.DanmakuSettings()},publication=pub)
    try{
        swing{controller.open(info.bvid)}
        waitFor("actual controller loads current authorized source"){!controller.state.value.opening&&player.currentSourceSnapshot()!=null}
        val old=player.currentSourceSnapshot()!!
        expect(old.source.authorizationReceipt!=null&&old.source.nativePublication!=null,"receipt + queued native admission survive mapping")
        var nativeCommands=0
        expect(old.source.nativePublication!!.admit{nativeCommands++}&&nativeCommands==1,"actual owned frame accepts one queued native command")
        pending=true;swing{controller.switchQuality(64)};ready.await()
        val before=player.currentSourceSnapshot()!!
        store.setPlaybackAccountMid(2,repo.sessionEpoch){true};release.complete(Unit)
        waitFor("same-owner cancellation releases only current busy"){!controller.state.value.opening}
        expect(player.currentSourceSnapshot()!!.source==before.source&&player.currentSourceVersion==before.sourceVersion,"selection after URL return cannot replace or stop already-running source")
        expect(calls.get()==2,"authorization cancellation never refetches/falls back")
        expect(!old.source.nativePublication!!.admit{nativeCommands++}&&nativeCommands==1,"retired queued native command rejects before body")
        expect(controller.currentCastSource(old.sourceVersion)==null,"retired source cannot be exported for cast")
        expect(player.state.value.sourceTitle==old.source.title,"selection itself leaves existing native requested source untouched")
    }finally{swing{controller.close()};scope.cancel();player.close()}
    val localGate=Any();val alive=AtomicBoolean(true)
    val local=DesktopLocalPlaybackPublication(alive::get){block->synchronized(localGate){if(!alive.get())false else{block();true}}}
    expect(local.admit(PlaybackSource("file:///C:/synthetic.mp4"),{true}){7}==7,"explicit synthetic local admission")
    expect(runCatching{local.admit(source(repo,"account"),{true}){error("late")}}.exceptionOrNull() is CancellationException,"local contract never adopts account receipt")
    expect(runCatching{DesktopPlaybackController(repo,null,null,null,DesktopLibrary(root.resolve("missing")){false},
        {PlayerPreferences()},CoroutineScope(Job()),dataSource=backend,currentDanmakuSettings={com.bilipai.desktop.danmaku.DanmakuSettings()})}.isFailure,"injected data source cannot silently bypass required publication port")
    listenCancellationAndReplay(root)
}

private suspend fun listenCancellationAndReplay(root:Path){
    val (store,repo)=seed();val player=MpvPlayer();val ready=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
    var pending=true;var prepares=0
    val memory=object:ListenPlaybackDataSource{
        override val lyrics=LyricsRepository(emptyList(),object:LyricsCache{
            override suspend fun read(key:String):LyricDocument?=null
            override suspend fun write(key:String,document:LyricDocument)=Unit
        })
        override suspend fun prepare(item:PlaylistItem):PreparedListenAudio{
            prepares++;val result=PreparedListenAudio(item,source(repo,"listen"))
            if(pending){ready.complete(Unit);release.await()};return result
        }
        override suspend fun subtitleTracks(item:PlaylistItem)=emptyList<SubtitleTrackMeta>()
        override suspend fun subtitleCues(track:SubtitleTrackMeta)=emptyList<SubtitleCue>()
    }
    val session=ListenAudioSession(repo,DesktopCommunityRepository(repo),player,
        store=ListenAudioStore(root.resolve("listen.json")),playbackDataSource=memory,publication=DesktopRepositoryPlaybackPublication(repo))
    try{
        val item=PlaylistItem("BVmemory",title="Memory",cover="",owner="",duration=10)
        swing{session.play(listOf(item))};ready.await()
        val baseline=player.currentSourceVersion;store.setPlaybackAccountMid(2,repo.sessionEpoch){true};release.complete(Unit)
        waitFor("current Listen authorization cancellation releases loading"){!session.state.value.loading}
        expect(!session.state.value.active&&player.currentSourceVersion==baseline&&player.currentSourceSnapshot()==null,"cancelled Listen cannot late-load or stop a foreign source")
        expect(prepares==1,"Listen cancellation does not fallback/refetch")
        pending=false;swing{session.play(listOf(item))}
        waitFor("current Listen source loaded without native DLL"){!session.state.value.loading&&player.currentSourceSnapshot()!=null}
        val loaded=player.currentSourceSnapshot()!!
        swing{
            session.pause()
            @Suppress("UNCHECKED_CAST")
            val state=player.javaClass.getDeclaredField("mutableState").apply{isAccessible=true}.get(player) as MutableStateFlow<PlayerState>
            // Explicit task-only native-state stimulus; no decoder/native completion claim.
            state.value=state.value.copy(durationSeconds=10.0,ended=true,paused=true)
            session.togglePause()
        }
        val replay=player.currentSourceSnapshot()!!;var commands=0
        expect(replay.sourceVersion==loaded.sourceVersion&&replay.source.nativePublication!!.admit{commands++}&&commands==1,"paused same-owner replay refreshes generation admission without transferring source ownership")
    }finally{swing{session.close()};player.close()}
}

private suspend fun callAndCancellationRace(){
    val (store,repo)=seed();val pub=DesktopRepositoryPlaybackPublication(repo);val hits=AtomicInteger()
    val client=OkHttpClient.Builder().addInterceptor{chain->hits.incrementAndGet();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("memory-only").body("synthetic".toResponseBody("text/plain".toMediaType())).build()}.build()
    try{
        val job=Job();val current=source(repo,"queued-call")
        val call=pub.calls(client,current,{job.isActive},job).newCall(Request.Builder().url(current.videoUrl).build())
        store.setPlaybackAccountMid(2,repo.sessionEpoch){true}
        expect(runCatching{call.execute().close()}.exceptionOrNull() is CancellationException&&hits.get()==0,"queued HTTP call rejected at actual enqueue after revision change")
        val fresh=source(repo,"cancel-wait");val cancelled=Job()
        val queued=pub.calls(client,fresh,{cancelled.isActive},cancelled).newCall(Request.Builder().url(fresh.videoUrl).build())
        val held=CountDownLatch(1);val release=CountDownLatch(1)
        val holder=Thread{repo.withPlaybackReceiptAdmission(fresh.authorizationReceipt!!,{true}){held.countDown();release.await()}}.apply{start()}
        expect(held.await(3,TimeUnit.SECONDS),"actual sole Store monitor is held")
        val attempted=CountDownLatch(1);val outcome=AtomicReference<Throwable?>()
        val waiter=Thread{attempted.countDown();try{queued.execute().close()}catch(t:Throwable){outcome.set(t)}}.apply{start()}
        expect(attempted.await(3,TimeUnit.SECONDS),"queued call reaches admission wait")
        cancelled.cancel();release.countDown();holder.join(3000);waiter.join(3000)
        expect(!holder.isAlive&&!waiter.isAlive&&outcome.get() is CancellationException&&hits.get()==0,"only request cancellation while Store is held prevents late enqueue")
        val accepted=source(repo,"accepted-call");pub.calls(client,accepted,{true}).newCall(Request.Builder().url(accepted.videoUrl).build()).execute().close()
        expect(hits.get()==1,"fresh admitted HTTP publication occurs once without sockets")
    }finally{client.dispatcher.cancelAll();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}

private suspend fun queuedDownloadRace(root:Path){
    val (store,repo)=seed();val pub=DesktopRepositoryPlaybackPublication(repo)
    val heads=AtomicInteger();val entered=CountDownLatch(2);val release=CountDownLatch(1);val paths=CopyOnWriteArrayList<String>();val refetch=AtomicInteger()
    val client=OkHttpClient.Builder().addInterceptor{chain->
        val req=chain.request();paths+=req.url.encodedPath
        if(req.method=="HEAD"&&req.url.encodedPath.contains("block")){heads.incrementAndGet();entered.countDown();check(release.await(4,TimeUnit.SECONDS))}
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("memory-only").header("Content-Length","4")
            .body(byteArrayOf(1,2,3,4).toResponseBody("video/mp4".toMediaType())).build()
    }.build()
    val mux=object:DownloadMuxer{override suspend fun mux(video:Path?,audio:Path?,output:Path){Files.write(output,byteArrayOf(1,2,3,4))}}
    val manager=DesktopDownloadManager(client,root.resolve("queue.json"),mux,sourceResolver={refetch.incrementAndGet();error("implicit account fallback")},publication=pub)
    try{
        for(i in 1..2)manager.enqueue(source(repo,"block-$i"),root.resolve("downloads"),DownloadMetadata(bvid="BVblock$i",cid=i.toLong(),includeCover=false,includeDanmaku=false))
        expect(entered.await(3,TimeUnit.SECONDS)&&heads.get()==2,"original two concurrent slots are actually running")
        val third=manager.enqueue(source(repo,"queued"),root.resolve("downloads"),DownloadMetadata(bvid="BVqueued",cid=3,includeCover=false,includeDanmaku=false))
        expect(manager.tasks.value.first{it.id==third}.status==DownloadStatus.QUEUED,"third original task waits in same queue")
        val stored=Files.readString(root.resolve("queue.json"))
        expect(!stored.contains("authorizationReceipt")&&!stored.contains("synthetic-one"),"ephemeral authorization/cookies are never serialized in original task file")
        store.setPlaybackAccountMid(2,repo.sessionEpoch){true};release.countDown()
        waitFor("retired queued download becomes paused without publication"){manager.tasks.value.first{it.id==third}.status==DownloadStatus.PAUSED}
        expect(paths.none{it.contains("queued")},"old queue cannot send HEAD/GET after choice change")
        expect(refetch.get()==0,"retired captured queue authorization never re-resolves under new account")
        expect(manager.tasks.value.size==3,"one original queue/task authority is retained")
    }finally{release.countDown();manager.close();client.dispatcher.cancelAll();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}

private fun castWriterRace(){
    val (store,repo)=seed();val pub=DesktopRepositoryPlaybackPublication(repo);val monitor=storeMonitor(store)
    val writer=DesktopCastWriter();val firstEntered=CountDownLatch(1);val firstRelease=CountDownLatch(1)
    val io=AtomicInteger();val failure=AtomicReference<Throwable?>();val pool=Executors.newFixedThreadPool(2)
    try{
        val first=pool.submit{writer.write({firstEntered.countDown();check(firstRelease.await(4,TimeUnit.SECONDS));expect(!Thread.holdsLock(monitor),"writer I/O runs outside actual Store monitor")},{true},null)}
        expect(firstEntered.await(3,TimeUnit.SECONDS),"sole cast writer is blocked by one accepted frame")
        val old=source(repo,"cast-old");val frame=DesktopCastPublicationFrame(pub,old,{true})
        val secondStarted=CountDownLatch(1)
        val second=pool.submit{secondStarted.countDown();try{writer.write({io.incrementAndGet()},{true},frame)}catch(t:Throwable){failure.set(t)}}
        expect(secondStarted.await(3,TimeUnit.SECONDS),"old cast submit starts")
        // Waiting for queue depth observes actual writer queue, not an implementation mirror.
        val field=writer.javaClass.getDeclaredField("writer").apply{isAccessible=true};val executor=field.get(writer) as ThreadPoolExecutor
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(executor.queue.size!=1&&System.nanoTime()<deadline)Thread.yield()
        expect(executor.queue.size==1,"frame is actually queued before revision changes")
        store.setPlaybackAccountMid(2,repo.sessionEpoch){true};firstRelease.countDown();first.get(3,TimeUnit.SECONDS);second.get(3,TimeUnit.SECONDS)
        expect(failure.get() is java.io.IOException&&io.get()==0,"queued stale frame is rejected before memory write")
        val fresh=DesktopCastPublicationFrame(pub,source(repo,"cast-current"),{true})
        writer.write({expect(!Thread.holdsLock(monitor),"fresh cast flush/write scope is outside Store");io.incrementAndGet()},{true},fresh)
        expect(io.get()==1,"fresh admitted cast frame writes exactly once")
        val cancelled=DesktopCastPublicationFrame(pub,source(repo,"cast-cancel"),{false})
        expect(runCatching{writer.write({io.incrementAndGet()},{true},cancelled)}.isFailure&&io.get()==1,"retired caller cannot enqueue Google frame")
        writer.cancelPending()
        expect(runCatching{writer.write({io.incrementAndGet()},{true},fresh)}.isFailure&&io.get()==1,"closing Channel refuses new frame")
    }finally{firstRelease.countDown();writer.close();pool.shutdownNow();expect(pool.awaitTermination(3,TimeUnit.SECONDS),"task threads drain outside Store")}
}

private fun origin(type:Class<*>):String{
    val bytes=type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}
    val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    return "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location}\",\"classSha256\":\"$sha\"}"
}
fun main(args:Array<String>)=runBlocking{
    val root=Path.of(args[0]);Files.createDirectories(root)
    controllerRace(root);callAndCancellationRace();queuedDownloadRace(root);castWriterRace()
    val origins=listOf(DesktopSessionStore::class.java,DesktopRepository::class.java,PlaybackSource::class.java,DesktopPlaybackController::class.java,
        DesktopDownloadManager::class.java,DownloadTask::class.java,DesktopCastWriter::class.java,DesktopCastPublicationFrame::class.java,
        ListenAudioSession::class.java,VideoDetails::class.java,com.android.purebilibili.feature.download.DownloadTask::class.java)
    Files.writeString(root.resolve("result.json"),"{\"status\":\"PASS\",\"groups\":4,\"assertions\":$assertions,\"origins\":[${origins.joinToString(",",transform=::origin)}],\"nativeDLLExecuted\":false,\"socket\":false,\"HTTP\":false,\"HWND\":false}")
    println("PASS 4 groups $assertions assertions; prospective explicit source overrides; no HTTP/socket/native DLL/HWND")
}
