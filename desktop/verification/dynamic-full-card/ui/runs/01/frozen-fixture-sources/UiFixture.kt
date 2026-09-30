@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.productdynamiccardproof

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import coil3.*
import coil3.decode.DataSource
import coil3.request.SuccessResult
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.util.concurrent.atomic.AtomicInteger

private class Ui(val width:Int,val height:Int,context:kotlin.coroutines.CoroutineContext){
    private val job=SupervisorJob();private val errors=mutableListOf<Throwable>()
    private val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,e->errors+=e})
    val scene=ImageComposeScene(width,height,coroutineContext=scope.coroutineContext)
    var nanos=0L;val pointers=mutableListOf<JsonObject>()
    private fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
    fun nodes()=scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}
    fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    fun matching(label:String)=nodes().filter{n->n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==label}==true||
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true}
    suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();yield();delay(5);check(errors.isEmpty()){errors.toString()}}
    suspend fun await(label:String,ready:()->Boolean){try{withTimeout(7000){while(!ready())frame()};repeat(8){frame()}}
        catch(error:TimeoutCancellationException){error("$label: ${texts()}")}}
    suspend fun press(node:SemanticsNode,label:String){val p=node.boundsInRoot.center;check(p.x in 0f..width.toFloat()&&p.y in 0f..height.toFloat())
        pointers+=buildJsonObject{put("label",label);put("x",p.x);put("y",p.y)}
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+60,buttons=PointerButtons());repeat(12){frame()}}
    suspend fun click(label:String){await("visible $label"){matching(label).any{it.boundsInRoot.top>=0&&it.boundsInRoot.bottom<=height&&it.boundsInRoot.width>0}}
        val rows=matching(label).filter{it.boundsInRoot.top>=0&&it.boundsInRoot.bottom<=height&&it.boundsInRoot.width>0}
        press(if(label=="全部")rows.minBy{it.boundsInRoot.top}else rows.last(),label)}
    private fun path(target:SemanticsNode):List<SemanticsNode>?{
        fun walk(node:SemanticsNode,prefix:List<SemanticsNode>):List<SemanticsNode>? {
            val next=prefix+node;if(node.id==target.id)return next
            for(child in node.children){val found=walk(child,next);if(found!=null)return found};return null}
        for(owner in scene.semanticsOwners){val found=walk(owner.unmergedRootSemanticsNode,emptyList());if(found!=null)return found};return null}
    suspend fun moreFor(body:String){await("body $body"){matching(body).isNotEmpty()}
        val bodyNode=matching(body).first();val ancestors=checkNotNull(path(bodyNode))
        val card=ancestors.asReversed().first{n->tree(n).any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("更多")==true}}
        val more=tree(card).first{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("更多")==true}
        press(more,"更多 for $body")}
    fun save(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
    fun semantics(path:Path){Files.writeString(path,nodes().joinToString("\n"){"${it.boundsInRoot} ${it.config}"})}
    suspend fun close(){scene.close();job.cancelAndJoin()}
}

@Suppress("UNCHECKED_CAST") private fun actualAll(memory:DesktopBrowseMemory):DesktopDynamicTimelineState?{
    val values=memory.javaClass.getDeclaredField("screens").apply{isAccessible=true}.get(memory) as Map<Any?,Any>
    return values.entries.firstOrNull{it.key==listOf("dynamic-settings-timeline",42L,0L,"all",0)}?.value as? DesktopDynamicTimelineState}

/** Read the production Users model registered by actual FeedReady. Nothing is registered by this fixture. */
@Suppress("UNCHECKED_CAST") private fun actualUsers(registry:DesktopDynamicCardStateRegistry):DesktopDynamicUsersState? {
    val models=registry.javaClass.getDeclaredField("models").apply{isAccessible=true}.get(registry)
        as MutableList<java.lang.ref.WeakReference<DesktopDynamicCardItemsOwner>>
    return synchronized(models){models.mapNotNull{it.get()}.filterIsInstance<DesktopDynamicUsersState>().singleOrNull()}
}

private suspend fun case(output:Path,style:AppUiStyle,mode:AppThemeMode,context:kotlin.coroutines.CoroutineContext):JsonObject{
    val name=style.name.lowercase()+"-"+mode.name.lowercase();val dir=output.resolve(name);Files.createDirectories(dir)
    val root=output.parent.resolve("cold-roots").resolve(name);val sessions=DesktopSessionStore(root.resolve("task-session.json"))
    check(sessions.account.value?.mid==42L)
    val store=DesktopPluginStore(root.resolve("actual-global"));val cache=DesktopDynamicCache(sessions,store)
    val prefs=DesktopDynamicTimelinePreferences(DesktopPluginContext(store));prefs.setLayoutMode(if(mode==AppThemeMode.DARK)DynamicFeedLayoutMode.LIST else DynamicFeedLayoutMode.WATERFALL)
    val repository=DesktopRepository(sessions);check(repository.httpClient.cookieJar===sessions)
    val requests=java.util.Collections.synchronizedList(mutableListOf<JsonObject>());val likes=java.util.Collections.synchronizedList(mutableListOf<Int>())
    val reposts=AtomicInteger();val deletes=AtomicInteger();val feeds=AtomicInteger();val upRequests=AtomicInteger()
    var backendLiked=false;var backendForward=9;var backendDeleted=false
    fun serverRows():List<DynamicItem> = rawCards().filterNot{backendDeleted&&it.id_str=="125"}.map{row->
        if(row.id_str!="123")row else row.copy(modules=row.modules.copy(module_stat=row.modules.module_stat!!.let{stat->
            stat.copy(like=stat.like.copy(count=if(backendLiked)18 else 17,status=backendLiked),forward=stat.forward.copy(count=backendForward))}))}
    val client=repository.httpClient.newBuilder().addInterceptor{chain->
        val request=chain.request();val path=request.url.encodedPath;val body=Buffer().also{request.body?.writeTo(it)}.readUtf8()
        requests+=buildJsonObject{put("method",request.method);put("host",request.url.host);put("path",path)
            put("query",buildJsonObject{request.url.queryParameterNames.filterNot{it=="csrf"}.forEach{put(it,request.url.queryParameter(it).orEmpty())}})
            if(body.isNotBlank())put("syntheticBody",Json.parseToJsonElement(body))}
        val response=when(path){
            "/x/polymer/web-dynamic/v1/feed/all"->{check(feeds.incrementAndGet()==1);Json.encodeToString(DynamicFeedResponse(data=DynamicFeedData(serverRows(),"",false,"123")))}
            "/x/polymer/web-dynamic/v1/feed/space"->{upRequests.incrementAndGet();val mid=request.url.queryParameter("host_mid")!!.toLong()
                Json.encodeToString(DynamicFeedResponse(data=DynamicFeedData(serverRows().filter{it.modules.module_author?.mid==mid},"",false,"123")))}
            "/x/relation/followings"->"""{"code":0,"data":{"list":[{"mid":77,"uname":"User-77","face":""}],"total":1}}"""
            "/xlive/web-ucenter/user/following"->"""{"code":0,"data":{"list":[]}}"""
            "/dynamic_svr/v1/dynamic_svr/w_dyn_uplist"->"""{"code":0,"data":{"items":[]}}"""
            "/x/emote/user/panel/web"->"""{"code":0,"data":{"packages":[]}}"""
            "/x/dynamic/feed/dyn/thumb"->{val payload=Json.parseToJsonElement(body).jsonObject;check(payload["dyn_id_str"]!!.jsonPrimitive.content=="123")
                val up=payload["up"]!!.jsonPrimitive.int;check(up in setOf(1,2));likes+=up;backendLiked=up==1;"""{"code":0}"""}
            "/x/dynamic/feed/create/dyn"->{val payload=Json.parseToJsonElement(body).jsonObject
                check(payload["dyn_req"]!!.jsonObject["scene"]!!.jsonPrimitive.int==4)
                check(payload["web_repost_src"]!!.jsonObject["dyn_id_str"]!!.jsonPrimitive.content=="123")
                reposts.incrementAndGet();backendForward++;"""{"code":0}"""}
            "/x/dynamic/feed/operate/remove"->{val payload=Json.parseToJsonElement(body).jsonObject
                check(payload["dyn_id_str"]!!.jsonPrimitive.content=="125"&&payload["dyn_type"]!!.jsonPrimitive.int==4&&payload["rid_str"]!!.jsonPrimitive.content=="125")
                deletes.incrementAndGet();backendDeleted=true;"""{"code":0}"""}
            else->error("Unexpected explicit task transport: $path; never chain.proceed")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("task fixture")
            .body(response.toResponseBody("application/json".toMediaType())).build()
    }.build()
    for((field,value)in listOf("client" to client,"visitorInitialized" to true,"visitorGeneration" to sessions.generation))
        repository.javaClass.getDeclaredField(field).apply{isAccessible=true}.set(repository,value)
    val community=DesktopCommunityRepository(repository,DesktopBlockedUpStore(prefs.context));val social=DesktopSocialRepository(repository)
    val cardSession=DesktopDynamicCardSession(repository,sessions.generation)
    val registry=DesktopDynamicCardStateRegistry(repository.dynamicCacheSessionGuard,cache,sessions.generation,cardSession::isOwned)
    val memory=DesktopBrowseMemory();val ui=Ui(1200,1600,context)
    val checkpoints=mutableListOf<JsonObject>();var users:DesktopDynamicUsersState?=null
    fun stat(rows:List<DynamicItem>,id:String)=rows.first{it.id_str==id}.modules.module_stat!!
    suspend fun rawCheckpoint(name:String){cache.flush();val session=checkNotNull(cache.openCurrent());val all=checkNotNull(actualAll(memory))
        val value=buildJsonObject{put("checkpoint",name);put("allRaw",Json.encodeToJsonElement(all.page.items))
            put("upRaw",Json.encodeToJsonElement(checkNotNull(users).userItems));put("cachedRaw",Json.encodeToJsonElement(session.cachedAllItems.value))
            put("notInterested",JsonArray(session.notInterestedIds.value.map(::JsonPrimitive)))
            put("allHasMore",all.page.hasMore);put("allInitialized",all.initialized);put("allBusy",all.busy)}
        checkpoints+=value;Files.writeString(dir.resolve("raw-$name.json"),value.toString())}
    try{
        ui.scene.setContent{DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=mode,hapticFeedbackEnabled=false)){
            CompositionLocalProvider(LocalDesktopBrowseMemory provides memory,LocalDesktopDynamicCache provides cache,
                LocalDesktopDynamicTimelinePreferences provides prefs,LocalDesktopDynamicCardRepository provides repository,
                LocalDesktopDynamicCardSession provides cardSession,LocalDesktopDynamicCardStateRegistry provides registry,
                LocalDesktopDynamicCardMutations provides registry.bindings){AppSurface(Modifier.fillMaxSize()){
                CommunityContentScreen(CommunitySection.DYNAMIC,repository,social,community,onVideo={},onUser={},onArticle={},onLogin={})
                com.android.purebilibili.feature.dynamic.components.ImagePreviewOverlayHost()
            }}}}
        ui.await("actual original five raw cards and cache integration"){actualAll(memory)?.initialized==true&&"Counter-A" in ui.texts()&&"Sibling-B" in ui.texts()}
        val all=checkNotNull(actualAll(memory));check(all.page.items.size==5);check("Hidden-Related" !in ui.texts())
        ui.save(dir.resolve("01-original-all-full-cards.png"));ui.semantics(dir.resolve("01-semantics.txt"))
        ui.click("UP");ui.await("actual original UP rail"){"User-77" in ui.texts()}
        users=actualUsers(registry);checkNotNull(users){"Actual FeedReady did not register its original Users owner"}
        ui.click("User-77");ui.await("actual selected-UP remote raw entries"){users!!.selectedUid==77L&&!users!!.userLoading&&users!!.userItems.size==4}
        check(upRequests.get()==1);check(users!!.userItems.first{it.id_str=="127"}.visible==false)
        val siblingAll=all.page.items.first{it.id_str=="124"};val siblingUp=users!!.userItems.first{it.id_str=="124"}
        ui.click("17");ui.await("confirmed original up1 changes real ALL and UP counts"){
            likes.toList()==listOf(1)&&stat(all.page.items,"123").like.count==18&&stat(users!!.userItems,"123").like.count==18}
        check(all.page.items.first{it.id_str=="124"}===siblingAll);check(users!!.userItems.first{it.id_str=="124"}===siblingUp)
        rawCheckpoint("02-like");ui.await("original like feedback"){"已点赞" in ui.texts()};ui.click("确定")
        ui.save(dir.resolve("02-up-like-confirmed-all-up-cache.png"))
        ui.click("18");ui.await("confirmed original unlike uses up2 and synchronizes"){
            likes.toList()==listOf(1,2)&&stat(all.page.items,"123").like.count==17&&stat(users!!.userItems,"123").like.count==17}
        check(!stat(all.page.items,"123").like.status&&!stat(users!!.userItems,"123").like.status)
        rawCheckpoint("03-unlike");ui.await("original unlike feedback"){"已取消" in ui.texts()};ui.click("确定")
        val forward=checkNotNull(com.android.purebilibili.feature.dynamic.resolveDynamicActionButtonText("转发",9))
        ui.click(forward);ui.await("original repost dialog"){"转发动态" in ui.texts()};ui.click("转发")
        ui.await("confirmed original repost updates ALL and UP"){
            reposts.get()==1&&stat(all.page.items,"123").forward.count==10&&stat(users!!.userItems,"123").forward.count==10}
        rawCheckpoint("04-repost");ui.await("original repost feedback"){"转发成功" in ui.texts()};ui.click("确定")
        ui.save(dir.resolve("03-up-unlike-repost-confirmed.png"))
        ui.click("展开1条相关动态");ui.await("original related unfold reaches all and up raw"){
            all.page.items.first{it.id_str=="127"}.visible&&users!!.userItems.first{it.id_str=="127"}.visible&&"Hidden-Related" in ui.texts()}
        check(all.page.items.first{it.id_str=="126"}.modules.module_fold==null);check(users!!.userItems.first{it.id_str=="126"}.modules.module_fold==null)
        rawCheckpoint("05-unfold");ui.save(dir.resolve("04-original-related-unfold.png"));ui.semantics(dir.resolve("04-semantics.txt"))
        val beforeAll=all.page.items;val beforeUp=users!!.userItems;val postCountBefore=requests.count{it["method"]!!.jsonPrimitive.content=="POST"}
        ui.moreFor("Sibling-B");ui.await("actual original menu not-interest"){"不感兴趣" in ui.texts()};ui.click("不感兴趣")
        ui.await("real menu hides sibling without raw removal"){
            checkNotNull(cache.openCurrentNonSuspendingFixtureOrNull()).notInterestedIds.value==setOf("124")&&"Sibling-B" !in ui.texts()}
        check(all.page.items===beforeAll&&users!!.userItems===beforeUp);check(requests.count{it["method"]!!.jsonPrimitive.content=="POST"}==postCountBefore)
        rawCheckpoint("06-not-interested");ui.save(dir.resolve("05-original-not-interest-filter-raw-retained.png"))
        // The per-card feedback can disappear when its successful local filter retires that card.
        if("已标记为不感兴趣" in ui.texts())ui.click("确定")
        ui.click("全部");ui.await("actual ALL after filter"){users!!.selectedLogicalTab==0&&"Counter-A" in ui.texts()&&"Sibling-B" !in ui.texts()}
        ui.save(dir.resolve("06-all-confirmed-counts-fold-and-filter.png"))
        ui.click("UP");ui.await("self original rail"){"我" in ui.texts()};ui.click("我")
        ui.await("actual self MID42 raw selected source") {users!!.selectedUid==42L&&!users!!.userLoading&&users!!.userItems.singleOrNull()?.id_str=="125"}
        ui.moreFor("Delete-Self");ui.await("original delete menu"){"删除" in ui.texts()};ui.click("删除")
        ui.await("original delete requires explicit confirmation") {"Fixture delete confirm" in ui.texts()&&"确认删除 fixture125" in ui.texts()}
        check(deletes.get()==0);ui.save(dir.resolve("07-original-delete-confirmation.png"))
        ui.click("确认删除 fixture125");ui.await("confirmed Delete removes only raw125 from both"){
            deletes.get()==1&&all.page.items.none{it.id_str=="125"}&&users!!.userItems.none{it.id_str=="125"}}
        rawCheckpoint("07-delete");check(all.page.items.map{it.id_str}==listOf("123","124","126","127"))
        check(all.page.hasMore==false&&all.initialized&&!all.busy)
        if("已删除动态" in ui.texts())ui.click("确定")
        ui.click("全部");ui.await("final actual all") {users!!.selectedLogicalTab==0&&"Counter-A" in ui.texts()&&"Hidden-Related" in ui.texts()&&"Delete-Self" !in ui.texts()}
        ui.save(dir.resolve("08-final-actual-all.png"));ui.semantics(dir.resolve("08-semantics.txt"))
        cache.flush();check(likes.toList()==listOf(1,2));check(reposts.get()==1&&deletes.get()==1)
        check(requests.count{it["path"]!!.jsonPrimitive.content=="/x/v2/reply/add"}==0)
        val emotes=requests.filter{it["path"]!!.jsonPrimitive.content=="/x/emote/user/panel/web"}.map{it["query"]!!.jsonObject["business"]!!.jsonPrimitive.content}
        check(emotes==listOf("dynamic","reply"))
        Files.writeString(dir.resolve("request-trace.json"),JsonArray(requests.toList()).toString())
        Files.writeString(dir.resolve("pointers.json"),JsonArray(ui.pointers).toString())
        Files.writeString(dir.resolve("checkpoints.json"),JsonArray(checkpoints).toString())
        return buildJsonObject{put("style",style.name);put("themeMode",mode.name);put("passed",true);put("pointerPairs",ui.pointers.size);put("screenshots",8)
            put("actualCommunityContentWithProductRegistryBindings",true);put("wholeShellExecuted",false);put("actualOriginalDynamicCardV2",true);put("readActualUpStateFromRegistryWeakReference",true)
            put("likeUnlikeRepostAllUpAndCache",true);put("confirmedDeleteAllUpAndCache",true);put("originalUnfoldAllUpAndCache",true)
            put("originalMenuNotInterestedFilterOnly",true);put("untouchedSiblingReference",true);put("pagingEnvelopeRetained",true)
            put("sharedEmoteCatalogDynamicReplyOnce",true);put("sameSessionCookieJar",true);put("allPrimaryRequests",feeds.get());put("upRequests",upRequests.get())
            put("taskOnlyReflectionTransport",true);put("chainProceed",false);put("coldRoot",root.toString());put("realAccountOrSocketHWNDOrPackage",false)}
    }catch(error:Throwable){runCatching{ui.save(dir.resolve("FAILED-current-frame.png"));ui.semantics(dir.resolve("FAILED-semantics.txt"))}
        Files.writeString(dir.resolve("FAILED.txt"),error.stackTraceToString());throw error
    }finally{registry.close();cardSession.close();ui.close();cache.shutdownForRestore();store.freezeWrites();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}

// A read-only fixture seam finds the already-open actor session; it opens no alternate actor.
private fun DesktopDynamicCache.openCurrentNonSuspendingFixtureOrNull():DesktopDynamicCacheSession? =
    javaClass.getDeclaredField("active").apply{isAccessible=true}.get(this) as? DesktopDynamicCacheSession

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val pins=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray
    val loaded=pins.map{row->val p=row.jsonObject;val name=p["class"]!!.jsonPrimitive.content;val type=Class.forName(name)
        check(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()==Path.of(p["codeSource"]!!.jsonPrimitive.content).toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()}
        check(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}==p["sha256Bytes"]!!.jsonPrimitive.content);row}
    Files.writeString(output.resolve("actual-loaded-class-identities.json"),JsonArray(loaded).toString())
    val loader=ImageLoader.Builder(PlatformContext.INSTANCE).components{add(coil3.intercept.Interceptor{chain->
        SuccessResult(ColorImage(0xff20c7cc.toInt(),400,300),chain.request,DataSource.MEMORY)})}.build();SingletonImageLoader.setUnsafe(loader)
    val checks=mutableListOf<JsonObject>()
    fun report(passed:Boolean,error:Throwable?=null){Files.writeString(output.resolve("ui.json"),buildJsonObject{
        put("passed",passed);put("checks",JsonArray(checks));put("productionOverrides",0);put("preparedOverlay",false)
        put("wholeMainOrRuntimeAcceptance",false);put("nativeWindowOrPackage",false);put("ImageComposeScene",true)
        put("realCredentialOrSocket",false);if(error!=null)put("failure",error.stackTraceToString())}.toString())}
    try{for(style in AppUiStyle.entries)for(mode in listOf(AppThemeMode.LIGHT,AppThemeMode.DARK)){
        checks+=case(output,style,mode,coroutineContext);report(false)};check(checks.size==4);report(true)
        println("PASS original full dynamic card actual Main all/UP/cache mutation UI four-cell matrix")
    }catch(error:Throwable){report(false,error);throw error}finally{loader.shutdown()}
}
