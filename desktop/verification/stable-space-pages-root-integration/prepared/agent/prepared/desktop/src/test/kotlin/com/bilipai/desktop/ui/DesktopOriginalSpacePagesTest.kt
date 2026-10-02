package com.bilipai.desktop.ui

import com.android.purebilibili.feature.space.*
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import okhttp3.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.test.Test
import kotlin.coroutines.CoroutineContext

private data class SpaceHit(val method:String,val path:String,val query:Map<String,String>,val fields:Map<String,String>)
private data class SpaceReply(val body:String="""{"code":0,"data":{}}""",val status:Int=200,val headers:Map<String,String> = emptyMap())
private fun fields(raw:String)=raw.split('&').filter {it.isNotBlank()}.associate {
    val parts=it.split('=',limit=2)
    URLDecoder.decode(parts[0],Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1){""},Charsets.UTF_8)
}
private class SpaceFixture(val guest:Boolean=false,context:CoroutineContext):AutoCloseable {
    val root=Files.createTempDirectory("bp-space-")
    val sessions=DesktopSessionStore(root.resolve("synthetic-session.json"),persistent=false)
    val repository=DesktopRepository(sessions)
    // The original lifecycle VM and real retained Root children share the UI
    // dispatcher. Keep this event loop, while real HTTP completions stay on IO.
    val parent=CoroutineScope(context.minusKey(Job)+SupervisorJob())
    val retained=AtomicBoolean(true)
    val visible=AtomicBoolean(true)
    val lock=Any()
    val hits=ConcurrentLinkedQueue<SpaceHit>()
    val executor=Executors.newCachedThreadPool()
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile var response:(SpaceHit)->SpaceReply={default(it)}
    val environment:DesktopOriginalSpaceEnvironment
    val entry:DesktopOriginalSpacePageEntry
    init {
        if(!guest)sessions.saveAccount(mapOf("SESSDATA" to "synthetic-space-only","bili_jct" to "synthetic-space-csrf"),AccountSummary(42,"Space Fixture",""))
        server.executor=executor
        server.createContext("/") {exchange->
            val body=exchange.requestBody.use {it.readBytes().toString(Charsets.UTF_8)}
            val hit=SpaceHit(exchange.requestMethod,exchange.requestURI.path,fields(exchange.requestURI.rawQuery.orEmpty()),
                if(exchange.requestHeaders.getFirst("Content-Type")?.startsWith("application/x-www-form-urlencoded")==true)fields(body)else emptyMap())
            hits+=hit
            try {val reply=response(hit);val bytes=reply.body.toByteArray();exchange.responseHeaders.set("Content-Type","application/json")
                reply.headers.forEach {(k,v)->exchange.responseHeaders.set(k,v)}
                exchange.sendResponseHeaders(reply.status,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
            }catch(_:java.io.IOException){}finally{exchange.close()}
        }
        server.start()
        // Fixture ONLY: exact original hosts/routes rewrite to one task-owned
        // loopback listener. Nothing may reach an unexpected external host.
        val transport=repository.httpClient.newBuilder().retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
            .addInterceptor {chain->val request=chain.request()
                check(request.url.host in setOf("api.bilibili.com","app.bilibili.com","api.live.bilibili.com"))
                check(request.url.encodedPath in allowed) {"Unexpected fixture route"}
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.address.port).build()).build())
            }.build()
        environment=DesktopOriginalSpaceEnvironment(repository,repository.sessionEpoch,if(guest)null else 42,77,parent,
            retained::get,visible::get,{block->synchronized(lock){if(retained.get()){block();true}else false}},{},transport)
        entry=DesktopOriginalSpacePageEntry(environment)
    }
    fun success()=entry.viewModel.uiState.value as? SpaceUiState.Success
    suspend fun await(test:()->Boolean) {withTimeout(6000){while(!test())delay(10)}}
    suspend fun loaded():SpaceViewModel {
        val vm=entry.viewModel;vm.loadSpaceInfo(77)
        await {success()?.videos?.isNotEmpty()==true && success()?.isLoadingMore==false}
        return vm
    }
    suspend fun <T> call(body:suspend()->T):T=environment.withActualCaller(body)
    fun default(hit:SpaceHit):SpaceReply=when(hit.path) {
        "/x/v2/space"->SpaceReply("""{"code":0,"data":{"default_tab":"home","card":{"mid":"77","name":"Known UP","face":"https://fixture.invalid/face.png","sign":"Fixture signature"},"archive":{"count":90,"item":[{"bvid":"BVfixture1","title":"Aggregate title","cover":"https://fixture.invalid/cover.png"}]}}}""")
        "/x/web-interface/nav"->SpaceReply("""{"code":0,"data":{"isLogin":true,"mid":42,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/abcdef0123456789abcdef0123456789.png"}}}""")
        "/x/space/wbi/arc/search"->SpaceReply(videoPage(hit.query["pn"]?.toIntOrNull()?:1))
        "/x/space/wbi/acc/info"->SpaceReply("""{"code":0,"data":{"mid":77,"name":"Known UP","face":"https://fixture.invalid/face.png","is_followed":false}}""")
        "/x/relation"->SpaceReply("""{"code":0,"data":{"attribute":0}}""")
        "/x/relation/stat"->SpaceReply("""{"code":0,"data":{"mid":77,"following":4,"follower":11}}""")
        "/x/space/upstat"->SpaceReply("""{"code":0,"data":{"archive":{"view":15},"likes":9}}""")
        "/x/web-interface/history/cursor"->SpaceReply("""{"code":0,"data":{"list":[],"cursor":{"max":0,"view_at":0,"business":""}}}""")
        "/x/polymer/web-space/seasons_series_list"->SpaceReply("""{"code":0,"data":{"items_lists":{"seasons_list":[],"series_list":[]}}}""")
        "/x/v3/fav/folder/created/list-all","/x/v3/fav/folder/collected/list"->SpaceReply("""{"code":0,"data":{"list":[],"count":0}}""")
        "/x/polymer/web-dynamic/v1/feed/space"->SpaceReply(dynamicPage("dyn1","Alpha original",false,""))
        "/x/space/wbi/article"->SpaceReply("""{"code":0,"data":{"articles":[],"count":0,"pn":1,"ps":30}}""")
        "/pugv/app/web/season/page"->SpaceReply("""{"code":0,"data":{"items":[],"page":{"next":false}}}""")
        "/audio/music-service/web/song/upper"->SpaceReply("""{"code":0,"data":{"data":[{"id":501,"title":"Actual synthetic song","author":"Known UP","duration":130}],"totalSize":1,"pageCount":1,"curPage":1}}""")
        "/x/upower/up/member/rank/v2"->SpaceReply(rankPage(91))
        "/xlive/app-ucenter/v1/guard/MainGuardCardAll"->SpaceReply(guardPage(listOf(1,2,3,4),false))
        "/x/relation/tags"->SpaceReply("""{"code":0,"data":[{"tagid":5,"name":"Known group","count":2}]}""")
        "/x/relation/tag/user"->SpaceReply("""{"code":0,"data":{"5":"Known group"}}""")
        "/x/space/notice"->SpaceReply("""{"code":0,"data":"Original notice"}""")
        else->SpaceReply("""{"code":0,"data":{}}""")
    }
    override fun close(){runBlocking{retained.set(false);check(environment.closeAndJoin());parent.cancel()};server.stop(0);executor.shutdownNow();repository.httpClient.dispatcher.executorService.shutdownNow();repository.httpClient.connectionPool.evictAll()}
    companion object {
        val allowed=setOf("/x/v2/space","/x/web-interface/nav","/x/web-interface/card","/x/space/wbi/arc/search","/x/space/wbi/acc/info",
            "/x/relation","/x/relation/stat","/x/space/upstat","/x/web-interface/history/cursor","/x/polymer/web-space/seasons_series_list",
            "/x/v3/fav/folder/created/list-all","/x/v3/fav/folder/collected/list","/x/polymer/web-dynamic/v1/feed/space","/x/space/wbi/article",
            "/pugv/app/web/season/page","/audio/music-service/web/song/upper","/x/upower/up/member/rank/v2","/xlive/app-ucenter/v1/guard/MainGuardCardAll",
            "/x/relation/modify","/x/relation/tags","/x/relation/tag/user","/x/relation/tag/addUsers","/x/relation/tag/delUsers","/x/space/top/arc","/x/space/notice")
    }
}
private fun videoPage(page:Int,bvid:String="BVfixture$page",count:Int=90)= """{"code":0,"data":{"list":{"vlist":[{"bvid":"$bvid","aid":100,"title":"Video $page","pic":"https://fixture.invalid/cover.png","mid":77,"author":"Known UP","typeid":4,"typename":"Known category"}],"tlist":{"4":{"tid":4,"name":"Known category","count":$count}}},"page":{"pn":$page,"ps":30,"count":$count}}}"""
private fun dynamicPage(id:String,text:String,more:Boolean,offset:String)="""{"code":0,"data":{"has_more":$more,"offset":"$offset","items":[{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","modules":{"module_author":{"mid":77,"name":"Known UP"},"module_dynamic":{"desc":{"text":"$text","rich_text_nodes":[]}}}}]}}"""
private fun rankPage(mid:Int)="""{"code":0,"data":{"rank_info":[{"mid":$mid,"nickname":"Known supporter","day":8}],"level_info":[{"privilege_type":1,"name":"Known tier","member_total":1},{"privilege_type":2,"name":"Second tier","member_total":2}]}}"""
private fun guardPage(ids:List<Int>,more:Boolean)="""{"code":0,"data":{"has_more":${if(more)1 else 0},"guard_top_list":[${ids.joinToString(","){"""{"uid":$it,"username":"Guard$it","guard_level":3}"""}}]}}"""

class DesktopOriginalSpacePagesTest {
    @Test fun actualOriginalAggregateLegacyHydrationAndPublicGuestReads():Unit=runBlocking {
        SpaceFixture(true,currentCoroutineContext()).use {f->f.loaded();val state=checkNotNull(f.success())
            check(state.userInfo.mid==77L&&state.userInfo.name=="Known UP")
            check(state.videos.single().bvid=="BVfixture1")
            check(f.hits.any {it.path=="/x/v2/space"&&it.query["vmid"]=="77"})
            check(f.hits.none {it.method=="POST"})
            check(f.hits.filter {it.path.endsWith("arc/search")}.all {it.query["mid"]=="77"&&it.query["ps"]=="30"&&it.query["w_rid"]?.length==32})
        }
    }
    @Test fun rawAudioSidAndOriginalRequestDefaultsSurviveMapping():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();vm.loadSpaceAudios(true);f.await {f.success()?.audios?.isNotEmpty()==true&&!f.success()!!.isLoadingAudios}
            check(f.success()!!.audios.single().id==501L)
            val hit=f.hits.first {it.path.endsWith("song/upper")};check(hit.query["uid"]=="77"&&hit.query["pn"]=="1"&&hit.query["ps"]=="30"&&hit.query["order"]=="1"&&hit.query["jsonp"]=="jsonp")
        }
    }
    @Test fun videoPaginationAndOriginalOldestOrderingRemainReal():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();vm.loadMoreVideos();f.await {f.success()?.videos?.size==2}
            check(f.success()!!.videos.map {it.bvid}==listOf("BVfixture1","BVfixture2"))
            vm.selectSortOrder(VideoSortOrder.OLDEST_PUBDATE);f.await {f.success()?.sortOrder==VideoSortOrder.OLDEST_PUBDATE&&!f.success()!!.isLoadingMore}
            check(f.hits.last {it.path.endsWith("arc/search")}.query["pn"]=="3")
            check(f.hits.last {it.path.endsWith("arc/search")}.query["order"]=="pubdate")
        }
    }
    @Test fun actualDelayedPreviousSortCannotOverwriteChosenSort():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.response={hit->if(hit.path.endsWith("arc/search")){
                if(hit.query["order"]=="click"){entered.countDown();release.await(4,TimeUnit.SECONDS);SpaceReply(videoPage(1,"BVold"))}
                else SpaceReply(videoPage(1,"BVnew"))
            }else f.default(hit)}
            vm.selectSortOrder(VideoSortOrder.CLICK);check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
            vm.selectSortOrder(VideoSortOrder.STOW);f.await {f.success()?.videos?.firstOrNull()?.bvid=="BVnew"};release.countDown();delay(80)
            check(f.success()!!.sortOrder==VideoSortOrder.STOW&&f.success()!!.videos.single().bvid=="BVnew") {"sort=${f.success()!!.sortOrder}; ids=${f.success()!!.videos.map {it.bvid}}"}
        }
    }
    @Test fun originalVideoFailureRetryIsBusinessBoundedNotTransportReplay():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();val attempts=AtomicInteger()
            f.response={hit->if(hit.path.endsWith("arc/search")){if(attempts.incrementAndGet()==1)SpaceReply("""{"code":-412,"message":"Synthetic rejection"}""")else SpaceReply(videoPage(1,"BVretry"))}else f.default(hit)}
            vm.selectCategory(4);f.await {f.success()?.videos?.singleOrNull()?.bvid=="BVretry"}
            check(attempts.get()==2&&f.hits.last {it.path.endsWith("arc/search")}.query["tid"]=="4")
        }
    }
    @Test fun dynamicPaginationUsesOriginalOffsetFeaturesAndLocalRichTextSearch():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();vm.selectMainTab(SpaceMainTab.DYNAMIC)
            f.response={hit->if(hit.path.endsWith("feed/space"))SpaceReply(if(hit.query["offset"].isNullOrEmpty())dynamicPage("1","First text",true,"next")else dynamicPage("2","needle rich text",false,"end"))else f.default(hit)}
            vm.loadSpaceDynamic(true);f.await {f.success()?.hasLoadedDynamicsOnce==true&&!f.success()!!.isLoadingDynamics}
            vm.updateSearchQuery("needle");f.await {f.success()?.dynamics?.any {it.id_str=="2"}==true}
            val state=f.success()!!;check(filterSpaceDynamicItemsByQuery(state.dynamics,"needle").single().id_str=="2")
            val hit=f.hits.last {it.path.endsWith("feed/space")};check(hit.query["host_mid"]=="77"&&hit.query["offset"]=="next"&&hit.query["timezone_offset"]=="-480"&&!hit.query["features"].isNullOrBlank())
        }
    }
    @Test fun rankLevelDelayedOldResponseCannotOverwriteChosenTier():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.response={hit->if(hit.path.endsWith("rank/v2")){if(hit.query["privilege_type"]==null){entered.countDown();release.await(4,TimeUnit.SECONDS);SpaceReply(rankPage(10))}else SpaceReply(rankPage(20))}else f.default(hit)}
            val vm=f.entry.rank(77,"Known UP",8);check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
            vm.selectLevel(1);f.await {!vm.uiState.value.isLoading&&vm.uiState.value.items.firstOrNull()?.mid==20L};release.countDown();delay(80)
            check(vm.uiState.value.selectedPrivilegeType==1&&vm.uiState.value.items.single().mid==20L)
            val hit=f.hits.last {it.path.endsWith("rank/v2")};check(hit.query["up_mid"]=="77"&&hit.query["pn"]=="1"&&hit.query["ps"]=="100"&&hit.query["web_location"]=="333.1196")
        }
    }
    @Test fun guardOriginalPodiumAndPageDefaultsRemainReal():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->f.response={hit->if(hit.path.endsWith("MainGuardCardAll"))SpaceReply(if(hit.query["page"]=="1")guardPage(listOf(1,2,3,4),true)else guardPage(listOf(5,6),false))else f.default(hit)}
            val vm=f.entry.guard(77,"Known UP",6);f.await {!vm.uiState.value.isLoading};check(vm.uiState.value.tops.map {it.uid}==listOf(1L,2L,3L))
            vm.loadMore();f.await {vm.uiState.value.items.size==3};check(vm.uiState.value.items.map {it.uid}==listOf(4L,5L,6L))
            check(f.hits.filter {it.path.endsWith("MainGuardCardAll")}.map {it.query["page"]}==listOf("1","2"))
            check(f.hits.filter {it.path.endsWith("MainGuardCardAll")}.all {it.query["ruid"]=="77"&&it.query["page_size"]=="20"})
        }
    }
    @Test fun followHttp503DoesNotImplicitlyReplayAndRollsBackOriginalState():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();f.response={hit->if(hit.path=="/x/relation/modify")SpaceReply("""{"code":-1}""",503,mapOf("Retry-After" to "0"))else f.default(hit)}
            vm.toggleFollow();f.await {f.hits.any {it.path=="/x/relation/modify"}&&!f.success()!!.userInfo.isFollowed};delay(100)
            val posts=f.hits.filter {it.path=="/x/relation/modify"};check(posts.size==1&&posts.single().fields["fid"]=="77"&&posts.single().fields["act"]=="1"&&"re_src" !in posts.single().fields&&posts.single().fields["csrf"]=="synthetic-space-csrf")
        }
    }
    @Test fun coveredEntryRejectsSubmitBeforeOptimisticFlagsThenCanSubmitOnBack():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();f.visible.set(false)
            check(runCatching {vm.toggleFollow()}.exceptionOrNull() is CancellationException)
            check(f.hits.none {it.path=="/x/relation/modify"});f.visible.set(true)
            vm.toggleFollow();f.await {f.hits.any {it.path=="/x/relation/modify"}&&f.success()!!.userInfo.isFollowed}
            check(f.hits.count {it.path=="/x/relation/modify"}==1)
        }
    }
    @Test fun sameMidEpochReplacementRejectsLateActualStatePublication():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.response={hit->if(hit.path=="/x/v2/space"){entered.countDown();release.await(4,TimeUnit.SECONDS);f.default(hit)}else f.default(hit)}
            val vm=f.entry.viewModel;vm.loadSpaceInfo(77);check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
            f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-space-only-B","bili_jct" to "synthetic-space-csrf-B"),AccountSummary(42,"Same MID successor",""))
            release.countDown();delay(100);check(!f.environment.owns());check(vm.uiState.value !is SpaceUiState.Success)
        }
    }
    @Test fun retainedEntryOwnsOriginalVmAndSelectionUntilRemovalDrain():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val vm=f.loaded();vm.selectMainTab(SpaceMainTab.FAVORITE);f.visible.set(false)
            check(f.entry.viewModel===vm&&f.environment.owns());f.visible.set(true)
            check(f.entry.viewModel.selectedMainTab.value==3)
            f.retained.set(false);check(f.environment.closeAndJoin());check(!f.environment.owns())
            check(runCatching {vm.loadSpaceInfo(77)}.exceptionOrNull() is CancellationException)
        }
    }
    @Test fun actualCallerCancellationRejectsCookieAndStateWhileCloseDrains():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.response={hit->if(hit.path.endsWith("rank/v2")){entered.countDown();release.await(4,TimeUnit.SECONDS);SpaceReply(rankPage(777))}else f.default(hit)}
            val result=CompletableDeferred<Unit>();val caller=f.parent.launch {f.call {f.environment.spaceApi.getUpowerRank(mapOf("up_mid" to "77"));f.environment.mutableStateFlow(0).value=1};result.complete(Unit)}
            check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)});caller.cancelAndJoin();release.countDown();delay(80)
            check(!result.isCompleted);check(f.environment.closeAndJoin())
        }
    }
    @Test fun immutableUpEntryRejectsWrongMidAndGuestFollowIsPermissionFailure():Unit=runBlocking {
        SpaceFixture(true,currentCoroutineContext()).use {f->val vm=f.loaded()
            check(runCatching {vm.loadSpaceInfo(88)}.isFailure)
            val result=f.call {f.environment.actions.followUser(77,true)}
            check(result.isFailure&&result.exceptionOrNull()!!.message!!.contains("登录"))
            check(f.hits.none {it.path=="/x/relation/modify"})
        }
    }
    @Test fun cancelledParallelRequestCannotAdoptCookiesWhileItsPageSurvives():Unit=runBlocking {
        SpaceFixture(context=currentCoroutineContext()).use {f->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.response={hit->if(hit.path.endsWith("rank/v2")) {
                entered.countDown();release.await(4,TimeUnit.SECONDS)
                SpaceReply(rankPage(777),headers=mapOf("Set-Cookie" to "buvid3=cancelled-child-only; Path=/; HttpOnly"))
            }else f.default(hit)}
            f.call {coroutineScope {
                val child=async {f.environment.spaceApi.getUpowerRank(mapOf("up_mid" to "77"))}
                check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
                child.cancelAndJoin();release.countDown();delay(80)
                check(f.environment.owns());f.environment.checkpoint()
                check("buvid3" !in f.sessions.currentCookies())
            }}
        }
    }
}
