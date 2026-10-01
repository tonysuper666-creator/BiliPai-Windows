package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.DesktopOriginalVideoLoadProtocol
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.loader.*
import com.android.purebilibili.feature.video.playback.session.*
import com.android.purebilibili.feature.video.playback.coordinator.*
import com.android.purebilibili.feature.video.policy.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

private var assertions=0
private fun prove(condition:Boolean,label:String) { check(condition) {label}; assertions++ }
private class ProtocolFixture {
 val alive=AtomicBoolean(true)
 val requests=java.util.Collections.synchronizedList(mutableListOf<Pair<String,List<Any?>>>() )
 val info=ViewInfo(bvid="BV1fixture",aid=123,cid=11,title="raw title",pages=listOf(Page(cid=11,page=1,part="first"),Page(cid=12,page=2,part="second")),desc="raw description",dimension=Dimension(1920,1080))
 var play=PlayUrlData(quality=80,timelength=90000,acceptQuality=listOf(127,80,64),acceptDescription=listOf("HDR","1080P","720P"),curLanguage="en",aiAudio=AiAudioInfo(items=listOf(AiAudioItem(langCode="en",langDoc="English"))),dash=Dash(duration=90,video=listOf(DashVideo(id=80,baseUrl="https://fixture.invalid/video",codecs="avc1.640028")),audio=listOf(DashAudio(id=30280,baseUrl="https://fixture.invalid/audio",codecs="mp4a.40.2"))))
 var onInfo:()->Unit={}
 var cacheWrites=0
 val cacheValues=mutableMapOf<Triple<String,Long,Int>,PlayUrlData>()
 val cache=object:DesktopOriginalVideoRawCache {
  override fun get(bvid:String,cid:Long,requestedQuality:Int):PlayUrlData? { prove(alive.get(),"Cache read owned");return cacheValues[Triple(bvid,cid,requestedQuality)] }
  override fun put(bvid:String,cid:Long,data:PlayUrlData,quality:Int){prove(alive.get(),"Cache write owned");cacheWrites++;cacheValues[Triple(bvid,cid,quality)]=data}
 }
 val state=object:DesktopOriginalVideoProtocolState {
  override var appApiCooldownUntilMs=0L
  override var wbiKeys:Pair<String,String>?="12345678901234567890123456789012" to "abcdefghijklmnopqrstuvwx12345678"
  override var wbiKeysTimestamp=System.currentTimeMillis()
  override var last412Time=0L
 }
 private fun api(role:String):BilibiliApi = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,arrayOf(BilibiliApi::class.java)){_,method,args ->
  val values=args?.toList().orEmpty();requests+=role+":"+method.name to values.dropLast(1)
  when(method.name) {
   "getVideoInfo","getVideoInfoByAid"->{onInfo();VideoDetailResponse(data=info)}
   "getPlayUrl","getPlayUrlApp","getPlayUrlLegacy"->PlayUrlResponse(data=play)
   "getRelatedVideos"->error("Unused related transport")
   else->error("Unexpected API method "+method.name)
  }
 } as BilibiliApi
 val environment=DesktopOriginalVideoLoadProtocolEnvironment(api("primary"),api("playback"),api("guest"),cache,state,
  ensureBuvid={prove(alive.get(),"Visitor bootstrap owned")},playbackAccount={null},hasPlaybackSessionCookie={true},playbackAccessToken={null},playbackAccessTokenPlatform={"android"},androidAccessTokenPlatform="android",isPlaybackVip={false},auto1080pEnabled={true},directedTrafficEnabled={false},isMobileData={false},canRefreshPrimaryToken={false},refreshPrimaryToken={error("No refresh expected")},stillOwned={alive.get()})
 val protocol=DesktopOriginalVideoLoadProtocol(environment)
}

private suspend fun rawProtocol() {
 val f=ProtocolFixture()
 val (info,data)=f.protocol.getVideoDetails("av123",aid=123,requestedCid=12,targetQuality=80,audioLang="en").getOrThrow()
 prove(info.bvid=="BV1fixture"&&info.cid==12&&info.pages.size==2&&info.desc=="raw description","requested CID preserves whole ViewInfo")
 prove(data===f.play&&data.aiAudio!!.items.single().langCode=="en"&&data.acceptQuality==listOf(127,80,64),"No selected-source reconstruction: full raw quality/translation identity")
 prove(f.cacheWrites==0,"Translated audio request does not write original default-language cache")
 val p=f.requests.single{it.first=="playback:getPlayUrl"}.second.single() as Map<*,*>
 prove(p["bvid"]=="BV1fixture"&&p["cid"]=="12"&&p["qn"]=="80","Canonical BV/CID/QN original API request")
 prove(p["cur_language"]=="en"&&p["lang"]=="en"&&p["fnval"]=="4048"&&p.containsKey("w_rid"),"Original WBI/translation request fields")
 val count=f.requests.size
 f.cacheValues[Triple("BV1fixture",12L,80)]=f.play
 prove(f.protocol.getInitialPlayUrlData("BV1fixture",12,80,null)===f.play&&f.requests.size==count,"Owned raw cache read retains complete object and avoids HTTP")
 val retired=ProtocolFixture();retired.onInfo={retired.alive.set(false)}
 var cancelled=false
 try {retired.protocol.getVideoInfoOnly("BV1fixture",123,12)}catch(e:CancellationException){cancelled=true}
 prove(cancelled&&retired.cacheWrites==0,"Late detail API retirement is cancellation, never failed UI/default/cache write")
 val entryRetired=ProtocolFixture();entryRetired.alive.set(false)
 cancelled=false;try {entryRetired.protocol.getInitialPlayUrlData("BV1fixture",12,80,null)}catch(e:CancellationException){cancelled=true}
 prove(cancelled&&entryRetired.requests.isEmpty(),"Retired entry cannot begin raw request")
}

private fun coordinatorAndIdentity() {
 val store=PlaybackSessionStore();val a=store.beginLoadRequest(PlaybackRequest.create("BV1fixture",cid=11,audioLang=" en ",videoCodecOverride=" hev1 "))
 val b=store.beginLoadRequest(PlaybackRequest.create("BV1fixture",cid=12))
 prove(b.requestToken>a.requestToken&&b.subtitleToken>a.subtitleToken,"Same BV different CID invalidates both request and subtitle generations")
 prove(a.request.audioLang=="en"&&a.request.videoCodecOverride=="hev1","Original request normalizers")
 store.blockVideoCodec(" HEV1 ");prove("hev1" in store.state.value.blockedVideoCodecs,"Codec block uses original family normalization")
 val coordinator=PlaybackCoordinator(store)
 val info=ViewInfo(bvid="BV1fixture",cid=11,pages=listOf(Page(cid=11,page=1),Page(cid=12,page=2)))
 val marked=mutableListOf<String>()
 val suggested=coordinator.refreshResumeSuggestion(0,info,true,{false},{marked+=it}){_,cid->if(cid==12L)45000L else 5000L}
 prove(suggested?.targetCid==12L&&suggested.positionMs==45000L&&marked==listOf("BV1fixture#12"),"Original resume chooses same BV next CID and exact millisecond progress")
 prove(coordinator.consumeResumeSuggestion()==suggested&&coordinator.consumeResumeSuggestion()==null,"Resume suggestion consumed once")
 val action=coordinator.resolvePlaybackEnded(PlaybackCompletionBehavior.STOP_AFTER_CURRENT,true,true,true,ExternalPlaylistSource.FAVORITE,PlayMode.REPEAT_ALL,true)
 prove(action==PlaybackEndAction.STOP&&store.state.value.lastCompletionAction==PlaybackEndAction.STOP,"Explicit stop dominates external favorite queue automatic loop")
 var calls=0
 val execution=coordinator.executePlaybackEndAction(action,{calls++},{calls++;true},{calls++;true},{calls++})
 prove(calls==0&&execution.shouldHidePlaybackEndedDialog,"Original completion executes STOP without foreign queue continuation")
}

private suspend fun supplementOwnership() {
 val alive=AtomicBoolean(true);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
 val entered=CompletableDeferred<Unit>();val response=CompletableDeferred<VideoSupplementSeed>()
 val vm=VideoSupplementViewModel(DesktopOriginalVideoSupplementEnvironment(scope,{alive.get()}){block->if(alive.get()){block();true}else false},VideoSupplementLoader{entered.complete(Unit);response.await()},0)
 val subject=VideoSubjectSnapshot(generation=1,bvid="BV1fixture",aid=123,cid=11)
 vm.bindSubject(subject,VideoSupplementSeed(onlineCount="initial"));entered.await();alive.set(false);response.complete(VideoSupplementSeed(onlineCount="late"))
 delay(50)
 prove(vm.uiState.value.onlineCount=="initial","Retired supplement result cannot overwrite original state")
 vm.close();scope.cancel()
}

fun main()=runBlocking {
 rawProtocol();coordinatorAndIdentity();supplementOwnership()
 println("PASS original raw playback/core ownership: "+assertions+" assertions in 3 groups; no HTTP/HWND/account/native mutation; full Holder/PlaybackVM unaccepted")
}
