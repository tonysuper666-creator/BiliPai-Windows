@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.refresh.WatchLaterRefreshBus
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.feature.watchlater.*
import com.android.purebilibili.navigation3.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

private var assertions=0
private fun verify(v:Boolean,why:String) { check(v){why};assertions++;println("PASS $why") }
private fun swing(block:()->Unit) { if(SwingUtilities.isEventDispatchThread())block() else SwingUtilities.invokeAndWait(block) }
private suspend fun until(test:()->Boolean)=withTimeout(8_000) { while(!withContext(Dispatchers.Swing){test()})delay(5) }
private fun row(aid:Int,cid:Int=aid+1000,title:String="原稍后 $aid",progress:Int=15,extra:String="")=
    """{"aid":$aid,"bvid":"BV-watch-$aid","cid":$cid,"title":"$title","pic":"","duration":100,"progress":$progress,"owner":{"mid":700,"name":"原UP","face":""},"stat":{"view":12,"danmaku":3}$extra}"""
private fun page(rows:List<String>,count:Int)= """{"code":0,"data":{"count":$count,"list":[${rows.joinToString(",")}]}}"""

fun main(args:Array<String>):Unit=runBlocking {
 val folder=Path.of(args[0]);Files.createDirectories(folder)
 val session=DesktopSessionStore(folder.resolve("no-account-file.json"),persistent=false)
 session.saveAccount(mapOf("SESSDATA" to "synthetic-only","bili_jct" to "synthetic-csrf"),AccountSummary(501,"Fixture",""))
 val repository=DesktopRepository(session)
 val requests=CopyOnWriteArrayList<Request>();val deletes=AtomicInteger();val delayed=CountDownLatch(1);val release=CountDownLatch(1)
 val terminal=repository.httpClient.newBuilder().addInterceptor {chain->
  val req=chain.request();requests+=req
  val body=when(req.url.encodedPath) {
   "/x/web-interface/nav"->"""{"code":0,"data":{"isLogin":true,"mid":501,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/bfs/wbi/fedcba9876543210fedcba9876543210.png"}}}"""
   "/x/v2/history/toview/web"->{
    if(req.url.queryParameter("key")=="延迟") {delayed.countDown();check(release.await(8,TimeUnit.SECONDS))}
    when {
     req.url.queryParameter("key")=="延迟"->page(listOf(row(999,title="旧账号迟到")),1)
     req.url.queryParameter("viewed")=="2"->page(listOf(row(200,1200,"原未看完",33,",\"charging_pay\":{\"level\":1},\"rights\":{\"is_cooperation\":1},\"is_pugv\":true")),1)
     req.url.queryParameter("pn")=="2"->page(listOf(row(20),row(21),row(22)),22)
     else->page((1..20).map(::row),22)
    }
   }
   "/x/v2/history/toview/del"->if(deletes.incrementAndGet()==1)"""{"code":-412,"message":"操作频繁"}""" else """{"code":0}"""
   "/x/v2/history/toview/clear","/x/v2/history/toview/copy","/x/v2/history/toview/move"->"""{"code":0}"""
   else->error("Unexpected terminal original API ${req.url.encodedPath}")
  }
  Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
 }.build()
 repository.javaClass.getDeclaredField("client").apply{isAccessible=true}.set(repository,terminal)
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing)
 val captured=checkNotNull(session.dynamicCacheOwner())
 val gate=DesktopHomeRetainedGate(session,captured,{repository.sessionEpoch},{repository.account.value?.mid},{scope.isActive},scope)
 val global=DesktopPluginStore(folder.resolve("global"));val library=DesktopLibrary(folder.resolve("library")){false}
 val feedback=CopyOnWriteArrayList<String>();val root=DesktopPersonalListsRoot(gate,repository,global,library,{false},feedback::add,HazeState())
 lateinit var entry:DesktopWatchLaterEntry
 swing {entry=root.watchLater(BiliPaiNavKey.WatchLater);entry.viewModel.loadData()}
 try {
  until{!entry.viewModel.uiState.value.isLoading && entry.viewModel.uiState.value.items.size==20}
  val first=requests.last{it.url.encodedPath=="/x/v2/history/toview/web"}
  verify(first.url.queryParameter("pn")=="1"&&first.url.queryParameter("ps")=="20"&&first.url.queryParameter("viewed")=="0"&&first.url.queryParameter("key")==""&&first.url.queryParameter("asc")=="false"&&first.url.queryParameter("need_split")=="true"&&first.url.queryParameter("web_location")=="333.881"&&first.url.queryParameter("w_rid")?.length==32,"whole original signed paged protocol consumes exact pn/ps/viewed/key/asc/split/location and WBI")
  swing {entry.viewModel.loadMore()};until{!entry.viewModel.uiState.value.isLoadingMore&&entry.viewModel.uiState.value.items.size==22}
  verify(entry.viewModel.uiState.value.items.count{it.aid==20L}==1&&entry.viewModel.uiState.value.page==2&&!entry.viewModel.uiState.value.hasMore,"whole original continuation appends and deduplicates by real aid")
  val navBefore=requests.count{it.url.encodedPath=="/x/web-interface/nav"}
  repository.homeWbiKeys(gate.epoch,entry::owns,entry.environment.api,true).getOrThrow()
  verify(requests.count{it.url.encodedPath=="/x/web-interface/nav"}==navBefore+1,"required force refresh uses same existing WBI cache and owned nav graph")
  val prefs=DesktopOriginalHomePreferences.create(global,scope,false,{false})
  withContext(Dispatchers.Swing){render(folder,root,entry,prefs,requests,DesktopDynamicTimelinePreferences(DesktopPluginContext(global)))}
  val selected=entry.viewModel.uiState.value.items.single()
  verify(selected.cid==1200L&&selected.progress==33&&selected.contentType=="充电 · 合作 · 课程","raw original DTO mapping preserves CID/progress and every charging/cooperation/course badge")
  var queueTarget:Pair<Long,Long>?=null
  val queueBinding=DesktopWatchLaterBindings(prefs.homeSettings,prefs.navigation,false,{items,index,_,position ->
      queueTarget=items[index].cid to position;null})
  val originalPlaylist=checkNotNull(buildExternalPlaylistFromWatchLater(entry.viewModel.uiState.value.items,selected.bvid))
  queueBinding.openQueue(originalPlaylist.playlistItems,originalPlaylist.startIndex,false,entry.viewModel.uiState.value.items)
  verify(queueTarget==1200L to 33000L,"actual Watch queue adapter consumes the original separate CID and millisecond target before reveal")
  val before=requests.count{it.url.encodedPath=="/x/v2/history/toview/web"}
  WatchLaterRefreshBus.notifyChanged();until{requests.count{it.url.encodedPath=="/x/v2/history/toview/web"}>before&&!entry.viewModel.uiState.value.isLoading}
  verify(root.watchLater(BiliPaiNavKey.WatchLater)===entry,"sole original refresh bus reaches retained same entry while video can cover it")
  entry.environment.watchLater.copyOrMoveToFavorite(900,linkedSetOf(200,201),true).getOrThrow()
  entry.environment.watchLater.copyOrMoveToFavorite(901,linkedSetOf(202),false).getOrThrow()
  val copy=requests.last{it.url.encodedPath.endsWith("/copy")}.body as FormBody
  val move=requests.last{it.url.encodedPath.endsWith("/move")}.body as FormBody
  fun fields(f:FormBody)=(0 until f.size).associate{f.name(it) to f.value(it)}
  verify(fields(copy)==mapOf("tar_media_id" to "900","mid" to "501","resources" to "200,201","platform" to "web","csrf" to "synthetic-csrf")&&fields(move)==mapOf("tar_media_id" to "901","resources" to "202","platform" to "web","csrf" to "synthetic-csrf"),"whole original copy/move uses distinct exact API fields and same admitted CSRF/MID")
  for(clean in listOf(1,2,null))entry.environment.watchLater.clear(clean).getOrThrow()
  val clears=requests.filter{it.url.encodedPath.endsWith("/clear")}.map{fields(it.body as FormBody)}
  verify(clears.map{it["clean_type"]}==listOf("1","2",null)&&clears.all{it["csrf"]=="synthetic-csrf"},"original clear invalid/viewed/all preserves nullable clean_type semantics")
  swing{entry.viewModel.deleteItem(200)};until{feedback.any{it=="已从稍后再看移除"}}
  verify(deletes.get()==2&&entry.viewModel.uiState.value.items.isEmpty(),"whole original optimistic deletion retries original risk code then commits success")
  lateinit var pending:DesktopWatchLaterEntry
  swing{pending=root.watchLater(BiliPaiNavKey.WatchLaterSearch("延迟"));pending.viewModel.updateQuery("延迟")}
  verify(withContext(Dispatchers.IO){delayed.await(8,TimeUnit.SECONDS)},"original search request actually reached delayed terminal")
  swing{session.saveAccount(mapOf("SESSDATA" to "synthetic-renewed"),AccountSummary(501,"Renewed",""))}
  release.countDown();delay(150)
  verify(!entry.owns()&&!pending.owns()&&pending.viewModel.uiState.value.items.none{it.aid==999L},"same MID credential epoch retires owner and rejects late original rows")
  root.closeAndJoin();verify(!entry.scope.isActive&&!pending.scope.isActive,"root disposal cancels the original VM and background batch scope")
  verify(!Files.exists(folder.resolve("no-account-file.json")),"only actual in-memory SessionStore received synthetic credentials")
  println("ORIGIN originalWatchUI="+Class.forName("com.android.purebilibili.feature.watchlater.WatchLaterScreenKt").protectionDomain.codeSource.location)
  println("ORIGIN existingDTO="+Class.forName("com.android.purebilibili.data.model.response.WatchLaterResponse").protectionDomain.codeSource.location)
  println("RESULT assertions=$assertions groups=1 pointerPairs=1 prepared=true Main=false HTTPsocket=false HWND=false")
 }finally {release.countDown();root.closeAndJoin();gate.closeAndJoin();scope.cancel();terminal.connectionPool.evictAll();terminal.dispatcher.executorService.shutdown()}
}

private suspend fun render(folder:Path,root:DesktopPersonalListsRoot,entry:DesktopWatchLaterEntry,
 prefs:DesktopOriginalHomePreferences,requests:CopyOnWriteArrayList<Request>,timeline:DesktopDynamicTimelinePreferences) {
 val scene=ImageComposeScene(1000,760,coroutineContext=currentCoroutineContext());var ns=0L;var bottom by mutableStateOf(true)
 val navigation=DesktopPersonalListNavigation({error("Route not clicked")},{error("Route not clicked")},{error("Article not clicked")},{error("Video not clicked")})
 fun nodes():List<SemanticsNode> {
  fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
  return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
 }
 fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
 suspend fun frame(){ns+=30_000_000L;scene.render(ns).close();delay(5)}
 suspend fun ready(test:()->Boolean){withTimeout(8_000){while(!test())frame()};repeat(8){frame()}}
 try {
  scene.setContent {
   DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=AppUiStyle.MATERIAL3,hapticFeedbackEnabled=false),systemLanguageTags=listOf("zh-CN")) {
    CompositionLocalProvider(LocalDesktopDynamicTimelinePreferences provides timeline,
        LocalBottomBarVisible provides bottom,LocalBottomBarContentPadding provides 0.dp,LocalSetBottomBarVisible provides {bottom=it}) {
     DesktopDetailWindow {
      val bindings=remember(entry){DesktopWatchLaterBindings(prefs.homeSettings,prefs.navigation,false,{_,_,_,_->error("No queue click in this UI fixture")})}
      DesktopOriginalWatchLaterHost(entry,bindings,navigation,{error("No back click")},{error("No search destination click")},{_,_,_->false},{_,_,_->error("No audio click")},root.watchLaterSearchChannel,root.watchLaterScrollToTopChannel,root.globalHazeState,true)
     }
    }
   }
  }
  ready{"原稍后 1" in labels()&&"未看完" in labels()}
  val point=nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text=="未看完"}==true}.boundsInRoot.center
  scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=ns/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
  scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=ns/1_000_000+55,buttons=PointerButtons())
  ready{"原未看完" in labels()&&entry.viewModel.uiState.value.filter==WatchLaterFilter.UNFINISHED}
  verify(requests.any{it.url.queryParameter("viewed")=="2"}&&"原稍后 1" !in labels(),"actual complete original filter pointer changes original request and visible rows")
  scene.render(ns).use{Files.write(folder.resolve("original-watchlater-filter-material3.png"),checkNotNull(it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)}
 }finally{scene.close()}
}
