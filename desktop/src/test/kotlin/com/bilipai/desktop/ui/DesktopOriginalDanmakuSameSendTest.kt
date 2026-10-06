package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.*

/** The actual pool callback dispatches into the complete original VM. Only its
 * asynchronous protocol result is controlled. No HWND, DLL, credentials or HTTP. */
class DesktopOriginalDanmakuSameSendTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
    private data class Call(val aid:Long,val cid:Long,val text:String,val progress:Long,
        val color:Int,val fontSize:Int,val mode:Int,val colorful:Boolean,val upIdentity:Boolean)
    private inner class Harness : AutoCloseable {
        val folder=Files.createTempDirectory("original-pool-send-")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val context=DesktopPluginContext(DesktopPluginStore(folder))
        val player=MpvPlayer()
        val version=player.loadVersioned(PlaybackSource("file:///C:/no-native-danmaku-fixture.avi"))
        val clickedSource=assertNotNull(player.currentSourceSnapshot())
        var account=1L;var entry=true;var disabled=false;var depth=0
        var beforeAdmission:()->Unit={}
        var afterAdmission:()->Unit={}
        var beforeSourceCheck:()->Unit={}
        val calls=mutableListOf<Call>()
        var requestJob:Job?=null
        var reply:suspend ()->Result<SendDanmakuData> = {Result.success(SendDanmakuData(dmid=7))}
        fun current()=entry&&account==1L
        fun commit(action:()->Unit):Boolean {if(!current())return false;action();return true}
        val repository=unused<DesktopOriginalVideoOwnerRepository>()
        val media=unused<DesktopOriginalVideoMediaPort>()
        val actions=unused<DesktopOriginalVideoOwnerActions>()
        val invocations=DesktopOriginalVideoPlaybackInvocationPorts(scope,::current,{
            val caller=currentCoroutineContext().job;val epoch=account
            DesktopOriginalVideoPlaybackInvocation(repository,media) {
                if(!entry||account!=epoch||!caller.isActive)throw CancellationException("Captured request retired")
            }
        },unused<DesktopOriginalVideoPlaybackStatus>(),{media})
        val danmaku=object:DesktopOriginalVideoOwnerDanmaku {
            override fun isDanmakuServerDisabled(cid:Long)=disabled
            override suspend fun sendDanmaku(aid:Long,cid:Long,message:String,progress:Long,color:Int,fontSize:Int,
                mode:Int,colorful:Boolean,upIdentity:Boolean):Result<SendDanmakuData> {
                assertEquals(0,depth);requestJob=currentCoroutineContext().job
                assertEquals(clickedSource.source,DesktopOriginalDanmakuExpectedSubmission.current()?.source?.source)
                calls+=Call(aid,cid,message,progress,color,fontSize,mode,colorful,upIdentity)
                return reply()
            }
            override suspend fun sendAttentionCommandDanmaku(aid:Long,cid:Long,progress:Long)=error("Not attention command")
            override suspend fun getDanmakuThumbupState(cid:Long,dmid:Long)=error("Not like")
            override suspend fun recallDanmaku(cid:Long,dmid:Long)=error("Not recall")
            override suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean)=error("Not like")
            override suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String)=error("Not report")
        }
        private val api=unused<BilibiliApi>()
        private val interaction=VideoInteractionUseCase(DesktopOriginalVideoEngagementProtocol(
            api,{"fixture-csrf"},{1L},{"fixture-session"},{null},{},{},
            DesktopOriginalFavoriteFolderProtocol(api,{1L},{"fixture-csrf"},{})),unused<DesktopOriginalVideoInteractionAnalytics>())
        private val settings=DesktopOriginalPlayerSettingsContext(context,::current,::commit,
            largeScreenOrFoldableConfiguration={false},isDebugBuild={false})
        private val useCase=DesktopOriginalVideoPlaybackUseCaseEnvironment(context,repository,actions,
            unused<DesktopOriginalVideoProgressPort>(),unused<DesktopOriginalVideoPlaybackCapabilities>(),media,
            {error("No volume effect")},{emptyMap()},{},{false},{_,_,_,_->},::current)
        val vm=VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(scope,settings,invocations,
            repository,unused(),actions,useCase,interaction,unused(),unused(),unused(),unused(),unused(),
            unused(),unused(),{null},unused(),unused(),unused(),danmaku,MutableStateFlow(false),::current,::commit,
            DesktopTodayWatchFeedbackWriteBinding(context,::current,::commit)))
        val session=VideoPlaybackViewModel::class.java.getDeclaredField("playbackSessionStore").apply {isAccessible=true}
            .get(vm) as PlaybackSessionStore
        init {
            @Suppress("UNCHECKED_CAST")
            val native=MpvPlayer::class.java.getDeclaredField("mutableState").apply {isAccessible=true}
                .get(player) as MutableStateFlow<PlayerState>
            native.value=native.value.copy(positionSeconds=73.125)
            val control=DesktopOriginalMpvSectionControl(player,{version},::current,::commit,{false},
                {error("No core preparation")},{error("No replay")},{_,_,_,_->},scope,MutableStateFlow<Long?>(version),::commit)
            VideoPlaybackViewModel::class.java.getDeclaredField("exoPlayer").apply {isAccessible=true}.set(vm,control)
            @Suppress("UNCHECKED_CAST")
            val state=VideoPlaybackViewModel::class.java.getDeclaredField("_uiState").apply {isAccessible=true}
                .get(vm) as MutableStateFlow<VideoPlaybackUiState>
            state.value=VideoPlaybackUiState.Success(ViewInfo(bvid="BVfixture",aid=17,cid=70),"")
            session.updateCurrentMedia("BVfixture",70);session.setCurrentLoadRequestToken(12)
        }
        fun dispatch(text:String="同款"):Boolean {
            var result=false
            javax.swing.SwingUtilities.invokeAndWait {
                result=dispatchDesktopOriginalDanmakuSameSend(text,vm,clickedSource,
                    {beforeSourceCheck();current()},::current) {action->
                    beforeAdmission()
                    if(!current())false else {
                        depth++;try {action()} finally {depth--}
                        afterAdmission();true
                    }
                }
            }
            return result
        }
        suspend fun idle() {withTimeout(2_000) {vm.isSendingDanmaku.first {!it}}}
        suspend fun noSent() {assertNull(withTimeoutOrNull(60) {vm.danmakuSentEvent.first()})}
        override fun close() {scope.cancel();player.close();folder.toFile().deleteRecursively()}
    }
    @Test fun actualVmConsumesCurrentCidClockAndDefaultStyleOnce()=runBlocking<Unit> {
        withTimeout(5_000) {Harness().use {h->
            val response=CompletableDeferred<Result<SendDanmakuData>>();h.reply={response.await()}
            try {
                assertTrue(h.dispatch());assertTrue(h.vm.isSendingDanmaku.value)
                assertFalse(h.dispatch());assertEquals(1,h.calls.size)
                assertEquals(Call(17,70,"同款",73125,16777215,25,1,false,false),h.calls.single())
                response.complete(Result.success(SendDanmakuData(dmid=7)));h.idle()
                val sent=withTimeout(2_000) {h.vm.danmakuSentEvent.first()}
                assertEquals("同款",sent.text);assertEquals(70L,sent.cid);assertEquals("BVfixture",sent.bvid)
                assertEquals(12L,sent.loadToken);assertEquals(h.clickedSource.source,sent.nativeSource.source)
                assertEquals(h.version,sent.nativeSource.sourceVersion);h.noSent()
            } finally {response.cancel()}
        }}
    }
    @Test fun actionRetiredInsideFinalAdmissionCannotStartProtocol()=runBlocking<Unit> {
        withTimeout(5_000) {Harness().use {h->
            h.beforeAdmission={h.entry=false};assertFalse(h.dispatch());assertTrue(h.calls.isEmpty())
            assertFalse(h.vm.isSendingDanmaku.value);h.noSent()
        }}
    }
    @Test fun actualSendCaptureRejectsNativeChangeAfterPresentationPermit()=runBlocking<Unit> {
        withTimeout(5_000) {for(changeInSourceRead in listOf(false,true)) Harness().use {h->
            val change={assertTrue(h.player.recoverSource(h.version,positionSeconds=2.0,paused=true));Unit}
            if(changeInSourceRead)h.beforeSourceCheck=change else h.afterAdmission=change
            h.dispatch();assertTrue(h.calls.isEmpty());assertFalse(h.vm.isSendingDanmaku.value);h.noSent()
        }}
    }
    @Test fun realFailedProtocolAndServerDisabledNeverManufactureSuccess()=runBlocking<Unit> {
        withTimeout(5_000) {Harness().use {h->
            h.reply={Result.failure(IllegalStateException("synthetic rejection"))}
            assertTrue(h.dispatch());h.idle();h.noSent();assertEquals(1,h.calls.size)
            h.disabled=true
            // true is synchronous action admission, not a confirmed network result.
            assertTrue(h.dispatch());assertEquals(1,h.calls.size);assertFalse(h.vm.isSendingDanmaku.value);h.noSent()
        }}
    }
    @Test fun completeOriginalVmRejectsLateAccountSourceAndCallerRetirement()=runBlocking<Unit> {
        withTimeout(5_000) {for(retire in listOf<(Harness)->Unit>(
            {it.account=2},{it.session.updateCurrentMedia(cid=71)},
            {assertTrue(it.player.recoverSource(it.version,positionSeconds=2.0,paused=true))},
            {assertNotNull(it.requestJob).cancel()})) Harness().use {h->
            val response=CompletableDeferred<Result<SendDanmakuData>>()
            h.reply={withContext(NonCancellable) {response.await()}}
            try {
                assertTrue(h.dispatch());assertEquals(1,h.calls.size);retire(h)
                response.complete(Result.success(SendDanmakuData(dmid=7)));yield();h.noSent()
            } finally {response.cancel()}
        }}
    }
}
