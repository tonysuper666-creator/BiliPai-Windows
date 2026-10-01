package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

private var assertions=0
private fun expect(value:Boolean,label:String){assertions++;check(value){label}}
private inline fun rejected(action:()->Unit):Boolean=try{action();false}catch(_:IllegalStateException){true}
private fun view(published:(PlaybackSource,DesktopOriginalVideoMediaIntent)->Unit)=DesktopOriginalVideoMediaIntentView(
    {v,a,_ -> PlaybackSource(v,a,title="captured original title")},
    {s,_ -> PlaybackSource(s.videoTracks.first().baseUrl,title="captured manifest title")},
    {v -> PlaybackSource(v)},published)
private fun lexicalIntent(){
    val accepted=mutableListOf<Pair<PlaybackSource,DesktopOriginalVideoMediaIntent>>()
    val media=view{source,intent->accepted.add(source to intent)}
    media.withPlaybackIntent(4321,false){
        val source=media.prepareLegacyDash("https://task.test/video","https://task.test/audio",emptyMap())
        expect(source.startPaused&&source.startPositionSeconds==4.321,"pause/position stamp uses exact call values")
        media.withPlaybackIntent(200,true){media.accept(media.prepareProgressive("https://task.test/next"))}
        media.accept(source)
    }
    expect(accepted[0].second==DesktopOriginalVideoMediaIntent(200,true)&&!accepted[0].first.startPaused,"nested intent receives own exact values")
    expect(accepted[1].second==DesktopOriginalVideoMediaIntent(4321,false)&&accepted[1].first.title=="captured original title","outer lexical intent restored; original payload retained")
    expect(rejected{media.accept(PlaybackSource("https://task.test/stale"))},"completed span cannot retain old intent")
    try{media.withPlaybackIntent(10,true){error("synthetic prepare failure")}}catch(_:IllegalStateException){}
    expect(rejected{media.prepareProgressive("https://task.test/stale")},"failure restores/removes intent in finally")
}
private fun resolvedSubject(){
    val session=PlaybackSessionStore()
    val request=PlaybackRequest.create("BVfixture",12,0)
    val context=session.beginLoadRequest(request)
    session.updateCurrentMedia(cid=701)
    val captured=captureDesktopOriginalResolvedMediaRequest(session.state.value,request,context.requestToken)
    expect(captured.cid==701L&&request.cid==0L,"actual original resolved CID replaces unresolved request CID without mutating request")
    session.nextLoadRequestToken()
    var cancelled=false
    try{captureDesktopOriginalResolvedMediaRequest(session.state.value,request,context.requestToken)}catch(_:CancellationException){cancelled=true}
    expect(cancelled,"sameCID later original load token rejects old capture")
    val second=session.beginLoadRequest(request.copy())
    cancelled=false
    try{captureDesktopOriginalResolvedMediaRequest(session.state.value,request,second.requestToken)}catch(_:CancellationException){cancelled=true}
    expect(cancelled,"equal-value foreign original Request instance rejected")
    val exact=PlaybackRequest.create("BVfixture",12,702)
    val third=session.beginLoadRequest(exact);session.updateCurrentMedia(cid=701)
    cancelled=false
    try{captureDesktopOriginalResolvedMediaRequest(session.state.value,exact,third.requestToken)}catch(_:CancellationException){cancelled=true}
    expect(cancelled,"explicit positive requested CID cannot be rebound to another result")
}
private val unusedRepository=object:DesktopOriginalVideoLoadRepository{
    override suspend fun getVideoInfoOnly(b:String,a:Long,c:Long):Result<ViewInfo> = error("not a network fixture")
    override suspend fun getInitialPlayUrlData(b:String,c:Long,q:Int,l:String?):PlayUrlData?=error("not a network fixture")
    override suspend fun getVideoDetails(b:String,a:Long,c:Long,q:Int?,l:String?):Result<Pair<ViewInfo,PlayUrlData>> = error("not a network fixture")
    override suspend fun getRelatedVideos(b:String):List<RelatedVideo> = error("not a network fixture")
    override suspend fun getPlayUrlData(b:String,c:Long,q:Int,l:String?):PlayUrlData?=error("not a network fixture")
    override suspend fun getPlaybackNavInfo():Result<NavData> = error("not a network fixture")
    override fun isPlaybackLoggedIn()=error("unused status")
    override fun isPlaybackVip()=error("unused status")
    override fun isUsingDedicatedPlaybackAccount()=error("unused status")
    override fun isAppApiCoolingDown()=error("unused status")
}
private val unusedStatus=object:DesktopOriginalVideoPlaybackStatus{
    override fun isPlaybackLoggedIn()=error("unused status")
    override fun isPlaybackVip()=error("unused status")
    override fun isUsingDedicatedPlaybackAccount()=error("unused status")
    override fun isAppApiCoolingDown()=error("unused status")
}
private suspend fun forwarding(){
    val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
    val creations=AtomicInteger();val values=mutableListOf<PlaybackSource>()
    val initial=view{source,_->values.add(source)}
    val ports=DesktopOriginalVideoPlaybackInvocationPorts(scope,{job.isActive},
        {DesktopOriginalVideoPlaybackInvocation(unusedRepository,initial,{check(job.isActive)})},unusedStatus,
        {creations.incrementAndGet();view{source,_->values.add(source)}})
    // Outside a network invocation, acceptedMedia factory is allowed to allocate
    // a new short admitted wrapper. One original synchronous span must pin ONE.
    ports.media.withPlaybackIntent(5678,false){
        ports.media.accept(ports.media.prepareProgressive("https://task.test/accepted"))
    }
    expect(creations.get()==1&&values.single().startPaused&&values.single().startPositionSeconds==5.678,"accepted recovery keeps one captured media view through prepare/accept")
    ports.withInvocation{
        withContext(Dispatchers.IO){
            ports.media.withPlaybackIntent(42,true){ports.media.accept(ports.media.prepareProgressive("https://task.test/initial"))}
        }
    }
    expect(creations.get()==1&&values.last().startPositionSeconds==0.042&&!values.last().startPaused,"initial invocation still forwards actual same media after dispatcher switch")
    expect(rejected{ports.media.accept(PlaybackSource("https://task.test/escape"))},"forwarding lexical binding cannot leak after span")
    ports.close();job.cancelAndJoin()
}
fun main()=runBlocking{
    lexicalIntent();resolvedSubject();forwarding()
    println("MEDIA_INTENT_PASS groups=3 assertions=$assertions native=false HTTP=false mounted=false")
    listOf(DesktopOriginalVideoMediaIntentView::class.java,DesktopOriginalVideoPlaybackInvocationPorts::class.java,
        PlaybackSessionStore::class.java,com.android.purebilibili.feature.video.usecase.VideoPlaybackUseCase::class.java).forEach{
        println("ORIGIN ${it.name} ${it.protectionDomain.codeSource.location}")
    }
}
