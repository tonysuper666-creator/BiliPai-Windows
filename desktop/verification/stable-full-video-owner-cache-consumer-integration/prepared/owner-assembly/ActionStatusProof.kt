package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

// No native/media/UI/HTTP is exercised. Actual Store, request Binding, Invocation,
// selected original Action methods and same-Store entry readbacks are executed.
private var checks = 0
private fun expect(value:Boolean,label:String) { checks++; check(value) { label } }
private fun replaceField(owner:Any,name:String,value:Any) {
    owner.javaClass.getDeclaredField(name).also { it.isAccessible=true }.set(owner,value)
}
private val unusedMedia = object:DesktopOriginalVideoMediaPort {
    override fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit):Unit = error("No media intent in protocol proof")
    override fun prepareLegacyDash(v:String,a:String?,k:Map<String,String>):PlaybackSource = error("No media")
    override fun prepareAdaptiveDash(s:AdaptiveDashPlaybackSource,k:Map<String,String>):PlaybackSource? = error("No media")
    override fun prepareProgressive(url:String):PlaybackSource = error("No media")
    override fun accept(source:PlaybackSource) = error("No native acceptance")
}
private class Terminal {
    var assertCurrent:()->Unit = { error("Terminal not captured") }
    var reply:(String,List<Any?>,Continuation<Any?>)->Any? = { name,_,_->error(name) }
    @Suppress("UNCHECKED_CAST")
    val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,arrayOf(BilibiliApi::class.java)) { proxy,method,args ->
        when(method.name) {
            "toString"->"task-owned Action terminal"
            "hashCode"->System.identityHashCode(proxy)
            "equals"->proxy===args?.get(0)
            else->{ assertCurrent(); val fields=args!!.toList(); reply(method.name,fields.dropLast(1),fields.last() as Continuation<Any?>) }
        }
    } as BilibiliApi
}
private class Fixture:AutoCloseable {
    val root=Files.createTempDirectory("bilipai-action-assembly-")
    val sessions=DesktopSessionStore(root.resolve("memory.json"),persistent=false)
    val repository=DesktopRepository(sessions)
    val alive=AtomicBoolean(true)
    val job=SupervisorJob()
    val scope=CoroutineScope(job+Dispatchers.Default)
    val assets=DesktopSubtitleAssets(OkHttpClient.Builder().addInterceptor { error("No default HTTP") }.build())
    val privacy=DesktopSearchPreferences(root.resolve("privacy"))
    val terminal=Terminal()
    val bound=AtomicReference<DesktopOriginalVideoRepositoryBinding>()
    val gate=Any()
    fun owned()=alive.get()&&job.isActive
    fun admit(block:()->Unit)=synchronized(gate) { if(owned()) { block();true } else false }
    init { sessions.saveAccount(mapOf("SESSDATA" to "task-owned-session","bili_jct" to "task-owned-csrf","DedeUserID" to "701"),AccountSummary(701,"synthetic","",true)) }
    val epoch=repository.sessionEpoch
    val readbacks=DesktopOriginalVideoEntryReadbacks(repository,epoch,scope,::owned,::admit)
    val ports=DesktopOriginalVideoPlaybackInvocationPorts(scope,::owned,capture={
        val caller=currentCoroutineContext()[Job]!!
        val binding=DesktopOriginalVideoRepositoryBinding.capture(repository,epoch,caller,::owned,::admit,
            PlayerPreferences(),null,emptySet(),true,{false},{false},{false},{false},{_,_->error("No token refresh")})
        bound.set(binding); terminal.assertCurrent=binding::assertCurrent
        replaceField(binding,"capturedPrimaryApi",terminal.api)
        replaceField(binding.environment,"api",terminal.api)
        replaceField(binding.environment,"playbackApi",terminal.api)
        val raw=createDesktopOriginalVideoOwnerRequestRepository(repository,binding,assets,privacy,{_,_->false},{error("No VIP update")})
        DesktopOriginalVideoPlaybackInvocation(raw,unusedMedia,binding::assertCurrent)
    },status=readbacks,acceptedMedia={error("No accepted source")})
    val analytics=object:DesktopOriginalVideoInteractionAnalytics {
        override fun logLike(videoId:String,isLiked:Boolean) { error("No mutation") }
        override fun logDislike(videoId:String,isDisliked:Boolean) { error("No mutation") }
        override fun logFavorite(videoId:String,isFavorited:Boolean) { error("No mutation") }
        override fun logFollow(userId:String,isFollowed:Boolean) { error("No mutation") }
        override fun logCoin(videoId:String,coinCount:Int) { error("No mutation") }
    }
    val actions=DesktopOriginalVideoOwnerActionView(ports,scope,analytics,{error("No follow mutation")},{error("No UI feedback")})
    suspend fun invoke(block:suspend()->Unit) {
        val result=CompletableDeferred<Unit>()
        ports.launch { try { block();result.complete(Unit) } catch(failure:Throwable) { result.completeExceptionally(failure) } }
        withTimeout(5000) { result.await() }
    }
    override fun close() { ports.close();job.cancel();assets.close() }
}
private suspend fun originalFieldsAndFallbacks() {
    Fixture().use { f ->
        val calls=mutableListOf<String>()
        f.terminal.reply={name,args,_->
            synchronized(calls) { calls.add(name) }
            if(name!="getWatchLaterList") expect(args.first()==123L,"original status aid preserved: $name")
            when(name) {
                "checkFavoured"->FavouredResponse(data=FavouredData(favoured=true))
                "hasLiked"->HasLikedResponse(data=1)
                "getVideoRelation"->{ expect(args[1]==null,"original relation default bvid");VideoRelationResponse(data=VideoRelationData(dislike=true)) }
                "hasCoined"->HasCoinedResponse(data=CoinedData(2))
                "getWatchLaterList"->WatchLaterResponse(data=WatchLaterData(list=listOf(WatchLaterItem(aid=123))))
                else->error(name)
            }
        }
        f.invoke {
            withContext(Dispatchers.IO) {
                expect(f.actions.checkFavoriteStatus(123),"original favourite state")
                expect(f.actions.checkLikeStatus(123),"original liked state")
                expect(f.actions.checkDislikeStatus(123),"original dislike state")
                expect(f.actions.checkCoinStatus(123)==2,"original coin count")
                expect(f.actions.checkWatchLaterStatus(123),"original WatchLater membership")
                expect(!f.actions.checkWatchLaterStatus(0),"invalid aid skips WatchLater request")
                expect(f.bound.get().primarySessData()=="task-owned-session","same receipt cookie getter")
                expect(f.bound.get().primaryAccessToken()==null,"same receipt missing access token is actual null")
            }
        }
        expect(calls==listOf("checkFavoured","hasLiked","getVideoRelation","hasCoined","getWatchLaterList"),"one actual service call per selected status")
        f.terminal.reply={name,_,_->when(name) {
            "checkFavoured"->FavouredResponse(code=-1)
            "hasLiked"->HasLikedResponse(data=2)
            "getVideoRelation"->VideoRelationResponse(code=-1,data=VideoRelationData(dislike=true))
            "hasCoined"->HasCoinedResponse(code=-1,data=CoinedData(2))
            "getWatchLaterList"->WatchLaterResponse(code=-1,data=WatchLaterData(list=listOf(WatchLaterItem(aid=123))))
            else->error(name)
        } }
        f.invoke {
            expect(!f.actions.checkFavoriteStatus(123),"original failed favourite default")
            expect(!f.actions.checkLikeStatus(123),"only original liked value1 succeeds")
            expect(!f.actions.checkDislikeStatus(123),"original failed dislike default")
            expect(f.actions.checkCoinStatus(123)==0,"original failed coin default")
            expect(!f.actions.checkWatchLaterStatus(123),"original failed WatchLater default")
        }
    }
}
private suspend fun lateSameMidEpochAndCancellation() {
    for(cancel in listOf(false,true)) Fixture().use { f ->
        val pending=CompletableDeferred<Continuation<Any?>>()
        val refused=AtomicBoolean(false)
        f.terminal.reply={name,_,continuation->check(name=="checkFavoured");pending.complete(continuation);COROUTINE_SUSPENDED}
        val request=f.ports.launch {
            try { f.actions.checkFavoriteStatus(123);error("Retired response escaped") }
            catch(_:CancellationException) { refused.set(true) }
        }
        val continuation=withTimeout(5000) { pending.await() }
        if(cancel) request.cancel()
        else f.sessions.saveAccount(mapOf("SESSDATA" to "task-owned-replaced","bili_jct" to "next-task-csrf","DedeUserID" to "701"),AccountSummary(701,"same-mid","",true))
        continuation.resume(FavouredResponse(data=FavouredData(favoured=true)))
        withTimeout(5000) { request.join() }
        expect(refused.get(),if(cancel) "original IO late success cannot outlive caller cancellation" else "same MID new epoch late status cannot publish old result")
        expect(!f.bound.get().admitCurrentMutation { error("Retired short mutation ran") },"completed/retired request cannot mutate")
    }
}
private fun actualStoreReadbacks() {
    Fixture().use { f ->
        expect(f.readbacks.hasPrimarySession()&&!f.readbacks.hasPrimaryAccessToken(),"actual same Store credential presence")
        expect(f.readbacks.primaryMid()==701L&&f.readbacks.isPlaybackLoggedIn(),"actual same Store primary and auth policy")
        expect(f.readbacks.isPlaybackVip()&&!f.readbacks.isUsingDedicatedPlaybackAccount(),"original primary VIP fallback with no selected account")
        expect(f.sessions.setPlaybackAccountMid(701,f.epoch,f::owned),"actual same Store selects existing account")
        expect(f.readbacks.isUsingDedicatedPlaybackAccount(),"original selected!=null semantics even same MID")
        expect(f.readbacks.isPlaybackLoggedIn()&&f.readbacks.isPlaybackVip(),"selected account auth/VIP are actual values")
        expect(f.readbacks.cooldownRemainingMs(1000)==0L,"real protocol monitor starts outside cooldown")
        f.repository.javaClass.getDeclaredField("appApiCooldownUntilMs").also { it.isAccessible=true }.setLong(f.repository,6000L)
        expect(f.readbacks.cooldownRemainingMs(1000)==5000L,"cooldown projects existing monitor rather than fake default")
        f.alive.set(false)
        expect(runCatching { f.readbacks.primaryMid() }.exceptionOrNull() is BiliApiException,"existing Store retirement rejects synchronous primary read")
        expect(runCatching { f.readbacks.cooldownRemainingMs(1000) }.exceptionOrNull() is BiliApiException,"existing Store retirement rejects monitor read")
    }
}
fun main()=runBlocking {
    originalFieldsAndFallbacks();lateSameMidEpochAndCancellation();actualStoreReadbacks()
    println("OWNER_ACTION_STATUS_PASS checks=$checks groups=3 HTTP=false native=false mounted=false")
    listOf(DesktopOriginalVideoOwnerActionView::class.java,DesktopOriginalVideoEntryReadbacks::class.java,
        DesktopOriginalVideoRepositoryBinding::class.java,DesktopRepository::class.java,
        com.android.purebilibili.data.repository.DesktopOriginalVideoActionStatus::class.java,
        DesktopOriginalVideoPlaybackInvocationPorts::class.java).forEach { println("ORIGIN ${it.name} ${it.protectionDomain.codeSource.location}") }
}
