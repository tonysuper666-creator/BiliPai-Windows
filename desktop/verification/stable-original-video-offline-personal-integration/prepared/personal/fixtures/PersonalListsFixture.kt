@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.LocalBottomBarVisible
import com.android.purebilibili.core.ui.LocalBottomBarContentPadding
import com.android.purebilibili.core.ui.LocalSetBottomBarVisible
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.navigation3.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private var assertions = 0
private fun verify(value: Boolean, why: String) { check(value) { why }; assertions++; println("PASS $why") }
private fun swing(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
private suspend fun until(ready: () -> Boolean) = withTimeout(8_000) { while (!withContext(Dispatchers.Swing) { ready() }) delay(5) }
private val initialHistory = """{"code":0,"data":{"list":[
 {"title":"历史原视频","duration":100,"progress":10,"history":{"oid":100,"bvid":"BV-original","cid":101,"business":"archive"}},
 {"title":"历史原课程","duration":100,"progress":33,"history":{"oid":200,"epid":201,"cid":202,"business":"cheese"}},
 {"title":"历史原直播","history":{"oid":300,"business":"live"}},
 {"title":"历史原专栏","history":{"oid":400,"business":"article"}}
 ],"cursor":{"max":100,"view_at":200,"business":"archive"}}}"""
private val secondHistory = """{"code":0,"data":{"list":[
 {"title":"历史原视频重复","duration":100,"progress":10,"history":{"oid":100,"bvid":"BV-original","cid":101,"business":"archive"}},
 {"title":"历史续页","duration":100,"progress":12,"history":{"oid":101,"bvid":"BV-next","cid":102,"business":"archive"}}
 ],"cursor":{"max":0,"view_at":0,"business":""}}}"""

fun main(args: Array<String>): Unit = runBlocking {
    val folder = Path.of(args[0]); Files.createDirectories(folder)
    val session = DesktopSessionStore(folder.resolve("not-created-session.json"), persistent = false)
    session.saveAccount(mapOf("SESSDATA" to "synthetic-only", "bili_jct" to "synthetic-only"), AccountSummary(501,"Fixture",""))
    val repository = DesktopRepository(session)
    val calls = CopyOnWriteArrayList<Request>()
    val original = repository.httpClient
    val delayed = CountDownLatch(1); val release = CountDownLatch(1)
    val terminal = original.newBuilder().addInterceptor { chain ->
        val request = chain.request(); calls += request
        val path = request.url.encodedPath
        val body = when(path) {
            "/x/v2/history/shadow" -> """{"code":0,"data":false}"""
            "/x/web-interface/history/cursor" -> if ((request.url.queryParameter("max") ?: "0") == "0") initialHistory else secondHistory
            "/x/web-interface/history/search" -> {
                if(request.url.queryParameter("keyword") == "延迟") { delayed.countDown(); check(release.await(8,TimeUnit.SECONDS)) }
                """{"code":0,"data":{"list":[{"title":"原搜索结果","history":{"oid":500,"bvid":"BV-search","cid":501,"business":"archive"}}]}}"""
            }
            "/x/v2/space/likearc", "/x/v2/space/coinarc" -> """{"code":0,"data":{"count":2,"item":[{"aid":600,"bvid":"BV-liked","first_cid":601,"title":"其他UP原点赞","author":"UP","duration":100},{"aid":602,"bvid":"BV-liked2","first_cid":603,"title":"其他UP原点赞2","author":"UP","duration":100}]}}"""
            "/x/v2/history/shadow/set", "/x/v2/history/delete", "/x/v2/history/clear" -> """{"code":0}"""
            "/read/cv400" -> ""
            else -> error("Unexpected original API request: $path")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
            .body(body.toResponseBody()).build()
    }.build()
    repository.javaClass.getDeclaredField("client").apply { isAccessible=true }.set(repository, terminal)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val alive = AtomicBoolean(true)
    val captured = checkNotNull(session.dynamicCacheOwner())
    val gate = DesktopHomeRetainedGate(session,captured,{repository.sessionEpoch},{repository.account.value?.mid},alive::get,appScope)
    val global = DesktopPluginStore(folder.resolve("global"))
    val library = DesktopLibrary(folder.resolve("library")) { false }
    library.record(VideoCard("BV-original","cached","","",0,100,progressSeconds=45,preferredCid=101))
    lateinit var root: DesktopPersonalListsRoot
    lateinit var history: DesktopPersonalListEntry
    lateinit var liked: DesktopPersonalListEntry
    val feedback = mutableListOf<String>()
    swing {
        root=DesktopPersonalListsRoot(gate,repository,global,library,{false},{feedback+=it},HazeState())
        history=root.history(BiliPaiNavKey.History)
        (history.viewModel as HistoryViewModel).loadData()
    }
    try {
        until { !history.viewModel.uiState.value.isLoading && history.viewModel.uiState.value.items.size == 4 }
        val vm=history.viewModel as HistoryViewModel
        swing {
            verify(root.history(BiliPaiNavKey.History) === history,"same retained original History VM while a video covers it")
            verify(vm.uiState.value.items.first().progress==45,"original display resolver receives exact same Library BVID/CID cached milliseconds")
            vm.loadMore()
        }
        until { vm.uiState.value.items.size==5 && !vm.isLoadingMoreState.value }
        verify(calls.any { it.url.queryParameter("max")=="100" && it.url.queryParameter("view_at")=="200" && it.url.queryParameter("business")=="archive" },"full original cursor pagination fields preserved")
        verify(vm.uiState.value.items.count { it.bvid=="BV-original" }==1,"full original history render-key append deduplication")
        val routes=mutableListOf<BiliPaiNavKey>();val videos=mutableListOf<BiliPaiNavKey.VideoDetail>()
        val article=DesktopPersonalArticleResolver(repository.ownedHomeCallFactory(gate.epoch,history::owns),history::owns)
        val navigation=DesktopPersonalListNavigation({routes+=it},{routes+=legacyRouteToBiliPaiNavKey(it)},article::resolve,{videos+=it})
        val callback=desktopOriginalHistoryVideoClick(vm,history.scope,history::owns,navigation)
        swing {
            callback(vm.resolveHistoryLookupKey(vm.uiState.value.items.first()),0,"cover",true)
            callback(vm.resolveHistoryLookupKey(vm.uiState.value.items.first { it.title=="历史原课程" }),0,"",false)
            callback(vm.resolveHistoryLookupKey(vm.uiState.value.items.first { it.title=="历史原直播" }),0,"",false)
            callback(vm.resolveHistoryLookupKey(vm.uiState.value.items.first { it.title=="历史原专栏" }),0,"",false)
        }
        until { routes.any { it is BiliPaiNavKey.ArticleDetail } }
        verify(videos.single().cid==101L && videos.single().resumePositionMs==45_000L && videos.single().initialVertical && videos.single().sourceRoute=="history","entire original navigation callback keeps CID/resume/vertical/source")
        verify(routes.filterIsInstance<BiliPaiNavKey.BangumiPlayer>().single().let { it.isCourse && it.seasonId==200L && it.epId==201L && it.resumePositionMs==33_000L },"original course route preserves season/episode/resume and course flag")
        verify(routes.filterIsInstance<BiliPaiNavKey.Live>().single().roomId=="300" && routes.filterIsInstance<BiliPaiNavKey.ArticleDetail>().single().articleId==400L,"original business-aware live/article navigation is not flattened to video")
        val count=calls.count { it.url.encodedPath=="/x/web-interface/history/cursor" }
        HistoryRefreshBus.notifyChanged()
        until { calls.count { it.url.encodedPath=="/x/web-interface/history/cursor" }>count && !vm.uiState.value.isLoading }
        verify(true,"actual sole HistoryRefreshBus reaches retained original History observer")

        val likedKey=BiliPaiNavKey.LikedVideos(777,"别的UP",false)
        val coinKey=BiliPaiNavKey.LikedVideos(888,"另一个UP",true)
        swing { liked=root.liked(likedKey);root.liked(coinKey) }
        until { liked.viewModel.uiState.value.items.size==2 && !liked.viewModel.uiState.value.isLoading }
        until { calls.any { it.url.encodedPath=="/x/v2/space/coinarc" } }
        verify(liked.viewModel.uiState.value.title=="别的UP 的点赞" && calls.any { it.url.encodedPath=="/x/v2/space/likearc" && it.url.queryParameter("vmid")=="777" },"actual original Like protocol consumes requested foreign MID and owner name")
        verify(calls.any { it.url.encodedPath=="/x/v2/space/coinarc" && it.url.queryParameter("vmid")=="888" },"actual original Coin archive uses distinct original API with target MID")
        withContext(Dispatchers.Swing) { renderOriginalListUi(folder,root,history,liked,navigation,calls) }
        swing {
            root.prune(listOf(BiliPaiNavKey.MainHost,likedKey,BiliPaiNavKey.VideoDetail("BV-liked")))
            verify(liked.owns() && root.history(BiliPaiNavKey.History)===history,"actual stack keeps covered Like and persistent MainHost History owners")
            root.prune(listOf(BiliPaiNavKey.MainHost))
            verify(!liked.owns() && history.owns(),"pop retires original Like entry but not retained History destination")
        }
        verify(!Files.exists(folder.resolve("not-created-session.json")),"synthetic account lives in actual in-memory SessionStore only")
        println("GROUP original retained protocols/navigation PASS")

        lateinit var pending:DesktopPersonalListEntry
        swing { pending=root.history(BiliPaiNavKey.HistorySearch("延迟"));(pending.viewModel as HistoryViewModel).searchHistory("延迟") }
        verify(withContext(Dispatchers.IO){delayed.await(8,TimeUnit.SECONDS)},"delayed actual original search reached terminal application interceptor")
        swing { session.saveAccount(mapOf("SESSDATA" to "synthetic-new"),AccountSummary(501,"Fixture renewed","")) }
        verify(!root.owns() && !pending.owns(),"same MID credential epoch change retires the original list and queued search")
        release.countDown();delay(100)
        verify(pending.viewModel.uiState.value.items.none { it.bvid=="BV-search" },"late response cannot publish old account search rows")
        println("GROUP same-MID retirement PASS")
        println("ORIGIN originalHistory="+vm.javaClass.protectionDomain.codeSource.location)
        println("ORIGIN originalCommonList="+Class.forName("com.android.purebilibili.feature.list.CommonListScreenKt").protectionDomain.codeSource.location)
        println("RESULT assertions=$assertions preparedThinAdapters=true originalClassOverrides=0 actualRootUI=false HTTPsocket=false")
        Files.writeString(folder.resolve("result.json"),"""{"passed":true,"assertions":$assertions,"groups":2,"preparedAdapters":true,"originalClassOverrides":0,"accountDisk":false,"HTTPsocket":false,"RootMounted":false}""")
    } finally {
        release.countDown();root.closeAndJoin();gate.closeAndJoin();appScope.cancel()
        terminal.connectionPool.evictAll();terminal.dispatcher.executorService.shutdown()
    }
}

/** Only the new page seam is prospective; theme/CommonList/cards/VM/protocol classes
 * come from actual48. Request terminal and route callbacks are explicit fixture inputs. */
private suspend fun renderOriginalListUi(folder:Path,root:DesktopPersonalListsRoot,history:DesktopPersonalListEntry,
    liked:DesktopPersonalListEntry,navigation:DesktopPersonalListNavigation,calls:CopyOnWriteArrayList<Request>) {
    val scene=ImageComposeScene(width=1000,height=760,coroutineContext=currentCoroutineContext())
    var entry by mutableStateOf(history)
    var back=0
    var bottomVisible by mutableStateOf(true)
    var nanos=0L
    fun nodes():List<SemanticsNode> {
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    fun labels()=nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    suspend fun frame() { nanos+=30_000_000L;scene.render(nanos).close();delay(5) }
    suspend fun wait(label:String,ready:()->Boolean) { try { withTimeout(7_000) { while(!ready()) frame() };repeat(12){frame()} }
        catch(f:TimeoutCancellationException) { error("$label actualLabels=${labels()}") } }
    suspend fun click(label:String) {
        val point=nodes().last { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text==label }==true }.boundsInRoot.center
        check(point.x in 0f..1000f && point.y in 0f..760f)
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000L,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000L+55L,buttons=PointerButtons())
        repeat(18){frame()}
    }
    fun capture(name:String) { scene.render(nanos).use { image->
        Files.write(folder.resolve(name),checkNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)
    } }
    try {
        scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=AppUiStyle.MATERIAL3,hapticFeedbackEnabled=false),systemLanguageTags=listOf("zh-CN")) {
                CompositionLocalProvider(LocalBottomBarVisible provides bottomVisible,
                    LocalBottomBarContentPadding provides 0.dp, LocalSetBottomBarVisible provides { bottomVisible=it }) {
                DesktopDetailWindow {
                    key(entry) {
                        val p=root.preferences
                        val bindings=remember(entry) { DesktopFavoriteBindings(p.showOnlineCount,p.homeSettings,p.initialHomeSettings(),
                            p.navigationSettings,p.initialNavigationSettings(),entry.categories,
                            { _,_,_-> error("Queue is outside this UI fixture") }, { _,_-> error("Queue append is outside this UI fixture") },
                            { _,_,_-> error("Share is outside this UI fixture") },p.homeFeedCardStyle,false,
                            p.backToTopEnabled,p.initialBackToTopEnabled(),p.backToTopOffset,p.initialBackToTopOffset(),p::setBackToTopOffset,p::updateBackToTopOffset) }
                        DesktopOriginalPersonalListHost(entry,bindings,navigation,{back++},{navigation.push(BiliPaiNavKey.Space(it))},
                            {navigation.push(BiliPaiNavKey.HistorySearch(it))},{_,_,_->false},{_,_->error("Audio is outside this fixture")},
                            root.historySearchChannel,root.historyScrollToTopChannel,root.globalHazeState,true)
                    }
                }
            }
            }
        }
        wait("actual original History card") { "历史原专栏" in labels() }
        capture("original-history-material3.png")
        verify(listOf("历史记录","历史原视频","历史原专栏").all { it in labels() },"actual installed full original History title/cards mounted at finite Windows viewport")
        val before=calls.count { it.url.queryParameter("type")=="article" }
        click("专栏")
        wait("original filter consumer API") { calls.count { it.url.queryParameter("type")=="article" }>before && "历史原专栏" in labels() }
        verify("历史原视频" !in labels(),"actual original filter pointer changes original API type and visible card consumer")
        entry=liked
        wait("actual original foreign Like") { "别的UP 的点赞" in labels() && "其他UP原点赞" in labels() }
        capture("original-liked-material3.png")
        click("其他UP原点赞")
        verify(back==0,"actual original Like row pointer does not accidentally invoke back")
        println("GROUP original CommonList History/Like offscreen pointer PASS; HWND=false RootMounted=false")
    } finally { scene.close() }
}
