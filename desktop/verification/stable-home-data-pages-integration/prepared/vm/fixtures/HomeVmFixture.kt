package com.bilipai.desktop.ui

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.plugin.TodayWatchPlugin
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** All terminal requests here are scripted in-process original DTOs; no account, socket or HWND. */
private class ScriptedHome(val dir:Path, val loggedIn:Boolean=false, val epoch:AtomicLong=AtomicLong(1), suppliedStore:DesktopPluginStore?=null) {
    private val admission=Any()
    val alive=AtomicBoolean(true)
    val capturedEpoch=epoch.get()
    val errors=mutableListOf<Throwable>()
    val parentJob=SupervisorJob()
    val scope=CoroutineScope(parentJob+Dispatchers.Default.limitedParallelism(1)+CoroutineExceptionHandler { _,e -> synchronized(errors){errors+=e} })
    val store=suppliedStore?:DesktopPluginStore(dir)
    val context=DesktopPluginContext(store)
    val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
    val incremental=MutableStateFlow(false)
    val tip=MutableStateFlow(true)
    var home:suspend(Int)->Result<List<VideoItem>> = {idx->calls+="home:$idx";Result.success(listOf(video("R$idx")))}
    var region:suspend(Int,Int)->Result<List<VideoItem>> = {tid,page->calls+="region:$tid:$page";Result.success(listOf(video("T$tid-$page")))}
    var nav:suspend()->Result<NavData> = {Result.success(NavData(isLogin=loggedIn,mid=if(loggedIn)123 else 0))}
    var history:suspend(Int,Long,Long,String)->Result<HistoryResult> = {ps,max,viewAt,business->calls+="history:$ps:$max:$viewAt:$business";Result.success(HistoryResult(emptyList(),null))}
    var following:suspend(Long,Int,Int)->FollowingsResponse={_,page,size->calls+="following:$page:$size";FollowingsResponse()}
    val followCalls=mutableListOf<String>()
    val followQueue=ArrayDeque<DynamicFeedFetchResult>()
    var baseline=""
    var followHasMore=true
    fun current()=alive.get()&&epoch.get()==capturedEpoch
    fun commit(block:()->Unit):Boolean=synchronized(admission){if(!current())false else{block();true}}
    val environment=DesktopHomeDataEnvironment(capturedEpoch,scope,::current,::commit,{loggedIn},{_,_->check(current());calls+="identity"},{true},
        object:DesktopHomeIdentityAnalytics {override fun syncUserContext(mid:Long?,isVip:Boolean,privacyModeEnabled:Boolean){check(current());calls+="analytics"}},
        context,DesktopHomeFollowingCache(store,::commit),incremental,tip,
        object:DesktopHomeVideoRequests {
            override suspend fun getHomeVideos(idx:Int)=home(idx)
            override suspend fun getPopularVideos(page:Int):Result<List<VideoItem>>{calls+="popular:$page";return Result.success(listOf(video("P$page")))}
            override suspend fun getRankingVideos(rid:Int,type:String):Result<List<VideoItem>>{calls+="ranking:$rid:$type";return Result.success(listOf(video("Rank")))}
            override suspend fun getWeeklyMustWatchVideos():Result<List<VideoItem>>{calls+="weekly";return Result.success(listOf(video("Weekly")))}
            override suspend fun getPreciousVideos():Result<List<VideoItem>>{calls+="precious";return Result.success(listOf(video("Precious")))}
            override suspend fun getRegionVideos(tid:Int,page:Int)=region(tid,page)
            override suspend fun getNavInfo()=nav()
            override suspend fun getPreviewVideoUrl(bvid:String,cid:Long):String?{calls+="preview:$bvid:$cid";return null}
        },
        object:DesktopHomeHistoryRequests {override suspend fun getHistoryList(ps:Int,max:Long,viewAt:Long,business:String)=history(ps,max,viewAt,business)},
        object:DesktopHomeLiveRequests {
            override suspend fun getFollowedLive(page:Int):Result<List<LiveRoom>>{calls+="live-followed:$page";return Result.success(emptyList())}
            override suspend fun getLiveRooms(page:Int):Result<List<LiveRoom>>{calls+="live-popular:$page";return Result.success(emptyList())}
        },
        object:DesktopHomeMessageRequests {
            override suspend fun getUnreadCount()=Result.success(MessageUnreadData())
            override suspend fun getFeedUnread()=Result.success(MessageFeedUnreadData())
        },
        object:DesktopHomeActionRequests {
            override suspend fun toggleWatchLater(aid:Long,add:Boolean):Result<Boolean>{calls+="watchlater:$aid:$add";return Result.success(true)}
            override suspend fun submitRecommendationFeedback(metadata:RecommendationFeedbackMetadata,reason:RecommendationFeedbackReason):Result<Unit>{calls+="feedback";return Result.success(Unit)}
        },
        object:DesktopHomeFollowRequests {
            override fun currentUpdateBaseline(scope:DynamicFeedScope,type:String):String{check(scope==DynamicFeedScope.HOME_FOLLOW&&type=="video");return baseline}
            override suspend fun getDynamicFeed(refresh:Boolean,scope:DynamicFeedScope,type:String,incrementalRefresh:Boolean):Result<DynamicFeedFetchResult>{
                check(scope==DynamicFeedScope.HOME_FOLLOW&&type=="video")
                followCalls+="$refresh:$incrementalRefresh:$scope:$type"
                return Result.success(followQueue.removeFirst().also{baseline="established";followHasMore=it.hasMore})
            }
            override fun hasMoreData(scope:DynamicFeedScope,type:String)=followHasMore
            override fun syncPaginationAfterRefresh(scope:DynamicFeedScope,type:String,offset:String,hasMore:Boolean){followCalls+="sync:$offset:$hasMore";followHasMore=hasMore}
        },
        object:DesktopHomeBlockedRequests {
            override fun getAllBlockedUps():Flow<List<BlockedUp>> = MutableStateFlow(emptyList())
            override suspend fun blockUp(mid:Long,name:String,face:String){calls+="block-local:$mid"}
            override suspend fun blockUpWithBilibiliSync(mid:Long,name:String,face:String):BlockedUpWriteResult=error("not requested in this fixture")
        },
        object:DesktopHomeFollowingRequests {override suspend fun getFollowings(mid:Long,page:Int,pageSize:Int)=following(mid,page,pageSize)},
        {message->check(current());calls+="notice:$message"})
    lateinit var vm:DesktopOriginalHomeViewModel
    suspend fun start(){withContext(scope.coroutineContext.minusKey(Job)){vm=DesktopOriginalHomeViewModel(environment)}}
    suspend fun install(bridge:DesktopTodayWatchRepository,beforeFactory:()->Unit={}){withContext(scope.coroutineContext.minusKey(Job)){
        vm=bridge.installOwner(capturedEpoch){beforeFactory();DesktopOriginalHomeViewModel(environment)} as DesktopOriginalHomeViewModel
    }}
    suspend fun call(block:DesktopOriginalHomeViewModel.()->Unit){withContext(scope.coroutineContext.minusKey(Job)){vm.block()}}
    suspend fun finish(){if(::vm.isInitialized)vm.closeAndJoin();parentJob.cancelAndJoin();check(errors.isEmpty()){errors.toString()}}
    fun retire(){synchronized(admission){alive.set(false);epoch.incrementAndGet()}}
}

private fun video(id:String)=VideoItem(bvid=id,title=id,owner=Owner(mid=71,name="Fixture"))
private fun dyn(id:String):DynamicItem = Json{ignoreUnknownKeys=true}.decodeFromString(DynamicItem.serializer(),
    """{"id_str":"$id","type":"DYNAMIC_TYPE_AV","modules":{"module_author":{"mid":71,"name":"Fixture"},"module_dynamic":{"major":{"type":"MAJOR_TYPE_ARCHIVE","archive":{"bvid":"$id","aid":"71","title":"$id","cover":"","duration_text":"01:01"}}}}}""")
private suspend fun until(label:String,condition:()->Boolean){withTimeout(5_000){while(!condition())delay(5)};check(condition()){label}}

fun main(args:Array<String>)=runBlocking {
    val root=Path.of(args.single());Files.createDirectories(root)
    val results=mutableListOf<String>()
    lateinit var plannerStore:DesktopPluginStore
    suspend fun gate(name:String,block:suspend()->Unit){block();results+=name;println("PASS $name")}

    gate("recommend idx incremental even merge divider and original undo") {
        val h=ScriptedHome(root.resolve("refresh"))
        h.home={idx->h.calls+="home:$idx";Result.success(if(idx==0)listOf(video("A"),video("B"),video("C"),video("D"))else listOf(video("X"),video("Y"),video("Z"),video("A"),video("X")))}
        h.start();until("initial"){h.vm.uiState.value.categoryStates[HomeCategory.RECOMMEND]?.videos?.size==4}
        h.call{refresh()};until("refresh"){h.vm.uiState.value.undoAvailable&&!h.vm.isRefreshing.value}
        val state=h.vm.uiState.value
        check(h.calls.filter{it.startsWith("home:")}==listOf("home:0","home:1"))
        check(state.videos.map{it.bvid}==listOf("X","Y","A","B","C","D"))
        check(state.refreshNewItemsCount==2&&state.recommendOldContentAnchorBvid=="A"&&state.recommendOldContentStartIndex==2)
        h.call{undoRefresh()};check(h.vm.uiState.value.videos.map{it.bvid}==listOf("A","B","C","D"));check(!h.vm.uiState.value.undoAvailable)
        h.finish()
    }

    gate("independent region pages and four original popular branch caches") {
        val h=ScriptedHome(root.resolve("categories"));h.start();until("initial"){h.vm.uiState.value.videos.isNotEmpty()}
        h.call{switchCategory(HomeCategory.GAME)};until("region1"){h.vm.uiState.value.categoryStates[HomeCategory.GAME]?.videos?.firstOrNull()?.bvid=="T4-1"}
        h.call{loadMore()};until("region2"){h.vm.uiState.value.categoryStates[HomeCategory.GAME]?.videos?.size==2}
        check(h.vm.uiState.value.categoryStates[HomeCategory.GAME]!!.pageIndex==2)
        h.call{switchCategory(HomeCategory.POPULAR)};until("popular"){h.vm.uiState.value.popularCategoryStates[PopularSubCategory.COMPREHENSIVE]?.videos?.isNotEmpty()==true}
        for(sub in listOf(PopularSubCategory.RANKING,PopularSubCategory.WEEKLY,PopularSubCategory.PRECIOUS)){
            h.call{switchPopularSubCategory(sub)};until("popular $sub"){h.vm.uiState.value.popularCategoryStates[sub]?.videos?.isNotEmpty()==true}
        }
        check(h.calls.containsAll(listOf("region:4:1","region:4:2","popular:1","ranking:0:all","weekly","precious")))
        h.call{switchCategory(HomeCategory.GAME)};until("restore game"){h.vm.uiState.value.currentCategory==HomeCategory.GAME}
        check(h.vm.uiState.value.categoryStates[HomeCategory.GAME]!!.videos.map{it.bvid}==listOf("T4-1","T4-2"))
        check(h.calls.count{it=="region:4:1"}==1)
        h.finish()
    }

    gate("HOME_FOLLOW baseline probe then replacement uses original scope and type") {
        val h=ScriptedHome(root.resolve("follow"),true)
        h.followQueue+=DynamicFeedFetchResult(listOf(dyn("F1")),nextOffset="one",hasMore=true)
        h.followQueue+=DynamicFeedFetchResult(listOf(dyn("Probe")),updateNum=1,usedUpdateBaseline=true,nextOffset="probe")
        h.followQueue+=DynamicFeedFetchResult(listOf(dyn("F2")),nextOffset="two",hasMore=false)
        h.start();until("nav"){h.vm.uiState.value.user.isLogin}
        h.call{switchCategory(HomeCategory.FOLLOW)};until("first follow"){h.vm.uiState.value.categoryStates[HomeCategory.FOLLOW]?.videos?.firstOrNull()?.bvid=="F1"}
        h.call{refresh(HomeCategory.FOLLOW)};until("replacement"){h.vm.uiState.value.categoryStates[HomeCategory.FOLLOW]?.videos?.firstOrNull()?.bvid=="F2"&&!h.vm.isRefreshing.value}
        check(h.followCalls.filterNot{it.startsWith("sync:")}==listOf("true:false:HOME_FOLLOW:video","true:true:HOME_FOLLOW:video","true:false:HOME_FOLLOW:video"))
        check(h.vm.uiState.value.categoryStates[HomeCategory.FOLLOW]!!.hasMore==false)
        h.finish()
    }

    gate("same MID epoch retirement rejects late feed and following disk writes") {
        val h=ScriptedHome(root.resolve("retirement"),true)
        val feedEntered=CompletableDeferred<Unit>();val feed=CompletableDeferred<Result<List<VideoItem>>>()
        val followingEntered=CompletableDeferred<Unit>();val following=CompletableDeferred<FollowingsResponse>()
        h.home={feedEntered.complete(Unit);feed.await()}
        h.following={_,_,_->followingEntered.complete(Unit);following.await()}
        h.start();feedEntered.await()
        // Actual first feed triggers original user-nav+following; unblock while still owned.
        feed.complete(Result.success(listOf(video("Before"))))
        followingEntered.await()
        val lateEntered=CompletableDeferred<Unit>();val lateFeed=CompletableDeferred<Result<List<VideoItem>>>()
        h.home={lateEntered.complete(Unit);lateFeed.await()}
        h.call{refresh()};lateEntered.await()
        val before=h.store.preferences("following_cache")
        h.retire();following.complete(FollowingsResponse());lateFeed.complete(Result.success(listOf(video("Late"))))
        delay(50)
        check(h.store.preferences("following_cache")==before)
        check(h.store.preferences("following_cache")["following_time_123"]==null)
        check(h.vm.uiState.value.followingMids.isEmpty())
        check(h.vm.uiState.value.videos.none{it.bvid=="Late"})
        try{h.call{refresh()};error("retired refresh accepted")}catch(_:CancellationException){}
        h.finish()
    }

    gate("actual TodayWatch plugin and sole VM bridge share READY consume reload and covered-page owner") {
        val h=ScriptedHome(root.resolve("planner"))
        plannerStore=h.store
        PluginManager.initialize(h.context)
        val plugin=TodayWatchPlugin { h.context }
        PluginManager.register(plugin)
        PluginManager.awaitPluginReady(TodayWatchPlugin.PLUGIN_ID)
        PluginManager.setEnabled(TodayWatchPlugin.PLUGIN_ID,true)
        h.home={idx->h.calls+="home:$idx";Result.success((1..10).map{video("Plan$it")})}
        h.history={ps,max,viewAt,business->h.calls+="history:$ps:$max:$viewAt:$business";Result.success(HistoryResult(listOf(HistoryData(title="Seen",history=HistoryPage(bvid="Seen",business="archive"))),null))}
        h.start();until("plan"){h.vm.uiState.value.todayWatchPlan?.videoQueue?.isNotEmpty()==true}
        val bridge=DesktopTodayWatchRepository({h.epoch.get()},h.scope)
        bridge.bindOwner(h.vm)
        val owner=h.vm
        val opened=owner.uiState.value.todayWatchPlan!!.videoQueue.first().bvid
        bridge.consume(opened)
        until("consumed"){owner.uiState.value.todayWatchPlan?.videoQueue?.none{it.bvid==opened}==true}
        bridge.reload(false)
        check(owner===h.vm&&owner.isCurrentOwner()) // Merely covering Home performs NO dispose/close.
        check(owner.uiState.value.todayWatchPlan!!.videoQueue.none{it.bvid==opened})
        check(h.calls.count{it.startsWith("history:")}==1) // Raw 10min original cache reused.
        until("projection"){bridge.state.value.plan==owner.uiState.value.todayWatchPlan}
        bridge.shutdownForRestore()
        check(!owner.isCurrentOwner())
        try{bridge.consume("Plan2");error("stopped bridge accepted")}catch(_:IllegalStateException){}
        h.finish()
    }

    gate("same MID replacement retires old actual VM and defers to new sole owner") {
        val epoch=AtomicLong(1)
        val rootJob=SupervisorJob();val rootScope=CoroutineScope(rootJob+Dispatchers.Default.limitedParallelism(1))
        val bridge=DesktopTodayWatchRepository({epoch.get()},rootScope)
        val pending=async { bridge.reload(false) }
        delay(20);check(!pending.isCompleted)
        val first=ScriptedHome(root.resolve("epoch-first"),epoch=epoch,suppliedStore=plannerStore)
        first.home={idx->first.calls+="home:$idx";Result.success((1..10).map{video("Epoch$it")})}
        first.install(bridge);until("old plan"){first.vm.uiState.value.todayWatchPlan?.videoQueue?.isNotEmpty()==true}
        pending.await()
        val same=bridge.installOwner(epoch.get()){error("same epoch must retain owner without a new factory")}
        check(same===first.vm)
        val bvid=first.vm.uiState.value.todayWatchPlan!!.videoQueue.first().bvid
        bridge.consume(bvid)
        val firstOwnedJob=first.vm.javaClass.getDeclaredField("ownedJob").apply{isAccessible=true}.get(first.vm) as Job
        first.retire()
        check(!first.vm.isCurrentOwner())
        val second=ScriptedHome(root.resolve("epoch-second"),epoch=epoch,suppliedStore=plannerStore)
        check(second.store===first.store)
        second.home={idx->second.calls+="home:$idx";Result.success((1..10).map{video("Epoch$it")})}
        second.install(bridge){check(firstOwnedJob.isCompleted){"old VM jobs must be joined BEFORE constructing replacement"}}
        until("new plan"){second.vm.uiState.value.todayWatchPlan?.videoQueue?.isNotEmpty()==true}
        bridge.accountChanged(epoch.get());bridge.accountChanged(1L) // late old flow must not retire current.
        bridge.reload(false)
        check(second.vm.uiState.value.todayWatchPlan!!.videoQueue.any{it.bvid==bvid})
        check(second.calls.count{it.startsWith("home:")}==1)
        try{bridge.bindOwner(first.vm);error("retired owner rebound")}catch(_:IllegalStateException){}
        check(second.vm.isCurrentOwner())
        bridge.shutdownForRestore();first.finish();second.finish();rootJob.cancelAndJoin()
    }

    gate("startup bridge waits for real binding and close cancels deferred request") {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        val bridge=DesktopTodayWatchRepository({1L},scope)
        val request=async{bridge.reload()}
        delay(30);check(!request.isCompleted)
        bridge.shutdownForRestore()
        try{request.await();error("deferred request not cancelled")}catch(_:CancellationException){}
        job.cancelAndJoin()
    }
    Files.writeString(root.resolve("results.json"),"""{"preparedCandidate":true,"actualMainRuntime":false,"terminalRequests":"scripted original DTOs only","gates":${results.size},"names":${Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),results)}}""")
    val origins=listOf(DesktopOriginalHomeViewModel::class.java,DesktopTodayWatchRepository::class.java,
        DesktopPluginStore::class.java,TodayWatchPlugin::class.java,PluginManager::class.java,
        VideoItem::class.java,DesktopOriginalHomeStateOwner::class.java).associate { type -> type.name to
        (type.protectionDomain.codeSource?.location?.toString() ?: "missing") }
    Files.writeString(root.resolve("class-origins.json"),Json.encodeToString(kotlinx.serialization.builtins.MapSerializer(
        kotlinx.serialization.serializer<String>(),kotlinx.serialization.serializer<String>()),origins))
    println("HOME_VM_PREPARED_GATES=${results.size}")
}
