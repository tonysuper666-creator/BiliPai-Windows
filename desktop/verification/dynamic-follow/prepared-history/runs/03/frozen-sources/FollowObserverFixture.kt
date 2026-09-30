@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package com.bilipai.desktop.ui.followobserverproof

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.FollowStateChange
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import java.security.Permission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private var assertions=0
private val cases=mutableListOf<JsonObject>()
private fun expect(value:Boolean,label:String){assertions++;check(value){"Assertion failed: $label"}}
private fun ids(rows:List<DynamicItem>)=rows.map{it.id_str}
private fun row(id:String,mid:Long)=DynamicItem(id_str=id,type="DYNAMIC_TYPE_WORD",modules=DynamicModules(
    module_author=DynamicAuthorModule(mid=mid,name="UP-$mid",following=true,pub_ts=100)))
private fun response(rows:List<DynamicItem>,more:Boolean=false)=DynamicFeedResponse(data=DynamicFeedData(rows,"next",more))
private val originalRows=listOf(row("target-a",22),row("keep",33),row("target-b",22),row("unknown",0))
private suspend fun eventually(label:String,ready:()->Boolean)=withTimeout(5000){while(!ready())delay(3);expect(true,label)}
private class NoSockets:SecurityManager(){
    override fun checkPermission(permission:Permission){}
    override fun checkConnect(host:String?,port:Int){error("Outside socket prohibited")}
    override fun checkListen(port:Int){error("Listener prohibited")}
}
private fun setField(target:Any,name:String,value:Any){target.javaClass.getDeclaredField(name).also{it.isAccessible=true}.set(target,value)}
private fun getField(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name).also{it.isAccessible=true}.get(target)
private class FixtureTransport:Interceptor {
    val writes=mutableListOf<Pair<Long,Int>>()
    var code=0
    var intercept:(()->Unit)?=null
    override fun intercept(chain:Interceptor.Chain):Response {
        val request=chain.request()
        check(request.url.encodedPath=="/x/relation/modify" && request.method=="POST"){"Unexpected fixture request"}
        val body=request.body as FormBody
        val fields=(0 until body.size).associate{body.name(it) to body.value(it)}
        synchronized(writes){writes += fields.getValue("fid").toLong() to fields.getValue("act").toInt()}
        intercept?.invoke()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("task fixture")
            .body("""{"code":$code,"message":"fixture"}""".toResponseBody("application/json".toMediaType())).build()
    }
}
private class GenericOwner(var rows:List<DynamicItem>):DesktopDynamicCardItemsOwner {
    var calls=0
    override fun mutateDynamicItems(transform:(List<DynamicItem>)->List<DynamicItem>){calls++;rows=transform(rows)}
}
private class Harness(val root:Path,context:CoroutineContext) {
    val scope=CoroutineScope(context+SupervisorJob())
    val sessions=DesktopSessionStore(root.resolve("fixture-session.json"),persistent=false)
    val repository:DesktopRepository
    val transport=FixtureTransport()
    val store=DesktopPluginStore(root.resolve("fixture-store"))
    val cache:DesktopDynamicCache
    val session:DesktopDynamicCardSession
    val registry:DesktopDynamicCardStateRegistry
    val social:DesktopSocialRepository
    val owner:DesktopDynamicCacheOwner
    val seen=mutableListOf<DesktopOwnedFollowStateChange>()
    private val observe:Job
    private val audit:Job
    init {
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-owner-a","bili_jct" to "synthetic-csrf"),AccountSummary(11,"fixture",""))
        repository=DesktopRepository(sessions)
        // Task-only reflection replaces only transport and visitor readiness. No API, reducer, event or model overrides.
        setField(repository,"client",repository.httpClient.newBuilder().addInterceptor(transport).build())
        bootstrapReady()
        owner=sessions.dynamicCacheOwner()!!
        cache=DesktopDynamicCache(sessions,store)
        session=DesktopDynamicCardSession(repository,owner.epoch)
        registry=DesktopDynamicCardStateRegistry(sessions,cache,owner.epoch,session::isOwned)
        social=DesktopSocialRepository(repository)
        audit=scope.launch(start=CoroutineStart.UNDISPATCHED){repository.followStateEvents.changes.collect{seen+=it}}
        observe=scope.launch(start=CoroutineStart.UNDISPATCHED){session.observeFollowStateChanges(registry)}
    }
    fun bootstrapReady(){setField(repository,"visitorInitialized",true);setField(repository,"visitorGeneration",sessions.generation)}
    fun rotateSameMid(){sessions.saveAccount(mapOf("SESSDATA" to "synthetic-owner-b","bili_jct" to "synthetic-csrf-b"),AccountSummary(11,"fixture",""));bootstrapReady()}
    suspend fun timeline(type:String="all",fetch:suspend(String,String,String)->DynamicFeedResponse={_,_,_->response(originalRows)}):DesktopDynamicTimelineState {
        val cached=cache.openCurrent()!!
        lateinit var state:DesktopDynamicTimelineState
        state=DesktopDynamicTimelineState(type,fetch,session::isOwned,
            initialCachedItems=if(type=="all")cached.cachedAllItems.value else emptyList(),
            onAllTimelineChanged={if(registry.isCurrentAll(state))cached.saveTimeline(it)})
        registry.register(state);state.initialize(false);return state
    }
    fun users(follow:suspend(Int)->FollowingsData={FollowingsData(listOf(FollowingUser(22,"UP-22",""),FollowingUser(33,"UP-33","")),2)},
        live:suspend()->List<LiveRoom> = {listOf(LiveRoom(uid=22,roomid=222,uname="UP-22"))},
        request:suspend(Map<String,String>)->DynamicFeedResponse={response(listOf(row("remote",22)))},
        now:()->Long={100_000L}):DesktopDynamicUsersState {
        val state=DesktopDynamicUsersState(scope,DesktopDynamicTabsPreferences(DesktopPluginContext(store)),11,follow,live,{null},request,session::isOwned,nowMs=now)
        registry.register(state);return state
    }
    suspend fun close(){session.close();registry.close();observe.cancelAndJoin();audit.cancelAndJoin();scope.cancel();cache.shutdownForRestore()}
}
private suspend fun runCase(name:String,root:Path,body:suspend (Harness)->Unit) {
    val before=assertions;val h=Harness(root.resolve(name),currentCoroutineContext())
    try{body(h);cases+=buildJsonObject{put("name",name);put("passed",true);put("assertions",assertions-before)};println("PASS $name ${assertions-before}")}
    finally{h.close()}
}

private suspend fun tests(root:Path) {
    // Exact original reducer remains the oracle for all timeline pages and selected-UP payload.
    val original=DynamicUiState(items=originalRows.toImmutableList(),userItems=originalRows.toImmutableList(),timelineRequestType="video",
        hasMore=false,incrementalRefreshBoundaryKey="keep",incrementalPrependedCount=2,
        timelinePages=persistentMapOf("all" to DynamicTimelinePageState(originalRows.toImmutableList(),isCachePlaceholder=true),
            "video" to DynamicTimelinePageState(originalRows.toImmutableList(),hasMore=false)))
    val reduced=resolveDynamicStateAfterAuthorUnfollow(original,22)
    expect(ids(reduced.items)==listOf("keep","unknown"),"original active page author identity")
    expect(ids(reduced.timelinePage("all").items)==listOf("keep","unknown"),"original retained All page")
    expect(ids(reduced.userItems)==listOf("keep","unknown"),"original remote UP rows")
    expect(reduced.timelinePage("all").isCachePlaceholder && !reduced.hasMore,"original metadata preserved")
    expect(resolveDynamicStateAfterAuthorUnfollow(original,0)===original,"invalid MID is original no-op")
    expect(original.items.size==4,"original immutable input preserved")
    cases+=buildJsonObject{put("name","exact-original-reducer");put("passed",true);put("assertions",6)}

    runCase("unfollow-home-scope-cache",root){h->
        val all=h.timeline();val video=h.timeline("video");val pgc=h.timeline("pgc");val article=h.timeline("article")
        val users=h.users();users.hydrateUsers();users.updateTimeline(all.page.items)
        users.selectUser(22);eventually("selected-UP fetched"){!users.userLoading}
        val space=GenericOwner(originalRows);val topic=GenericOwner(originalRows);h.registry.register(space);h.registry.register(topic)
        val cached=h.cache.openCurrent()!!;cached.markNotInterested("keep")
        h.social.setFollowing(22,false);eventually("original event collected"){h.seen.size==1}
        expect(h.seen.single().change==FollowStateChange(22,false),"original success payload")
        expect(h.seen.single().owner==h.owner,"exact credential epoch stamped")
        listOf(all,video,pgc,article).forEach{expect(ids(it.page.items)==listOf("keep","unknown"),"home retained timeline reduced")}
        expect(users.selectedUid==null && users.selectedLogicalTab==0,"original selected UP cleared and All selected")
        eventually("original selected tab persisted"){users.preferences.selectedTab==0}
        expect(users.userItems.isEmpty()&&!users.userLoading&&users.hasUserMore,"original selected UP task reset")
        expect(users.users.none{it.uid==22},"followings and live side rail removed")
        expect((getField(users,"followings") as List<*>).size==1,"existing following owner reduced")
        expect((getField(users,"live") as List<*>).isEmpty(),"existing live owner reduced")
        expect(ids(space.rows)==ids(originalRows)&&space.calls==0,"Space is outside original home unfollow scope")
        expect(ids(topic.rows)==ids(originalRows)&&topic.calls==0,"Topic is outside original home unfollow scope")
        h.cache.flush();expect(ids(cached.cachedAllItems.value)==listOf("keep","unknown"),"same sole cache actor accepted reduced All")
        expect(cached.notInterestedIds.value==setOf("keep"),"unfollow does not alter NotInterested")
        val cold=DesktopDynamicCache(h.sessions,DesktopPluginStore(h.store.root));try {
            expect(ids(cold.openCurrent()!!.cachedAllItems.value)==listOf("keep","unknown"),"cold disk reader reproduces reduced All")
        }finally{cold.shutdownForRestore()}
        h.registry.bindings.likeConfirmed("keep",true);expect(space.calls==1&&topic.calls==1,"existing generic card mutation scope preserved")
    }
    runCase("follow-ttl-refresh-success-only",root){h->
        var calls=0;var rows=listOf(FollowingUser(33,"UP-33",""))
        val users=h.users(follow={calls++;FollowingsData(rows,rows.size)},live={emptyList()});users.hydrateUsers()
        val before=calls;rows=rows+FollowingUser(22,"UP-22","")
        h.social.setFollowing(22,true);eventually("follow success invalidated fresh TTL and hydrated"){calls>before&&users.users.any{it.uid==22}}
        expect(h.seen.single().change.isFollowing,"follow event true")
        expect((getField(users,"lastFollowingsLoadMs") as Long)>0,"original TTL stamp restored by successful fetch")
        val rail=users.users;h.transport.code=-400
        val failed=runCatching{h.social.setFollowing(33,false)}
        expect(failed.exceptionOrNull() is BiliApiException,"failed account write reported")
        delay(30);expect(h.seen.size==1&&users.users==rail,"failure emitted nothing and removed no author")
        expect(h.transport.writes==listOf(22L to 1,33L to 2),"original relation acts exactly once no retry")
    }
    runCase("canceled-success-no-event",root){h->
        val all=h.timeline();val enter=CountDownLatch(1);val release=CountDownLatch(1)
        h.transport.intercept={enter.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val job=h.scope.launch{h.social.setFollowing(22,false)}
        withContext(Dispatchers.IO){check(enter.await(5,TimeUnit.SECONDS))};job.cancel();release.countDown();job.join()
        delay(30);expect(job.isCancelled,"actual mutation coroutine cancellation")
        expect(h.seen.isEmpty()&&all.page.items.size==4,"canceled response never commits local follow event")
        expect(h.transport.writes.size==1,"no cancellation transport retry")
    }
    runCase("same-mid-new-epoch-late-response",root){h->
        val all=h.timeline();val enter=CountDownLatch(1);val release=CountDownLatch(1)
        h.transport.intercept={enter.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val write=h.scope.async{runCatching{h.social.setFollowing(22,false)}}
        withContext(Dispatchers.IO){check(enter.await(5,TimeUnit.SECONDS))};h.rotateSameMid();release.countDown()
        expect(write.await().isFailure,"same MID with new credential rejects delayed write response")
        delay(20);expect(h.seen.isEmpty()&&all.page.items.size==4,"old response cannot mutate old or new epoch")
        expect(h.repository.sessionEpoch!=h.owner.epoch&&h.repository.account.value!!.mid==h.owner.mid,"same MID explicit epoch rotation")
        val newerSession=DesktopDynamicCardSession(h.repository)
        val newer=DesktopDynamicCardStateRegistry(h.sessions,h.cache,h.repository.sessionEpoch,newerSession::isOwned)
        val cached=h.cache.openCurrent()!!
        lateinit var fresh:DesktopDynamicTimelineState
        fresh=DesktopDynamicTimelineState("all",{_,_,_->response(originalRows)},newerSession::isOwned,onAllTimelineChanged={if(newer.isCurrentAll(fresh))cached.saveTimeline(it)})
        newer.register(fresh);fresh.initialize(false)
        newer.applyFollowStateChange(DesktopOwnedFollowStateChange(h.owner,FollowStateChange(22,false)))
        expect(fresh.page.items.size==4,"late explicitly old event refused by new registry")
        expect(runCatching{h.repository.followStateEvents.confirm(h.owner,FollowStateChange(22,false))}.isFailure,"producer refuses old event owner atomically")
        newerSession.close();newer.close()
    }
    runCase("queued-old-owner-no-post",root){h->
        val enter=CountDownLatch(1);val release=CountDownLatch(1)
        h.transport.intercept={enter.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val first=h.scope.async{runCatching{h.social.setFollowing(22,false)}}
        withContext(Dispatchers.IO){check(enter.await(5,TimeUnit.SECONDS))}
        val second=h.scope.async(start=CoroutineStart.UNDISPATCHED){runCatching{h.social.setFollowing(33,false)}}
        h.rotateSameMid();release.countDown()
        expect(first.await().isFailure&&second.await().isFailure,"running and mutex queued old owner rejected")
        expect(h.transport.writes.size==1&&h.seen.isEmpty(),"queued operation never posts with new credentials")
    }
    runCase("closed-session-and-registry",root){h->
        val all=h.timeline();h.session.close();h.registry.close()
        h.repository.followStateEvents.confirm(h.owner,FollowStateChange(22,false));eventually("audit saw event"){h.seen.size==1}
        expect(all.page.items.size==4,"closed page refuses follow-state reduction")
    }
    for(mode in listOf("success","failure","cancel")) runCase("pending-home-response-$mode",root){h->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var count=0
        val all=h.timeline(fetch={_,_,_->if(count++==0)response(originalRows,true) else {entered.complete(Unit);release.await();if(mode=="failure")error("fixture feed failure") else response(originalRows,true)}})
        val append=h.scope.async{all.loadMore(false)};entered.await()
        h.social.setFollowing(22,false);eventually("unfollow applied while feed pending"){all.page.items.size==2}
        if(mode=="cancel")append.cancelAndJoin() else {release.complete(Unit);expect(!append.await(),"retired feed version not accepted")}
        expect(ids(all.page.items)==listOf("keep","unknown"),"late feed never resurrects unfollowed author")
        expect(!all.busy&&!all.page.isLoading,"pending feed unlocks after revision rejection")
        h.cache.flush();expect(ids(h.cache.openCurrent()!!.cachedAllItems.value)==listOf("keep","unknown"),"late feed cannot overwrite persisted reduced All")
    }
    runCase("pending-followings-live-unfollow",root){h->
        val followingEnter=CompletableDeferred<Unit>();val liveEnter=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val users=h.users(follow={followingEnter.complete(Unit);release.await();FollowingsData(listOf(FollowingUser(22,"UP-22","")),1)},
            live={liveEnter.complete(Unit);release.await();listOf(LiveRoom(uid=22,roomid=222,uname="UP-22"))})
        users.updateTimeline(originalRows)
        val hydrate=h.scope.launch{users.hydrateUsers()};followingEnter.await();liveEnter.await()
        h.social.setFollowing(22,false);eventually("unfollow event arrived"){h.seen.size==1};release.complete(Unit);hydrate.join()
        expect(users.users.none{it.uid==22},"old following/live payload cannot restore side rail")
        expect((getField(users,"followings") as List<*>).isEmpty()&&(getField(users,"live") as List<*>).isEmpty(),"existing owner keeps reduced backing state")
    }
    runCase("following-while-hydration-pending",root){h->
        val enter=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val users=h.users(follow={calls++;if(calls==1){enter.complete(Unit);release.await();FollowingsData(listOf(FollowingUser(33,"UP-33","")),1)}
            else FollowingsData(listOf(FollowingUser(22,"UP-22",""),FollowingUser(33,"UP-33","")),2)},live={emptyList()})
        val hydrate=h.scope.launch{users.hydrateUsers()};enter.await()
        h.social.setFollowing(22,true);eventually("follow event invalidates active hydration"){getField(users,"followingsRefreshRequested")==true}
        release.complete(Unit);hydrate.join();eventually("queued original following reload completed"){users.users.any{it.uid==22}}
        expect(calls==2,"exactly one queued TTL reload no lost invalidation")
        expect(getField(users,"followingsFullyLoaded")==true,"new response completion determines original full flag")
    }
    runCase("pending-selected-up-unfollow",root){h->
        val enter=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val users=h.users(request={enter.complete(Unit);withContext(NonCancellable){release.await()};response(listOf(row("late-up",22)))})
        users.selectUser(22);enter.await();h.social.setFollowing(22,false);eventually("selected UP cleared before request unwinds"){users.selectedUid==null}
        release.complete(Unit);delay(40)
        expect(users.userItems.isEmpty()&&!users.userLoading,"uncooperative selected-UP response rejected by cancellation/token")
        expect(users.selectedLogicalTab==0,"unfollow retains original selection route")
    }
    runCase("guest-csrf-self-invalid-no-http",root){h->
        expect(runCatching{h.social.setFollowing(11,true)}.isFailure,"self follow blocked")
        expect(runCatching{h.social.setFollowing(0,true)}.isFailure,"invalid target blocked")
        h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-incomplete"),AccountSummary(11,"fixture",""));h.bootstrapReady()
        expect(runCatching{h.social.setFollowing(22,true)}.isFailure,"missing csrf blocked")
        h.sessions.logout();expect(runCatching{h.social.setFollowing(22,true)}.isFailure,"guest blocked")
        expect(h.transport.writes.isEmpty()&&h.seen.isEmpty(),"invalid/partial/guest actions create zero transport/event")
    }
}

fun main(args:Array<String>) {
    val out=Path.of(args[0]);val root=Path.of(args[1]);System.setSecurityManager(NoSockets())
    val dispatcher=Executors.newSingleThreadExecutor{r->Thread(r,"follow-fixture-model-owner")}.asCoroutineDispatcher()
    try {
        runBlocking(dispatcher){tests(root)}
        val identities=listOf(DesktopRepository::class.java,DesktopSocialRepository::class.java,DesktopDynamicCardSession::class.java,
            DesktopDynamicCardStateRegistry::class.java,DesktopDynamicUsersState::class.java,DesktopDynamicTimelineState::class.java,
            DesktopSessionStore::class.java,DesktopDynamicCache::class.java,DesktopPluginStore::class.java,DynamicItem::class.java,
            DesktopFollowStateEvents::class.java,FollowStateChange::class.java,DynamicUiState::class.java)
        val result=buildJsonObject {
            put("passed",true);put("assertions",assertions);put("cases",JsonArray(cases));put("modelOwnerThread","follow-fixture-model-owner")
            put("socketGuardInstalled",true);put("outsideHTTP",false);put("MainIntegrated",false);put("HWND",false);put("package",false)
            put("fixtureTransport","reflection replaces Repository client transport + visitor readiness only; actual Retrofit route, checks, event/session/registry/models/cache")
            put("classSources",JsonArray(identities.map{buildJsonObject{put("class",it.name);put("codeSource",it.protectionDomain.codeSource.location.toString())}}))
        }
        Files.writeString(out.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result));println("PASS total $assertions assertions")
    }finally{dispatcher.close()}
}
