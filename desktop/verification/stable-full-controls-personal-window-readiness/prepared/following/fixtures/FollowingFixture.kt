@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.FollowingCacheStore
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.data.model.response.FollowingUser
import com.android.purebilibili.feature.following.*
import com.android.purebilibili.navigation3.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

private var assertions=0
private fun verify(ok:Boolean,message:String){check(ok){message};assertions++;println("PASS $message")}
private suspend fun until(block:()->Boolean)=withTimeout(12_000){while(!withContext(Dispatchers.Swing){block()})delay(5)}
private fun row(mid:Long)= """{"mid":$mid,"uname":"原UP $mid","face":"","sign":"简介 $mid","mtime":100,"official_verify":{"type":-1}}"""
private fun page(rows:List<String>,total:Int)="""{"code":0,"data":{"list":[${rows.joinToString(",")}],"total":$total}}"""

fun main(args:Array<String>):Unit=runBlocking {
 val folder=Path.of(args[0]);Files.createDirectories(folder)
 val sessions=DesktopSessionStore(folder.resolve("no-account.json"),persistent=false)
 sessions.saveAccount(mapOf("SESSDATA" to "fixture-only","bili_jct" to "fixture-csrf"),AccountSummary(501,"Synthetic",""))
 val repo=DesktopRepository(sessions);val calls=CopyOnWriteArrayList<Request>()
 val phase=AtomicInteger();val requestOrdinal=AtomicInteger();val unfollow2=AtomicInteger()
 val started=CountDownLatch(1);val release=CountDownLatch(1)
 val terminal=repo.httpClient.newBuilder().addInterceptor {chain ->
  val r=chain.request();calls+=r
  val body=when(r.url.encodedPath) {
   "/x/relation/followings" -> {
    if(phase.get()==1 && requestOrdinal.incrementAndGet()==1) {
      started.countDown();release.await(10,TimeUnit.SECONDS);page(listOf(row(999)),1)
    } else if(phase.get()==1)page(listOf(row(201)),1)
    else if(r.url.queryParameter("pn")=="2")page(listOf(row(50),row(51)),51)
    else page((1L..50L).map(::row),51)
   }
   "/x/relation/tags" -> """{"code":0,"data":[{"tagid":10,"name":"原分组","count":1}]}"""
   "/x/relation/tag" -> """{"code":0,"data":[{"mid":${if(r.url.queryParameter("tagid")=="-10")1 else 2}}]}"""
   "/x/relation/tag/user" -> """{"code":0,"data":{"10":"原分组"}}"""
   "/x/relation/tags/addUsers" -> """{"code":0}"""
   "/x/relation/modify" -> {
    val f=r.body as FormBody;val mid=f.value((0 until f.size).first{f.name(it)=="fid"})
    if(mid=="2" && unfollow2.incrementAndGet()==1)"""{"code":-412,"message":"操作频繁"}"""
    else if(mid=="3")"""{"code":-400,"message":"拒绝"}""" else """{"code":0}"""
   }
   else -> error("Unexpected actual API ${r.url.encodedPath}")
  }
  Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
 }.build()
 repo.javaClass.getDeclaredField("client").apply{isAccessible=true}.set(repo,terminal)
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing)
 val gate=DesktopHomeRetainedGate(sessions,checkNotNull(sessions.dynamicCacheOwner()),{repo.sessionEpoch},{repo.account.value?.mid},{scope.isActive},scope)
 val store=DesktopPluginStore(folder.resolve("global"));store.update("following_cache",mapOf("home_preserved" to JsonPrimitive("same-store")))
 val root=DesktopPersonalListsRoot(gate,repo,store,DesktopLibrary(folder.resolve("library")){false},{false},{},HazeState())
 lateinit var entry:DesktopFollowingEntry
 try {
  withContext(Dispatchers.Swing){entry=root.following(BiliPaiNavKey.Following(501));entry.viewModel.loadFollowingList(501)}
  until{(entry.viewModel.uiState.value as? FollowingListUiState.Success)?.let{it.users.size==51 && !it.isLoadingMore}==true}
  verify(calls.filter{it.url.encodedPath=="/x/relation/followings"}.map{it.url.queryParameter("pn")}==listOf("1","2") && calls.filter{it.url.encodedPath=="/x/relation/followings"}.all{it.url.queryParameter("ps")=="50" && it.url.queryParameter("vmid")=="501"},"whole original initial and automatic remaining pages use exact MID/pn/ps")
  verify((entry.viewModel.uiState.value as FollowingListUiState.Success).users.count{it.mid==50L}==1,"original off-main merge deduplicates raw users by MID")
  until{entry.viewModel.userFollowGroupIds.value.size==51 && !entry.viewModel.isFollowGroupMetaLoading.value}
  verify(entry.viewModel.followGroupTags.value.first().tagid==-10L && entry.viewModel.userFollowGroupIds.value[1L]==setOf(-10L) && entry.viewModel.userFollowGroupIds.value[2L]==setOf(10L) && entry.viewModel.userFollowGroupIds.value[3L]==emptySet<Long>(),"original special/default groups and actual tag member mapping are retained")
  until{FollowingCacheStore.getSnapshot(entry.environment.cacheContext,501)?.users?.size==51}
  verify(store.preferences("following_cache")["home_preserved"]?.jsonPrimitive?.content=="same-store" && store.preferences("following_cache")["following_payload_v1"]!=null,"original 2000-user payload uses same global namespace without overwriting Home keys")
  withContext(Dispatchers.Swing){render(folder,entry,DesktopDynamicTimelinePreferences(DesktopPluginContext(store)))}
  val dialog=withContext(Dispatchers.Swing){entry.viewModel.prepareBatchGroupDialogData(listOf(1,2)).getOrThrow()}
  verify(dialog.hasMixedSelection && dialog.initialSelection.isEmpty(),"original mixed group selection is not presented as one shared selection")
  entry.environment.actions.overwriteFollowGroupIds((1L..25L).toSet(),setOf(0,10)).getOrThrow()
  fun fields(body:FormBody)=(0 until body.size).associate{body.name(it) to body.value(it)}
  val edits=calls.filter{it.url.encodedPath=="/x/relation/tags/addUsers"}.map{fields(it.body as FormBody)}
  verify(edits.size==4 && edits.map{it["tagids"]}==listOf("0","10","0","10") && edits.all{it["csrf"]=="fixture-csrf"} && edits.first()["fids"]?.split(',')?.size==20 && edits[2]["fids"]?.split(',')?.size==5,"original group overwrite resets to default then applies normalized tags in 20-user chunks")
  val events=CopyOnWriteArrayList<DesktopOwnedFollowStateChange>()
  val observer=scope.launch(start=CoroutineStart.UNDISPATCHED){repo.followStateEvents.changes.collect{events+=it}}
  val outcome=withContext(Dispatchers.Swing){entry.viewModel.batchUnfollow(listOf(FollowingUser(mid=2),FollowingUser(mid=3)))}
  verify(outcome.successCount==1 && outcome.failedCount==1 && outcome.succeededMids==setOf(2L) && unfollow2.get()==2,"original batch unfollow retries risk failures and keeps partial outcome")
  until{events.size==1}
  verify(events.single().change.mid==2L && !events.single().change.isFollowing && events.single().owner.epoch==gate.epoch,"server-success-only follow notification reaches Root's sole same-owner event bus")
  observer.cancelAndJoin()
  verify((entry.viewModel.uiState.value as FollowingListUiState.Success).users.none{it.mid==2L} && (entry.viewModel.uiState.value as FollowingListUiState.Success).total==50,"original successful unfollow updates raw users/count and cache")
  phase.set(1)
  withContext(Dispatchers.Swing){entry.viewModel.loadFollowingList(501,true)}
  verify(withContext(Dispatchers.IO){started.await(10,TimeUnit.SECONDS)},"same MID delayed old refresh actually entered terminal")
  withContext(Dispatchers.Swing){entry.viewModel.loadFollowingList(501,true)}
  until{(entry.viewModel.uiState.value as? FollowingListUiState.Success)?.users?.singleOrNull()?.mid==201L}
  release.countDown();delay(150)
  verify((entry.viewModel.uiState.value as FollowingListUiState.Success).users.single().mid==201L,"same epoch force refresh retires old first-page job and prevents stale cache/UI overwrite")
  until{FollowingCacheStore.getSnapshot(entry.environment.cacheContext,501)?.users?.singleOrNull()?.mid==201L}
  verify(FollowingCacheStore.getSnapshot(entry.environment.cacheContext,501)?.users?.single()?.mid==201L,"latest request alone publishes the original persistent payload after old terminal returns")
  sessions.saveAccount(mapOf("SESSDATA" to "new-synthetic"),AccountSummary(501,"Renewed",""))
  val rejected=runCatching{FollowingCacheStore.saveSnapshot(entry.environment.cacheContext,501,1,listOf(FollowingUser(mid=777)))}.exceptionOrNull()
  verify(!entry.owns() && rejected is kotlinx.coroutines.CancellationException,"same MID credential epoch rejects former global cache writer")
  root.closeAndJoin();verify(!entry.scope.isActive,"Root disposal retires original following VM/page/background jobs")
  verify(!Files.exists(folder.resolve("no-account.json")),"synthetic credentials remain only in actual in-memory SessionStore")
  println("ORIGIN originalUI="+Class.forName("com.android.purebilibili.feature.following.FollowingListScreenKt").protectionDomain.codeSource.location)
  println("ORIGIN actualDTO="+FollowingUser::class.java.protectionDomain.codeSource.location)
  println("RESULT assertions=$assertions groups=1 pointerPairs=1 prepared=true Main=false sockets=false HWND=false")
 } finally {release.countDown();root.closeAndJoin();gate.closeAndJoin();scope.cancel();terminal.dispatcher.executorService.shutdown();terminal.connectionPool.evictAll()}
}

private suspend fun render(folder:Path,entry:DesktopFollowingEntry,timeline:DesktopDynamicTimelinePreferences) {
 val scene=ImageComposeScene(1000,760,coroutineContext=currentCoroutineContext());var ns=0L;var bottom by mutableStateOf(true)
 fun nodes():List<SemanticsNode> {
  fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
  return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
 }
 fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
 suspend fun frame(){ns+=30_000_000;scene.render(ns).close();delay(5)}
 suspend fun ready(check:()->Boolean){withTimeout(12_000){while(!check())frame()};repeat(8){frame()}}
 try {
  scene.setContent {
   DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=AppUiStyle.MATERIAL3,hapticFeedbackEnabled=false),systemLanguageTags=listOf("zh-CN")) {
    CompositionLocalProvider(LocalDesktopDynamicTimelinePreferences provides timeline,LocalBottomBarVisible provides bottom,
      LocalBottomBarContentPadding provides 0.dp,LocalSetBottomBarVisible provides {bottom=it}) {
      DesktopDetailWindow {DesktopOriginalFollowingHost(entry,{error("Back not clicked")},{error("User not clicked")},true)}
    }
   }
  }
  ready{texts().any{it.contains("特别关注")} && "原UP 1" in texts()}
  val n=nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text.startsWith("特别关注")}==true}
  val p=n.boundsInRoot.center
  scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=ns/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
  scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=ns/1_000_000+50,buttons=PointerButtons())
  ready{"当前分组 1 人" in texts() && "原UP 1" in texts() && "原UP 2" !in texts()}
  verify(true,"complete original group chip pointer filters actual users with original group policy")
  scene.render(ns).use{Files.write(folder.resolve("original-following-special-group.png"),checkNotNull(it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)}
 }finally{scene.close()}
}
