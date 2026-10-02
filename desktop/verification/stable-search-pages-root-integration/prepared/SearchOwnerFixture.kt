@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import coil3.*
import coil3.decode.DataSource
import coil3.request.SuccessResult
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.database.entity.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.search.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Canvas
import java.awt.Frame
import java.nio.file.*
import java.lang.reflect.Proxy
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import java.util.concurrent.CopyOnWriteArrayList

private val json=Json { ignoreUnknownKeys=true;coerceInputValues=true }
private val asserts=mutableListOf<String>()
private var trace:()->String={""}
private fun verify(name:String,yes:Boolean) { check(yes) { name+"; "+trace() };asserts+=name;println("PASS $name") }
private suspend fun until(name:String,test:()->Boolean) { try { withTimeout(5000) { while(!test())delay(8) };verify(name,true) }
    catch(f:TimeoutCancellationException) { error(name+"; "+trace()) } }

/** Fixture responses use ORIGINAL models and API interfaces; no production DTO/client is replaced. */
private class ApiFixture(private val scope:CoroutineScope) {
    data class Call(val name:String,val params:Map<String,String>,val query:String)
    val calls=CopyOnWriteArrayList<Call>()
    var slowQuery="";var holdNextTrending=false;var trendingIndex=0;var heldTrendingSerial=0
    private fun response(name:String,page:Int,query:String,serial:Int):Any {
        val item="""{"bvid":"BVfixture$page","aid":$page,"title":"Video $query $page","author":"Fixture user","mid":77,"duration":"01:20","play":20,"video_review":2}"""
        val data="""{"code":0,"data":{"page":$page,"numPages":3,"numResults":3,"result":[$item]}}"""
        return when(name) {
            "search"->json.decodeFromString<SearchTypeResponse>(data)
            "searchUp"->json.decodeFromString<SearchUpResponse>("""{"data":{"page":$page,"numPages":3,"numResults":3,"result":[{"mid":77,"uname":"Fixture user","fans":123}]}}""")
            "searchBangumi","searchMediaFt"->json.decodeFromString<BangumiSearchResponse>("""{"data":{"page":$page,"numPages":3,"numResults":3,"result":[{"season_id":19,"title":"Fixture media","media_id":31}]}}""")
            "searchLive"->json.decodeFromString<LiveRoomSearchResponse>("""{"data":{"page":$page,"numPages":3,"numResults":3,"result":[{"roomid":71,"uid":77,"title":"Fixture room","uname":"Fixture broadcaster"}]}}""")
            "searchLiveUser"->json.decodeFromString<SearchLiveUserResponse>("""{"data":{"page":$page,"numPages":3,"numResults":3,"result":[{"roomid":71,"uid":77,"uname":"Fixture live user","is_live":true}]}}""")
            "searchArticle"->json.decodeFromString<SearchArticleResponse>("""{"data":{"page":$page,"numPages":3,"numResults":3,"result":[{"id":41,"mid":77,"title":"Fixture article"}]}}""")
            "searchTopic"->json.decodeFromString<SearchTopicResponse>("""{"data":{"page":$page,"numPages":3,"numResults":3,"result":[{"topic_id":51,"topic_name":"Fixture topic","title":"Fixture topic"}]}}""")
            "searchPhoto"->json.decodeFromString<SearchPhotoResponse>("""{"data":{"page":$page,"numPages":3,"numResults":3,"result":[{"id":61,"uid":77,"title":"Fixture photo"}]}}""")
            "getSearchSuggest"->SearchSuggestResponse(result=SearchSuggestResult(listOf(SearchSuggestTag(term=query+" suggestion",name=query+" suggestion"))))
            "getTrendingList"->SearchTrendingResponse(topList=listOf(HotItem(keyword="Pinned hot")),list=listOf(HotItem(keyword="Fresh hot $serial")))
            "getHotSearch"->HotSearchResponse(data=HotSearchData(TrendingData(emptyList())))
            "getDefaultSearch","getDefaultSearchLegacy"->SearchDefaultResponse(data=SearchDefaultData(showName="Default hint"))
            "getSearchRecommend"->SearchRecommendResponse(data=SearchRecommendData(listOf(HotItem(keyword="Discover original"))))
            "getNavInfo"->NavResponse()
            else->error("Unexpected original API method: $name")
        }
    }
    @Suppress("UNCHECKED_CAST") fun <T> proxy(type:Class<T>):T = Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args ->
        if(method.name=="toString")return@newProxyInstance "FixtureOriginalApi"
        val all=args.orEmpty();val continuation=all.last() as Continuation<Any>
        val params=(all.firstOrNull() as? Map<String,String>).orEmpty().toMap()
        val query=params["keyword"] ?: (all.firstOrNull() as? String).orEmpty()
        calls+=Call(method.name,params,query)
        val capturedTrending=if(method.name=="getTrendingList")++trendingIndex else 0
        val delayedTrending = method.name=="getTrendingList" && holdNextTrending
        if(delayedTrending) { holdNextTrending=false;heldTrendingSerial=capturedTrending }
        scope.launch {
            // Deliberately noncooperative upstream completion verifies captured child Job fences.
            if(query==slowQuery&&query.isNotEmpty() || delayedTrending)delay(450) else delay(12)
            try { continuation.resume(response(method.name,params["page"]?.toIntOrNull()?:1,query,capturedTrending)) }
            catch(failure:Throwable) { continuation.resumeWithException(failure) }
        }
        COROUTINE_SUSPENDED
    } as T
}

private class SearchScene(val scene:ImageComposeScene) {
    var nanos=0L;var pointerEvents=0;var keyboardEvents=0
    fun nodes():List<SemanticsNode> { fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) } }
    fun labels()=nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    suspend fun frame() { nanos+=32_000_000;scene.render(nanos).close();delay(7) }
    suspend fun wait(name:String,test:()->Boolean) { try { withTimeout(7000) { while(!test())frame() };repeat(5){frame()};verify(name,true) }
        catch(failure:TimeoutCancellationException) { error("$name: actual labels ${labels()}") } }
    suspend fun press(node:SemanticsNode) { val p=node.boundsInRoot.center;check(p.x in 0f..1100f&&p.y in 0f..900f)
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+40,buttons=PointerButtons());pointerEvents+=2;repeat(6){frame()} }
    suspend fun text(name:String) { press(nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==name}==true }) }
    suspend fun key(id:Int,code:Int,char:Char) { val awt=java.awt.event.KeyEvent(Canvas(),id,System.currentTimeMillis(),0,code,char)
        val native=Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").getMethod("toComposeEvent",java.awt.event.KeyEvent::class.java).invoke(null,awt)
        scene.sendKeyEvent(KeyEvent(native));keyboardEvents++;frame() }
    fun png(path:Path) { scene.render(nanos+1).use { Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes) } }
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);val root=Path.of(args[1]);Files.createDirectories(output)
    val job=SupervisorJob(coroutineContext[Job]);val parent=CoroutineScope(coroutineContext+job)
    val fixtureJob=SupervisorJob();val fixtureScope=CoroutineScope(Dispatchers.Default+fixtureJob)
    val api=ApiFixture(fixtureScope)
    val store=DesktopPluginStore(root.resolve("global"));val context=DesktopPluginContext(store)
    val prefs=DesktopSearchPreferences(root.resolve("accounts-owned"))
    val settings=DesktopOriginalHomePreferences.create(store,parent,false,{false})
    val backToTop=DesktopFavoritePreferences(store)
    val liquidTabs=DesktopLiquidTabSettings(store)
    val dynamicSettings=DesktopDynamicTimelinePreferences(context)
    val homeCards=DesktopHomeCardPreferences(context)
    val blocked=MutableStateFlow<List<BlockedUp>>(emptyList())
    var owned=true;var published=true;val commits=Any();var watchLaterCalls=0
    fun env():DesktopOriginalSearchEnvironment = DesktopOriginalSearchEnvironment(parent,
        { owned&&published },{ action -> synchronized(commits){if(owned&&published){action();true}else false} },
        context,settings,prefs,100L,DesktopOriginalSearchBlocked(blocked),
        { current -> DesktopPersonalArticleResolver(okhttp3.Call.Factory { error("Uninvoked article transport in this focused Search fixture") },current) },
        backToTop,
        { current -> DesktopOriginalSearchRepository(api.proxy(SearchApi::class.java),api.proxy(BilibiliApi::class.java),
            {Result.success("abcdef1234567890abcdef1234567890" to "1234567890abcdef1234567890abcdef")},current) },
        MutableStateFlow(DesktopProfileWindowConfiguration(1100,900)),PlatformContext.INSTANCE,false,
        { _,_->watchLaterCalls++;Result.success(true) },{}, {},{})
    val environment=env();val vm=SearchViewModel(environment)
    trace={ val state=vm.uiState.value;"state=${state.searchType}/${state.currentPage}; searching=${state.isSearching}; error=${state.error}; last=${api.calls.lastOrNull()?.let{it.name+":"+it.params.filterKeys{k->k in setOf("keyword","order","duration","tids","pubtime_begin_s","pubtime_end_s","page")}}}" }
    var loader:ImageLoader?=null;var scene:ImageComposeScene?=null;var resources:DesktopHomeActualWindowResources?=null;var window:Frame?=null
    try {
        vm.ensureLandingBootstrap();until("original default hint/trending/discover bootstrap") { vm.uiState.value.defaultSearchHint=="Default hint"&&vm.uiState.value.hotList.isNotEmpty()&&vm.uiState.value.discoverList.isNotEmpty() }
        vm.onQueryChange("debounce-a");delay(70);vm.onQueryChange("debounce-b");delay(360)
        until("original suggestion debounce final keyword") { vm.uiState.value.suggestions.firstOrNull()?.keyword=="debounce-b suggestion" }
        verify("superseded debounce query not requested",api.calls.none{it.name=="getSearchSuggest"&&it.query=="debounce-a"})
        api.slowQuery="slow-suggest";vm.onQueryChange("slow-suggest");delay(320);vm.onQueryChange("new-suggest");delay(800)
        verify("cancelled slow suggestion cannot publish after new result",vm.uiState.value.suggestions.firstOrNull()?.keyword=="new-suggest suggestion")
        vm.search("nine-tabs");until("original video search first page") {!vm.uiState.value.isSearching&&vm.uiState.value.searchResults.size==1}
        verify("original video WBI/platform params",api.calls.last{it.name=="search"}.params.let{it["platform"]=="pc"&&it["w_rid"]?.length==32&&it["page_size"]=="20"})
        vm.loadMoreResults();until("original video pagination retains unique first/second page") {!vm.uiState.value.isLoadingMore&&vm.uiState.value.currentPage==2&&vm.uiState.value.searchResults.size==2}
        for(type in SearchType.entries.filterNot{it==SearchType.VIDEO}) {
            vm.setSearchType(type);until("original ${type.value} initial request") {!vm.uiState.value.isSearching&&vm.uiState.value.searchType==type&&vm.uiState.value.currentPage==1}
            verify("original ${type.value} current page populated",vm.uiState.value.toCurrentSearchResultPage().totalCount==1)
            vm.loadMoreResults();until("original ${type.value} independent pagination") {!vm.uiState.value.isLoadingMore&&vm.uiState.value.currentPage==2}
        }
        vm.setSearchType(SearchType.VIDEO);verify("return to original cached VIDEO second page",vm.uiState.value.currentPage==2&&vm.uiState.value.searchResults.size==2)
        vm.setSearchType(SearchType.UP);vm.setUpOrder(SearchUpOrder.FANS);vm.setUpOrderSort(SearchOrderSort.ASC);vm.setUpUserType(SearchUserType.VERIFIED)
        until("original UP sort/direction/type request params") { !vm.uiState.value.isSearching&&api.calls.last{it.name=="searchUp"}.params.let{it["order"]=="fans"&&it["order_sort"]=="1"&&it["user_type"]=="3"} }
        vm.setSearchType(SearchType.LIVE);vm.setLiveOrder(SearchLiveOrder.LIVE_TIME)
        until("original live sorting request params") { !vm.uiState.value.isSearching&&api.calls.last{it.name=="searchLive"}.params["order"]=="live_time" }
        vm.setSearchType(SearchType.ARTICLE);vm.setArticleOrder(SearchOrder.PUBDATE);vm.setArticleCategory(SearchArticleCategory.TECHNOLOGY)
        until("original article sorting/category request params") { !vm.uiState.value.isSearching&&api.calls.last{it.name=="searchArticle"}.params.let{it["order"]=="pubdate"&&it["category_id"]=="17"} }
        vm.setSearchType(SearchType.PHOTO);vm.setPhotoOrder(SearchOrder.ATTENTION);vm.setPhotoCategory(SearchPhotoCategory.PHOTOGRAPHY)
        until("original photo sorting/category request params") { !vm.uiState.value.isSearching&&api.calls.last{it.name=="searchPhoto"}.params.let{it["order"]=="attention"&&it["category_id"]=="2"} }
        vm.setSearchType(SearchType.VIDEO)
        vm.setSearchOrder(SearchOrder.CLICK);until("original sorting restarts same video keyword") {!vm.uiState.value.isSearching&&api.calls.last{it.name=="search"}.params["order"]==SearchOrder.CLICK.value}
        vm.setVideoTid(17);vm.setCustomPubTimeRange(1700000000,1710000000);until("original partition/date request params") {!vm.uiState.value.isSearching&&api.calls.last{it.name=="search"}.params.let{it["tids"]=="17"&&it["pubtime_begin_s"]=="1700000000"&&it["pubtime_end_s"]=="1710000000"}}
        vm.setSearchDuration(SearchDuration.UNDER_10MIN);until("original duration request param") {!vm.uiState.value.isSearching&&api.calls.last{it.name=="search"}.params["duration"]==SearchDuration.UNDER_10MIN.value.toString()}
        until("same existing account history records original searches") { prefs.history(100).value.any{it.keyword=="nine-tabs"} }
        vm.deleteHistory(prefs.history(100).value.first{it.keyword=="nine-tabs"});until("original history delete reaches same sole DAO") { prefs.history(100).value.none{it.keyword=="nine-tabs"} }
        prefs.record(100,"must-clear");vm.clearHistory();until("original confirmed history clear reaches same sole DAO") { prefs.history(100).value.isEmpty() }
        coroutineScope { repeat(4) { i ->
            launch(Dispatchers.IO) { environment.history.insert(SearchHistory("owned-cas-$i")) }
            launch(Dispatchers.IO) { prefs.record(100,"legacy-shared-$i") }
        } }
        verify("owned CAS and legacy mutations retain all same-DAO history",prefs.history(100).value.count { it.keyword.startsWith("owned-cas-")||it.keyword.startsWith("legacy-shared-") }==8)
        environment.history.clearAll()
        store.update("settings",mapOf("back_to_top_button_enabled" to JsonPrimitive(false)))
        verify("BackToTop read uses existing global preference owner",!backToTop.backToTopEnabled.first())
        environment.setBackToTopOffset(12f,-25f)
        verify("BackToTop offset writes same canonical keys with owner permit",backToTop.backToTopOffset.first()==(12f to -25f))
        prefs.setPrivacyMode(true);vm.search("incognito");until("incognito actual original result still loads") {!vm.uiState.value.isSearching}
        verify("original privacy suppresses persistence",prefs.history(100).value.none{it.keyword=="incognito"});prefs.setPrivacyMode(false)
        api.slowQuery="stale-result";vm.search("stale-result");delay(30);vm.search("fresh-result");until("new search session renders fresh result") {!vm.uiState.value.isSearching&&vm.uiState.value.searchResults.firstOrNull()?.title?.contains("fresh-result")==true};delay(520)
        verify("original activeSearchSessionId/caller rejects late result",vm.uiState.value.query=="fresh-result"&&vm.uiState.value.searchResults.first().title.contains("fresh-result"))
        blocked.value=listOf(BlockedUp(mid=77,name="Fixture",face=""));until("same blocked-up owner removes current result") { vm.uiState.value.searchResults.isEmpty() };blocked.value=emptyList()
        DesktopOriginalSearchSettings.setSearchHotSectionEnabled(environment,false);verify("original hot setting same global backing",store.preferences("settings")["search_hot_section_enabled"]?.jsonPrimitive?.boolean==false)
        DesktopOriginalSearchSettings.setSearchDiscoverSectionEnabled(environment,false);verify("original discover setting same global backing",store.preferences("settings")["search_discover_section_enabled"]?.jsonPrimitive?.boolean==false)
        DesktopOriginalSearchSettings.setGridColumnCount(environment,4);verify("grid write uses existing global canonical preference",store.preferences("settings")["grid_column_count"]?.jsonPrimitive?.int==4)
        DesktopOriginalSearchSettings.setSearchHotSectionEnabled(environment,true);DesktopOriginalSearchSettings.setSearchDiscoverSectionEnabled(environment,true)
        val trendingEnv=env();val trending=SearchTrendingViewModel(trendingEnv)
        until("full original Trending VM initial result") {!trending.uiState.value.isLoading&&trending.uiState.value.pinnedCount==1}
        api.holdNextTrending=true;trending.refresh()
        until("older original Trending refresh reached delayed transport") { api.heldTrendingSerial>0 }
        val held=api.heldTrendingSerial;trending.refresh()
        until("new original Trending refresh publishes final ranking") { api.trendingIndex>held&&!trending.uiState.value.isRefreshing&&trending.uiState.value.items.any{it.keyword=="Fresh hot ${api.trendingIndex}"} }
        val newest=api.trendingIndex;delay(500)
        verify("cancelled noncooperative original Trending result cannot publish",trending.uiState.value.items.any{it.keyword=="Fresh hot $newest"}&&!trending.uiState.value.items.any{it.keyword=="Fresh hot $held"})
        trending.close()
        published=false;verify("unpublished Root refuses new request/history admission",runCatching{env()}.exceptionOrNull() is CancellationException);published=true
        // Actual original SearchScreen in a bounded isolated Compose scene, real pointer and
        // AWT->Compose keyboard events. This is deliberately not a mounted product Root.
        vm.exitResultsToLanding();vm.onQueryChange("")
        loader=ImageLoader.Builder(PlatformContext.INSTANCE).components { add(coil3.intercept.Interceptor { chain ->
            SuccessResult(ColorImage(0xffa0b0c0.toInt(),80,80),chain.request,DataSource.MEMORY) }) }.build();SingletonImageLoader.setUnsafe(loader)
        window=Frame();resources=DesktopHomeActualWindowResources(window,{owned},{java.awt.Color.WHITE},java.util.logging.Logger.getLogger("SearchOwnedFixture"))
        scene=ImageComposeScene(width=1100,height=900,coroutineContext=coroutineContext);val ui=SearchScene(scene)
        val navigation=DesktopCommentDialogNavigation();var showTrending by mutableStateOf(false)
        val uiTrendingEnv=env();val uiTrending=SearchTrendingViewModel(uiTrendingEnv)
        scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=AppUiStyle.MATERIAL3,hapticFeedbackEnabled=false)) {
                DesktopDetailWindow(precisePointerConnected=true,hardwareKeyboardConnected=true) {
                    DesktopHomeWindowGlobals(resources,true,{_,_,_->}) {
                        CompositionLocalProvider(LocalDesktopOriginalSearchEnvironment provides environment,
                            LocalDesktopLiquidTabSettings provides liquidTabs,
                            LocalDesktopDynamicTimelinePreferences provides dynamicSettings,
                            LocalDesktopHomeCardPreferences provides homeCards,
                            LocalDesktopDetailForeground provides (scene != null && owned),
                            LocalDesktopOriginalSearchActive provides true,LocalNavigationEventDispatcherOwner provides navigation) {
                            DesktopOriginalSearchBackToTop { if(showTrending) SearchTrendingScreen(onBack={showTrending=false},onKeywordClick={vm.search(it);showTrending=false},viewModel=uiTrending)
                            else SearchScreen(vm,onBack={},onOpenTrending={showTrending=true},onVideoClick={_,_,_->},onWebClick={_,_->},onUpClick={},onBangumiClick={},onLiveClick={_,_,_->},onTopicClick={},onArticleClick={_,_->},onAvatarClick={}) }
                        }
                    }
                }
            }
        }
        ui.wait("full original Search landing/history/hot mounted") { ui.nodes().any{it.config.getOrNull(SemanticsActions.SetText)!=null}&&ui.labels().any{it.contains("历史")||it.contains("热搜")||it.contains("搜索发现")} }
        val field=ui.nodes().first{it.config.getOrNull(SemanticsActions.SetText)!=null};ui.press(field)
        for(ch in "keyboard")ui.key(java.awt.event.KeyEvent.KEY_TYPED,java.awt.event.KeyEvent.VK_UNDEFINED,ch)
        ui.wait("actual desktop keyboard edits original search query") { vm.uiState.value.query=="keyboard" }
        ui.key(java.awt.event.KeyEvent.KEY_PRESSED,java.awt.event.KeyEvent.VK_ENTER,'\n');ui.key(java.awt.event.KeyEvent.KEY_RELEASED,java.awt.event.KeyEvent.VK_ENTER,'\n')
        ui.wait("actual desktop Enter submits original KeyboardActions.Search") { vm.uiState.value.showResults&&!vm.uiState.value.isSearching&&vm.uiState.value.query=="keyboard" }
        ui.png(output.resolve("original-search-keyboard-results.png"))
        val up=ui.nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any{it.text.startsWith("UP主")}==true }
        ui.press(up);ui.wait("actual pointer switches original UP tab") { vm.uiState.value.searchType==SearchType.UP&&!vm.uiState.value.isSearching }
        ui.png(output.resolve("original-search-up-tab.png"))
        vm.exitResultsToLanding();vm.onQueryChange("");ui.wait("actual Search restores landing after results") {!vm.uiState.value.showResults&&"大家都在搜" in ui.labels()}
        ui.text("完整榜单");ui.wait("actual pointer opens full original Trending page") { showTrending&&"bilibili 热搜" in ui.labels() }
        ui.png(output.resolve("original-search-trending.png"));ui.text("Pinned hot");ui.wait("actual pointer chooses Trending keyword into original Search") {!showTrending&&vm.uiState.value.query=="Pinned hot"&&vm.uiState.value.showResults}
        verify("actual scene exercised pointer and desktop keyboard",ui.pointerEvents>=8&&ui.keyboardEvents>=10)
        uiTrending.close();navigation.close();scene.close();scene=null
        owned=false;val before=vm.uiState.value;verify("retired environment rejects reducer admission",!environment.commit { error("must not execute") });delay(30)
        verify("retired route preserves previous visible original state",vm.uiState.value==before)
        verify("old epoch history cannot mutate sole DAO",runCatching{environment.history.clearAll()}.exceptionOrNull() is CancellationException)
        owned=true;prefs.freezeWritesForRestore();verify("restore freeze rejects writes on existing history backing",runCatching{environment.history.insert(SearchHistory("restore-must-not-persist"))}.isFailure)
        verify("restore rejected entry not persisted",prefs.history(100).value.none{it.keyword=="restore-must-not-persist"})
        Files.writeString(output.resolve("result.json"),buildJsonObject {put("passed",true);put("assertions",asserts.size);put("checks",JsonArray(asserts.map(::JsonPrimitive)));put("originalSearchTabs",9);put("actualComposePointer",true);put("actualAWTComposeKeyboard",true);put("originalRepositoryAndModels",true);put("isolatedOwnedStore",true);put("businessNetwork",false);put("userAccount",false);put("RootMounted",false);put("nativePlayer",false)}.toString())
        println("Original Search CPU/UI PASS ${asserts.size} assertions; real pointer/key; RootMounted=false")
    } finally {
        scene?.close();resources?.close();window?.dispose();loader?.shutdown();environment.close();job.cancelAndJoin();fixtureJob.cancelAndJoin()
    }
}
