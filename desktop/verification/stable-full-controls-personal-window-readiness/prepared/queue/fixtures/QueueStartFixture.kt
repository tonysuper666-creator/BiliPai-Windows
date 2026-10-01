package com.bilipai.desktop.ui

import com.android.purebilibili.feature.list.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.audio.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.PlaybackSource as NativeSource

private var assertions=0
private fun verify(v:Boolean,why:String){check(v){why};assertions++;println("PASS $why")}
private fun swing(block:()->Unit){if(SwingUtilities.isEventDispatchThread())block()else SwingUtilities.invokeAndWait(block)}
fun main(args:Array<String>):Unit=runBlocking {
 val folder=Path.of(args[0]);Files.createDirectories(folder)
 val sessions=DesktopSessionStore(folder.resolve("no-account-disk.json"),persistent=false)
 sessions.saveAccount(mapOf("SESSDATA" to "fixture-only","bili_jct" to "fixture-only"),AccountSummary(501,"Fixture",""))
 val repository=DesktopRepository(sessions)
 val terminal=repository.httpClient.newBuilder().addInterceptor { chain ->
  val req=chain.request();val body=when(req.url.encodedPath){
   "/x/v2/history/shadow"->"""{"code":0,"data":false}"""
   "/x/web-interface/history/cursor"->"""{"code":0,"data":{"list":[{"title":"cached history","duration":100,"progress":10,"history":{"oid":100,"bvid":"BV-history","cid":102,"business":"archive"}}],"cursor":{"max":0,"view_at":0,"business":""}}}"""
   else->error("Unexpected terminal ${req.url.encodedPath}")
  }
  Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
 }.build()
 repository.javaClass.getDeclaredField("client").apply{isAccessible=true}.set(repository,terminal)
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing)
 val captured=checkNotNull(sessions.dynamicCacheOwner());val gate=DesktopHomeRetainedGate(sessions,captured,{repository.sessionEpoch},{repository.account.value?.mid},{true},scope)
 val library=DesktopLibrary(folder.resolve("library")){false}
 library.record(VideoCard("BV-history","cached","","",0,100,progressSeconds=45,preferredCid=102))
 val root=DesktopPersonalListsRoot(gate,repository,DesktopPluginStore(folder.resolve("global")),library,{false},{error("Unexpected feedback")},HazeState())
 val history=root.history(BiliPaiNavKey.History);val model=history.viewModel as HistoryViewModel
 swing{model.loadData()};withTimeout(5000){model.uiState.first{!it.isLoading&&it.items.size==1}}
 val starting=desktopOriginalPersonalQueueStart(model,listOf(PlaylistItem("BV-history",title="cached",cover="",owner="")),0)!!
 verify(starting.first.single().cid==102L&&starting.second==45000L,"original VM current cached History resolves real CID and 45000 ms before queue admission")
 val video=MpvPlayer();val audio=MpvPlayer()
 val publication=DesktopLocalPlaybackPublication({scope.isActive},{admitted->if(!scope.isActive)false else{admitted();true}})
 val source=object:DesktopPlaybackDataSource{
  override val sessionEpoch get()=repository.sessionEpoch
  override suspend fun videoDetails(bvid:String)=VideoDetails(bvid,100,bvid,"","","",0,0,listOf(VideoPart(101,"P1",100),VideoPart(102,"P2",100)))
  override suspend fun related(bvid:String)=emptyList<VideoCard>()
  override suspend fun playback(details:VideoDetails,index:Int,quality:Int,codecOverride:String?,forceRefresh:Boolean)=ResolvedSource("file:///C:/fixture-queue-start.mp4",null,"actual-selected-${details.pages[index].cid}","",quality=quality)
 }
 val controller=DesktopPlaybackController(repository,video,null,null,library,{PlayerPreferences()},scope,currentDanmakuSettings={error("No overlay in this fixture")},dataSource=source,publication=publication)
 val listenSource=object:ListenPlaybackDataSource{
  override val lyrics=LyricsRepository(emptyList(),object:LyricsCache{
   override suspend fun read(key:String):LyricDocument?=null
   override suspend fun write(key:String,document:LyricDocument)=Unit
  })
  override suspend fun prepare(item:PlaylistItem)=PreparedListenAudio(item,NativeSource("file:///C:/fixture-listen-start.mp4",title="actual-listen-${item.cid}"))
  override suspend fun subtitleTracks(item:PlaylistItem)=emptyList<SubtitleTrackMeta>()
  override suspend fun subtitleCues(track:SubtitleTrackMeta)=emptyList<SubtitleCue>()
 }
 val listenStore=ListenAudioStore(folder.resolve("listen.json"))
 lateinit var listen:ListenAudioSession
 swing{listen=ListenAudioSession(repository,DesktopCommunityRepository(repository),audio,store=listenStore,playbackDataSource=listenSource,publication=publication)}
 var targetAudio=false;var videoReveals=0;var audioReveals=0
 val bridge=DesktopFavoriteQueueBridge(controller,listen,history::owns,{it==targetAudio},{targetAudio=it;true},{videoReveals++},{audioReveals++})
 try{
  lateinit var token:DesktopFavoriteQueueToken
  swing{token=checkNotNull(bridge.openQueue(starting.first,0,false,starting.second))}
  withTimeout(5000){controller.state.first{!it.opening&&it.details!=null}}
  val version=video.currentSourceVersion
  verify(controller.state.value.currentPart==1&&video.state.value.positionSeconds==45.0,"same Controller initial source consumes exact cached History part and seconds")
  verify(library.resumeCard("BV-history")?.let{it.preferredCid==102L&&it.progressSeconds==45}==true,"actual same Library checkpoint records selected CID and initial 45 seconds")
  swing{verify(bridge.revealIfOwned("BV-history",102,false),"original callback reveals the loaded current queue without ordinary open")}
  verify(videoReveals==1&&controller.ownsQueue(token.owner)&&video.currentSourceVersion==version,"reveal preserves queue token and native source version")
  swing{checkNotNull(bridge.openQueue(starting.first,0,true,starting.second))}
  withTimeout(5000){listen.state.first{it.active&&!it.loading}}
  verify(listen.state.value.current?.cid==102L&&audio.state.value.positionSeconds==45.0,"same Listen owner consumes original milliseconds and exact part")
  swing{verify(bridge.revealIfOwned("BV-history",102,true),"audio callback reveals current owned Listen queue") ;listen.pause()}
  withTimeout(5000){while(listenStore.read().positionSeconds!=45.0)delay(10)}
  verify(audioReveals==1&&listenStore.read().queue.single().cid==102L,"same Listen store preserves real CID and 45-second progress")
  val foreign=audio.loadVersioned(NativeSource("file:///C:/foreign-queue-source.mp4",title="Foreign",startPositionSeconds=3.0))
  swing{verify(!bridge.revealIfOwned("BV-history",102,true),"retired reveal ticket cannot claim a foreign audio source")}
  verify(audio.currentSourceVersion==foreign&&audio.state.value.positionSeconds==3.0,"foreign source remains untouched by stale queue callback")
  verify(!Files.exists(folder.resolve("no-account-disk.json")),"all credentials exist only in actual ephemeral SessionStore")
  println("ORIGIN originalHistory="+model.javaClass.protectionDomain.codeSource.location)
  println("ORIGIN preparedController="+controller.javaClass.protectionDomain.codeSource.location)
  println("ORIGIN preparedListen="+listen.javaClass.protectionDomain.codeSource.location)
  println("RESULT assertions=$assertions groups=1 MainIntegrated=false HTTP=false HWND=false nativeDevice=false")
 }finally{bridge.close();swing{listen.close();controller.close()};root.closeAndJoin();gate.closeAndJoin();scope.cancel();video.close();audio.close();terminal.dispatcher.executorService.shutdown();terminal.connectionPool.evictAll()}
}
