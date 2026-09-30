@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.productcacheproof

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

private class Ui(val width:Int,val height:Int,context:kotlin.coroutines.CoroutineContext) {
    private val job=SupervisorJob();val errors=mutableListOf<Throwable>()
    private val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,e->errors+=e})
    val scene=ImageComposeScene(width,height,coroutineContext=scope.coroutineContext)
    var nanos=0L;val pointers=mutableListOf<JsonObject>()
    fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
    fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();yield();delay(5);check(errors.isEmpty()){errors.toString()}}
    suspend fun await(label:String,ready:()->Boolean){try{withTimeout(7000){while(!ready())frame()};repeat(8){frame()}}
        catch(e:TimeoutCancellationException){error("$label: ${texts()}")}}
    fun matching(label:String)=nodes().filter{n->n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==label}==true||
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true}
    suspend fun click(label:String){
        await("visible $label"){matching(label).any{it.boundsInRoot.width>0&&it.boundsInRoot.height>0&&it.boundsInRoot.top>=0&&it.boundsInRoot.bottom<=height}}
        val visible=matching(label).filter{it.boundsInRoot.width>0&&it.boundsInRoot.height>0&&it.boundsInRoot.top>=0&&it.boundsInRoot.bottom<=height}
        val node=if(label=="全部")visible.minBy{it.boundsInRoot.top}else visible.last()
        val p=node.boundsInRoot.center
        pointers+=buildJsonObject{put("label",label);put("x",p.x);put("y",p.y)}
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+60,buttons=PointerButtons());repeat(12){frame()}
    }
    fun save(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
    fun semantics(path:Path){Files.writeString(path,nodes().joinToString("\n"){"${it.boundsInRoot} ${it.config}"})}
    suspend fun close(){scene.close();job.cancelAndJoin()}
}

@Suppress("UNCHECKED_CAST") private fun actualTimeline(memory:DesktopBrowseMemory,epoch:Long):DesktopDynamicTimelineState? {
    val screens=memory.javaClass.getDeclaredField("screens").apply{isAccessible=true}.get(memory) as Map<Any?,Any>
    return screens.entries.firstOrNull{it.key==listOf("dynamic-settings-timeline",42L,epoch,"all",0)}?.value as? DesktopDynamicTimelineState
}
private fun bindTaskTransport(repository:DesktopRepository,client:OkHttpClient,sessions:DesktopSessionStore){
    for((name,value)in listOf("client" to client,"visitorInitialized" to true,"visitorGeneration" to sessions.generation))
        repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
}

private suspend fun case(output:Path,style:AppUiStyle,mode:AppThemeMode,context:kotlin.coroutines.CoroutineContext):JsonObject {
    val name=style.name.lowercase()+"-"+mode.name.lowercase();val dir=output.resolve(name);Files.createDirectories(dir)
    val root=output.parent.resolve("cold-roots").resolve(name)
    val sessions=DesktopSessionStore(root.resolve("task-session.json"));check(sessions.account.value?.mid==42L)
    val store=DesktopPluginStore(root.resolve("actual-global"));val cache=DesktopDynamicCache(sessions,store)
    val prefs=DesktopDynamicTimelinePreferences(DesktopPluginContext(store));prefs.setIncrementalRefresh(true)
    prefs.setLayoutMode(if(mode==AppThemeMode.DARK)DynamicFeedLayoutMode.LIST else DynamicFeedLayoutMode.WATERFALL)
    check(prefs.incrementalRefresh.first())
    val repository=DesktopRepository(sessions);check(repository.httpClient.cookieJar===sessions)
    val memory=DesktopBrowseMemory();val requestRecords=java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    val coldPresentAtRequestStart=AtomicBoolean(false)
    val feeds=AtomicInteger();val spaces=AtomicInteger();val initialEntered=CountDownLatch(1);val initialRelease=CountDownLatch(1)
    val rotatedEntered=CountDownLatch(1);val rotatedRelease=CountDownLatch(1)
    val client=repository.httpClient.newBuilder().addInterceptor{chain->
        val request=chain.request();val path=request.url.encodedPath
        requestRecords+=buildJsonObject{put("method",request.method);put("host",request.url.host);put("path",path)
            put("query",buildJsonObject{request.url.queryParameterNames.forEach{put(it,request.url.queryParameter(it).orEmpty())}})}
        val body=when(path){
            "/x/polymer/web-dynamic/v1/feed/all" -> when(feeds.incrementAndGet()){
                1 -> {val atStart=actualTimeline(memory,0L)
                    coldPresentAtRequestStart.set(atStart?.page?.isCachePlaceholder==true&&atStart.page.items.map{it.id_str}==listOf("2","1"))
                    initialEntered.countDown();check(initialRelease.await(10,TimeUnit.SECONDS))
                    Json.encodeToString(DynamicFeedResponse(data=DynamicFeedData(listOf(fixtureDynamic("3","Fresh-3"),fixtureDynamic("2","Fresh-2")),"",false,"3")))}
                2 -> """{"code":-400,"message":"fixture-refresh-failed"}"""
                3 -> Json.encodeToString(DynamicFeedResponse(data=DynamicFeedData(listOf(fixtureDynamic("4","Retry-4"),fixtureDynamic("3","Fresh-3")),"",false,"4",1)))
                4 -> {rotatedEntered.countDown();check(rotatedRelease.await(10,TimeUnit.SECONDS))
                    Json.encodeToString(DynamicFeedResponse(data=DynamicFeedData(listOf(fixtureDynamic("5","Rotated-5")),"",false,"5")))}
                else -> error("Unexpected repeated feed; no external socket is permitted")
            }
            "/x/polymer/web-dynamic/v1/feed/space" -> {spaces.incrementAndGet()
                Json.encodeToString(DynamicFeedResponse(data=DynamicFeedData(listOf(fixtureDynamic("3","Fresh-3"),fixtureDynamic("2","Fresh-2")),"",false,"3")))}
            "/x/relation/followings" -> """{"code":0,"data":{"list":[{"mid":77,"uname":"User-77","face":""}],"total":1}}"""
            "/xlive/web-ucenter/user/following" -> """{"code":0,"data":{"list":[]}}"""
            "/dynamic_svr/v1/dynamic_svr/w_dyn_uplist" -> """{"code":0,"data":{"items":[]}}"""
            else -> error("Unexpected task transport path: $path; chain.proceed is never called")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("task fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    bindTaskTransport(repository,client,sessions)
    val community=DesktopCommunityRepository(repository,DesktopBlockedUpStore(DesktopPluginContext(store)))
    val navigation=CommunityNavigation({}, {}, {}, {}, {}, {}, {})
    val ui=Ui(1100,1000,context);var guest by mutableStateOf(false);var markDone by mutableStateOf(false);var guestDone by mutableStateOf(false)
    val actionErrors=mutableListOf<Throwable>()
    try {
        ui.scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=mode,hapticFeedbackEnabled=false)) {
                CompositionLocalProvider(LocalDesktopBrowseMemory provides memory,LocalDesktopDynamicCache provides cache,
                    LocalDesktopDynamicTimelinePreferences provides prefs) {
                    AppSurface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize()) {
                        val scope=rememberCoroutineScope()
                        if(!guest) {
                            AppTextButton(onClick={scope.launch{try{checkNotNull(cache.openCurrent()).markNotInterested("2");markDone=true}catch(e:Throwable){actionErrors+=e}}}) {
                                AppText("Fixture: explicit local mark 2")}
                            Box(Modifier.weight(1f)){CommunityDynamicFeed(42L,community,navigation)}
                        } else {
                            DesktopDynamicCacheContent(cache,0L,sessions.generation,{}) {session ->
                                Column(Modifier.fillMaxSize()) {
                                    AppTextButton(onClick={scope.launch{try{session.markNotInterested(" guest-local-hidden ");guestDone=true}catch(e:Throwable){actionErrors+=e}}}){AppText("Fixture: guest local mark")}
                                    CommunityLoginGate(repository,{}) {mid->CommunityDynamicFeed(mid,community,navigation)}
                                }
                            }
                        }
                    }}
                }
            }
        }
        ui.await("original cold cache visible while initial real Repository request held"){
            initialEntered.count==0L&&"Cached-2" in ui.texts()&&"Cached-1" in ui.texts()}
        val cold=checkNotNull(cache.openCurrent());val initialState=checkNotNull(actualTimeline(memory,0L))
        check(initialState.page.isCachePlaceholder&&initialState.page.items.map{it.id_str}==listOf("2","1"))
        check(feeds.get()==1);check(coldPresentAtRequestStart.get())
        check(requestRecords.none{it["path"]!!.jsonPrimitive.content=="/x/relation/followings"})
        ui.save(dir.resolve("01-cold-seed-before-primary-response.png"));ui.semantics(dir.resolve("01-cold-semantics.txt"))
        initialRelease.countDown()
        ui.await("first refresh replaces original placeholder even with incremental preference"){
            !initialState.busy&&initialState.initialized&&"Fresh-3" in ui.texts()&&"Fresh-2" in ui.texts()&&"Cached-1" !in ui.texts()}
        check(!initialState.page.isCachePlaceholder);check(initialState.page.items.map{it.id_str}==listOf("3","2"))
        cache.flush();check(cold.cachedAllItems.value.map{it.id_str}==listOf("3","2"))
        ui.save(dir.resolve("02-first-refresh-replaced-placeholder.png"))
        ui.click("刷新");ui.await("failed refresh retains the usable real raw timeline"){
            initialState.error!=null&&!initialState.busy&&"重试" in ui.texts()}
        check(initialState.page.items.map{it.id_str}==listOf("3","2"));check("Fresh-3" in ui.texts()&&"Fresh-2" in ui.texts())
        repeat(25){ui.frame()};check(feeds.get()==2)
        ui.save(dir.resolve("03-failure-retains-and-offers-explicit-retry.png"));ui.semantics(dir.resolve("03-failure-semantics.txt"))
        ui.click("重试");ui.await("real retry button succeeds"){
            initialState.error==null&&!initialState.busy&&"Retry-4" in ui.texts()}
        check(feeds.get()==3);check(initialState.page.items.map{it.id_str}==listOf("4","3","2"))
        val beforeMark=initialState.page.items
        ui.click("Fixture: explicit local mark 2");ui.await("real actor publishes not-interested and actual ALL transform reacts"){
            markDone&&"Fresh-2" !in ui.texts()&&"Fresh-3" in ui.texts()}
        check(initialState.page.items===beforeMark);cache.flush()
        check(cold.cachedAllItems.value.map{it.id_str}==listOf("4","3","2"));check(cold.notInterestedIds.value==setOf("2"))
        Files.writeString(dir.resolve("raw-after-not-interested.json"),buildJsonObject{
            put("timelineIds",JsonArray(initialState.page.items.map{JsonPrimitive(it.id_str)}))
            put("cacheIds",JsonArray(cold.cachedAllItems.value.map{JsonPrimitive(it.id_str)}))
            put("notInterestedIds",JsonArray(cold.notInterestedIds.value.map(::JsonPrimitive)))
        }.toString())
        ui.save(dir.resolve("04-all-display-filter-raw-retained.png"))
        ui.click("UP");ui.await("actual original UP rail hydrated"){"User-77" in ui.texts()}
        ui.click("User-77");ui.await("actual selected-UP request and same visibility transform"){
            spaces.get()==1&&"Fresh-3" in ui.texts()&&"Fresh-2" !in ui.texts()}
        repeat(16){ui.frame()};check(spaces.get()==1);check(initialState.page.items===beforeMark)
        ui.save(dir.resolve("05-selected-up-display-filter.png"));ui.semantics(dir.resolve("05-selected-up-semantics.txt"))
        ui.click("全部");ui.await("return original ALL tab rather than selected-UP ALL content filter"){
            DesktopDynamicTabsPreferences(prefs.context).selectedTab==0&&"Retry-4" in ui.texts()}
        sessions.saveAccount(mapOf("SESSDATA" to "task-only-cache-owner-b","bili_jct" to "task-only-cache-csrf"),AccountSummary(42,"Fixture", ""))
        bindTaskTransport(repository,client,sessions)
        ui.await("same MID credential rotation starts new unseeded actual page"){
            rotatedEntered.count==0L&&actualTimeline(memory,1L)!=null&&"Retry-4" !in ui.texts()&&"Fresh-3" !in ui.texts()}
        val rotated=checkNotNull(cache.openCurrent());check(rotated.cachedAllItems.value.isEmpty()&&rotated.notInterestedIds.value.isEmpty())
        val rotatedState=checkNotNull(actualTimeline(memory,1L));check(rotatedState.page.items.isEmpty()&&!rotatedState.page.isCachePlaceholder)
        check(runCatching{cold.markNotInterested("late-old-owner")}.isFailure)
        ui.save(dir.resolve("06-same-mid-new-owner-rejects-cold-private-seed.png"))
        rotatedRelease.countDown();ui.await("rotated source still usable"){"Rotated-5" in ui.texts()&&!rotatedState.busy}
        cache.flush();check(rotated.cachedAllItems.value.single().id_str=="5")
        sessions.logout();guest=true;bindTaskTransport(repository,client,sessions)
        val requestCount=requestRecords.size
        ui.await("actual guest gate plus actual local cache binding"){"登录后查看账号内容" in ui.texts()&&"Fixture: guest local mark" in ui.texts()}
        ui.click("Fixture: guest local mark");ui.await("guest local action acknowledged") {guestDone}
        cache.flush();val guestSession=checkNotNull(cache.openCurrent())
        check(guestSession.owner.mid==0L&&guestSession.cachedAllItems.value.isEmpty())
        check(guestSession.notInterestedIds.value==setOf("guest-local-hidden"));check(requestRecords.size==requestCount)
        repeat(30){ui.frame()};check(requestRecords.size==requestCount)
        check(actionErrors.isEmpty());check(feeds.get()==4);check(spaces.get()==1)
        ui.save(dir.resolve("07-guest-local-action-zero-http.png"));ui.semantics(dir.resolve("07-guest-semantics.txt"))
        Files.writeString(dir.resolve("pointers.json"),JsonArray(ui.pointers).toString())
        Files.writeString(dir.resolve("request-trace.json"),JsonArray(requestRecords.toList()).toString())
        return buildJsonObject{put("style",style.name);put("themeMode",mode.name);put("passed",true);put("pointerPairs",ui.pointers.size)
            put("coldSeedSeparateWriterJvm",true);put("actualCommunityReady",true);put("coldSeedBeforePrimaryResponse",true);put("actualPlaceholderObservedAtPrimaryRequestStart",coldPresentAtRequestStart.get())
            put("originalStartupBarrierNoFollowingBeforePrimary",true);put("incrementalSettingEnabled",true)
            put("placeholderReplaced",true);put("failedRefreshPreserves",true);put("explicitRetry",true)
            put("notInterestedAllAndSelectedUpFilter",true);put("rawItemsRetained",true);put("notInterestedMenuInstalled",false)
            put("sameMidOwnerRotationRejectsSeed",true);put("guestLocalZeroHttp",true)
            put("taskOwnedReflectionTransport",true);put("sameCookieJar",true);put("chainProceed",false)
            put("httpFixtureFeedRequests",feeds.get());put("httpFixtureUpRequests",spaces.get());put("coldRoot",root.toString())
            put("screenshots",7);put("realCredentialOrSocket",false);put("HWND",false)}
    }catch(error:Throwable){runCatching{ui.save(dir.resolve("FAILED-current-frame.png"));ui.semantics(dir.resolve("FAILED-semantics.txt"))}
        Files.writeString(dir.resolve("FAILED.txt"),error.stackTraceToString());throw error
    }finally{initialRelease.countDown();rotatedRelease.countDown();ui.close();cache.shutdownForRestore();store.freezeWrites()
        client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val pins=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray
    val loaded=pins.map{row->val p=row.jsonObject;val name=p["class"]!!.jsonPrimitive.content;val type=Class.forName(name)
        check(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()==Path.of(p["codeSource"]!!.jsonPrimitive.content).toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()}
        check(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}==p["sha256Bytes"]!!.jsonPrimitive.content);row}
    Files.writeString(output.resolve("actual-loaded-class-identities.json"),JsonArray(loaded).toString())
    val checks=mutableListOf<JsonObject>()
    fun report(passed:Boolean,error:Throwable?=null){Files.writeString(output.resolve("ui.json"),buildJsonObject{
        put("passed",passed);put("checks",JsonArray(checks));put("productionOverrides",0);put("preparedOverlay",false)
        put("wholeMainOrRuntimeAcceptance",false);put("nativeWindowOrPackage",false);put("ImageComposeScene",true)
        put("realCredentialOrSocket",false);if(error!=null)put("failure",error.stackTraceToString())}.toString())}
    try{for(style in AppUiStyle.entries)for(mode in listOf(AppThemeMode.LIGHT,AppThemeMode.DARK)){
        checks+=case(output,style,mode,coroutineContext);report(false)};check(checks.size==4);report(true)
        println("PASS actual CommunityDynamicFeed cache 4 theme cells, zero production overrides")
    }catch(error:Throwable){report(false,error);throw error}
}
