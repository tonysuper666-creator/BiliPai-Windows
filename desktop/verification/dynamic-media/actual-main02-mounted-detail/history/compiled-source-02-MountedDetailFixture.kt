@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*

private data class MountedRequest(val path:String,val query:Map<String,String?>,val bytes:ByteArray)
private class TerminalHold {
    val entered=CountDownLatch(1); val released=CountDownLatch(1); val returned=CountDownLatch(1)
    fun waitResponse() { entered.countDown(); check(released.await(10,TimeUnit.SECONDS)){"Fixture terminal release timeout"} }
}
private fun field(repository:DesktopRepository,name:String,value:Any)=repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
private fun wordItem(id:String,text:String,count:Int)=buildJsonObject {
    put("id_str",id);put("type","DYNAMIC_TYPE_WORD");put("visible",true)
    putJsonObject("basic"){put("comment_id_str",id);put("comment_type",17)}
    putJsonObject("modules") {
        putJsonObject("module_author"){put("mid",42);put("name","Fixture author");put("face","");put("pub_time","测试");put("pub_action","")}
        putJsonObject("module_dynamic"){putJsonObject("desc"){put("text",text)}}
        putJsonObject("module_stat") {
            putJsonObject("forward"){put("count",2)};putJsonObject("comment"){put("count",count)}
            putJsonObject("like"){put("count",8);put("status",false)}
        }
    }
}.toString()
private fun replyJson(id:Long,oid:String,message:String,root:Long=0,parent:Long=0)=buildJsonObject {
    put("rpid",id);put("oid",oid.toLong());put("type",17);put("mid",42);put("root",root);put("parent",parent)
    put("like",8);put("count",1);put("rcount",1);put("ctime",10)
    putJsonObject("member"){put("mid","42");put("uname","Fixture member");put("avatar","")}
    putJsonObject("content"){put("message",message)}
    putJsonObject("reply_control"){put("location","IP属地：测试")}
}
private fun threadWire(rootId:Long,oid:Long,message:String,childId:Long=710):ByteArray {
    fun info(id:Long,root:Long,parent:Long,text:String,child:ByteArray?=null)=ProtoWire.message(
        child?.let{ProtoWire.bytes(1,it)}?:byteArrayOf(),ProtoWire.int64(2,id),ProtoWire.int64(3,oid),ProtoWire.int32(4,17),
        ProtoWire.int64(5,42),ProtoWire.int64(6,root),ProtoWire.int64(7,parent),ProtoWire.int32(9,8),ProtoWire.int64(10,10),ProtoWire.int32(11,1),
        ProtoWire.bytes(12,ProtoWire.message(ProtoWire.string(1,text))),
        ProtoWire.bytes(13,ProtoWire.message(ProtoWire.int64(1,42),ProtoWire.string(2,"Fixture member"))),
        ProtoWire.bytes(14,ProtoWire.message(ProtoWire.string(25,"IP属地：测试"))))
    val child=info(childId,rootId,rootId,"Thread child $childId")
    return ProtoWire.message(ProtoWire.bytes(1,ProtoWire.message(ProtoWire.bool(4,true))),
        ProtoWire.bytes(2,ProtoWire.message(ProtoWire.int64(16,1))),ProtoWire.bytes(3,info(rootId,0,0,message,child)))
}
private fun form(row:MountedRequest)=row.bytes.toString(Charsets.UTF_8).split('&').filter{it.isNotEmpty()}.associate{
    val pair=it.split('=',limit=2);URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    val temp=Files.createTempDirectory("bilipai-mounted-original-detail-")
    val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
    val sessions=DesktopSessionStore(temp.resolve("fixture-account.json"),persistent=false)
    sessions.saveAccount(mapOf("SESSDATA" to "not-real","bili_jct" to "fixture-csrf","buvid3" to "fixture-buvid"),AccountSummary(42,"fixture",""))
    sessions.saveSpiCookies(mapOf("buvid3" to "fixture-buvid"))
    val repository=DesktopRepository(sessions)
    val rows=Collections.synchronizedList(mutableListOf<MountedRequest>())
    val detailCount=AtomicInteger(10);val commentCount=AtomicInteger(33)
    val text=AtomicReference("Initial actual Root detail")
    val detailHold=AtomicReference<TerminalHold?>(TerminalHold());val initialHold=checkNotNull(detailHold.get())
    val detailHeldText=AtomicReference<String?>(null);val detailHeldCount=AtomicInteger(-1)
    val threadHold=AtomicReference<TerminalHold?>(null)
    val terminal=repository.httpClient.newBuilder().addInterceptor { chain ->
        val request=chain.request();val path=request.url.encodedPath
        val bytes=Buffer().also{request.body?.writeTo(it)}.readByteArray()
        val query=request.url.queryParameterNames.associateWith(request.url::queryParameter)
        rows+=MountedRequest(path,query,bytes)
        var status="0";var contentType="application/json";var activeHold:TerminalHold?=null
        val result=try {
            when {
                path.endsWith("/DetailList") -> {
                    contentType="application/grpc";activeHold=threadHold.getAndSet(null);activeHold?.waitResponse()
                    val wire=ProtoWire.parseFields(ProtoWire.unframe(bytes));val oid=wire.single{it.number==1}.varint
                    val root=wire.single{it.number==3}.varint
                    ProtoWire.frame(threadWire(root,oid,if(activeHold!=null)"Retired old root result" else "Route root $root"))
                }
                path.startsWith("/bilibili.main.community.reply.v1.Reply/") -> {contentType="application/grpc";status="13";ProtoWire.frame(byteArrayOf())}
                path=="/x/polymer/web-dynamic/v1/detail" || path=="/x/polymer/web-dynamic/desktop/v1/detail" -> {
                    activeHold=detailHold.getAndSet(null);activeHold?.waitResponse()
                    val id=query["id"]?:"100"
                    val body=wordItem(id,if(activeHold!=null)detailHeldText.get()?:text.get() else text.get(),
                        if(activeHold!=null&&detailHeldCount.get()>=0)detailHeldCount.get() else detailCount.get())
                    """{"code":0,"data":{"item":$body}}""".toByteArray()
                }
                path=="/x/polymer/web-dynamic/v1/opus/detail" -> """{"code":-404}""".toByteArray()
                path=="/x/v2/reply/count" -> """{"code":0,"data":{"count":${commentCount.get()}}}""".toByteArray()
                path in setOf("/x/v2/reply/wbi/main","/x/v2/reply/main","/x/v2/reply") -> {
                    val oid=query["oid"]?:"100"
                    val reply=replyJson(if(oid=="100")701 else 801,oid,if(oid=="100")"Main reply 701" else "Replacement reply 801")
                    """{"code":0,"data":{"cursor":{"all_count":${commentCount.get()},"is_end":true},"replies":[$reply]}}""".toByteArray()
                }
                path=="/x/v2/reply/add" -> {commentCount.incrementAndGet();"""{"code":0,"data":{"reply":{"rpid":901,"mid":42,"ctime":10,"content":{"message":"posted"}}}}""".toByteArray()}
                path=="/x/v2/reply/del" -> {commentCount.decrementAndGet();"""{"code":0}""".toByteArray()}
                path=="/x/emote/user/panel/web" -> """{"code":0,"data":{"packages":[]}}""".toByteArray()
                path=="/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"fixture","wbi_img":{"img_url":"https://fixture.invalid/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://fixture.invalid/4932caff0ff746eab6f01bf08b70ac45.png"}}}""".toByteArray()
                else -> throw IOException("Unexpected terminal fixture path: $path; no socket fallback")
            }
        } finally {activeHold?.returned?.countDown()}
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("grpc-status",status).body(result.toResponseBody(contentType.toMediaType())).build()
    }.build()
    field(repository,"client",terminal)
    val nav=Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(terminal)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    field(repository,"api",nav);field(repository,"visitorInitialized",true);field(repository,"visitorGeneration",sessions.generation)
    val store=DesktopPluginStore(temp.resolve("global-store"));val context=DesktopPluginContext(store)
    val community=DesktopCommunityRepository(repository,DesktopBlockedUpStore(context))
    val preferences=DesktopDynamicTimelinePreferences(context)
    val cache=DesktopDynamicCache(repository.dynamicCacheSessionGuard,store)
    val registry=DesktopDynamicCardStateRegistry(repository.dynamicCacheSessionGuard,cache,repository.sessionEpoch)
    val cardSession=DesktopDynamicCardSession(repository)
    var route by mutableStateOf(DesktopDynamicDetailRoute("100",701,710));var mounted by mutableStateOf(true)
    val navigation=CommunityNavigation({}, {}, {}, {error("Unexpected login navigation")},{},{},{},onDynamicBack={mounted=false})
    val scene=ImageComposeScene(width=1100,height=800,coroutineContext=coroutineContext)
    val ui=ComposerScene(scene);val cases=mutableListOf<String>()
    fun snapshot()=synchronized(rows){rows.toList()}
    fun comments()=snapshot().filter{it.path.startsWith("/x/v2/reply")||it.path.contains("community.reply")}
    fun descriptions(node:SemanticsNode)=node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    suspend fun pressText(label:String,last:Boolean=false) {
        val candidates=ui.nodes().filter{it.config.getOrNull(SemanticsProperties.Text).orEmpty().any{text->text.text==label}}
        ui.press(if(last)candidates.last()else candidates.first())
    }
    suspend fun pressDescription(label:String,last:Boolean=false) {
        val candidates=ui.nodes().filter{label in descriptions(it)}
        ui.press(if(last)candidates.last()else candidates.first())
    }
    try {
        scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled=false)) {
                CompositionLocalProvider(LocalDesktopDynamicCardRepository provides repository,
                    LocalDesktopDynamicCardSession provides cardSession,
                    LocalDesktopDynamicTimelinePreferences provides preferences,
                    LocalDesktopDynamicCardMutations provides registry.bindings,
                    LocalDesktopDynamicCardStateRegistry provides registry,
                    LocalDesktopDetailForeground provides true,
                    LocalDesktopTextClipboard provides DesktopTextClipboard{error("No OS clipboard in mounted fixture")}) {
                    if(mounted)CommunityDynamicDetail(route,community,navigation)
                }
            }
        }
        ui.await("initial actual detail request reaches gated terminal"){initialHold.entered.count==0L}
        repeat(20){ui.frame()}
        check(comments().isEmpty()){ "Actual Loading branch started comments before detail: ${comments().map{it.path}}" }
        cases+="gated-initial-detail-Loading-has-no-comment-requests"
        initialHold.released.countDown()
        ui.await("actual original root route 701 opens and child is rendered"){ui.labels().any{it.contains("Thread child 710")}}
        check(snapshot().filter{it.path.endsWith("/DetailList")}.any {
            val wire=ProtoWire.parseFields(ProtoWire.unframe(it.bytes));wire.single{f->f.number==3}.varint==701L&&wire.single{f->f.number==4}.varint==710L})
        ui.screenshot(output.resolveSibling("main02-root-701-thread.png"))
        cases+="actual-Main-CommunityDetail-route-root701-target710-original-thread"
        ui.await("detail-first comments confirm 33 on actual card"){ui.labels().any{it=="33"}}
        check(ui.labels().any{it.contains("Initial actual Root detail")})
        // Actual root reply UI -> original inline composer, no direct Session injection.
        val actualReplies=ui.nodes().filter{it.config.getOrNull(SemanticsProperties.Text).orEmpty().any{text->text.text=="回复"}}
        check(actualReplies.size>=3);ui.press(actualReplies[1]) // Modal root701; background row is physically covered.
        ui.await("actual original reply pointer closes modal and focuses inline field"){ui.nodes().any{it.config.getOrNull(SemanticsActions.SetText)!=null&&it.config.getOrNull(SemanticsProperties.Focused)==true}}
        val late=TerminalHold();detailHeldText.set("Late detail after publish");detailHeldCount.set(1);detailHold.set(late)
        cardSession.confirmContentChange() // Synthetic parent confirmed refresh event, actual product owner/collector.
        ui.await("refresh retains Success and enters held detail request"){late.entered.count==0L}
        ui.input("  Root mounted post  ");ui.send()
        ui.await("actual post body and refreshed count reach 34"){snapshot().any{it.path=="/x/v2/reply/add"}&&ui.labels().any{it=="34"}}
        val post=form(snapshot().single{it.path=="/x/v2/reply/add"})
        check(post["oid"]=="100"&&post["type"]=="17"&&post["root"]=="701"&&post["parent"]=="701"&&post["message"]=="Root mounted post"){"Actual post target mismatch: ${post.filterKeys{it!="csrf"}}"}
        late.released.countDown()
        ui.await("late detail readback description becomes visible while confirmed count remains34"){ui.labels().any{it.contains("Late detail after publish")}}
        check(ui.labels().any{it=="34"}){"Confirmed count lost to delayed detail: ${ui.labels()}"}
        ui.screenshot(output.resolveSibling("main02-publish-count-late-readback.png"))
        cases+="original-pointer-compose-post-701701-and-count34-preserves-delayed-detail"
        // Original post reload closes thread without route replay. Dismiss only if an actual layer remains.
        ui.nodes().firstOrNull{"Close" in descriptions(it)}?.let{ui.press(it)}
        val lateDelete=TerminalHold();detailHeldText.set("Late detail after delete");detailHeldCount.set(99);detailHold.set(lateDelete)
        cardSession.confirmContentChange();ui.await("delete refresh held"){lateDelete.entered.count==0L}
        pressDescription("删除")
        ui.await("actual original delete mutation decrements confirmed count to33"){snapshot().any{it.path=="/x/v2/reply/del"}&&ui.labels().any{it=="33"}}
        val deleted=form(snapshot().single{it.path=="/x/v2/reply/del"});check(deleted["rpid"]=="701"&&deleted["oid"]=="100"&&deleted["type"]=="17")
        lateDelete.released.countDown()
        ui.await("late delete detail readback appears"){ui.labels().any{it.contains("Late detail after delete")}}
        check(ui.labels().any{it=="33"}){"Delete count overwritten by stale detail: ${ui.labels()}"}
        cases+="actual-original-delete-pointer-and-count33-preserves-delayed-detail99"
        // Replace owner while its routed thread response is blocked; a cancelled result must not publish.
        route=DesktopDynamicDetailRoute("200",801,810)
        ui.await("replacement root 801 actual thread ready"){ui.labels().any{it.contains("Route root 801")}}
        pressDescription("Close")
        val oldThread=TerminalHold();threadHold.set(oldThread)
        // Actual comment item opens root801 from its original count row.
        pressText("共1条回复")
        ui.await("old thread request held after actual pointer"){oldThread.entered.count==0L}
        mounted=false;repeat(15){ui.frame()};oldThread.released.countDown();repeat(35){ui.frame()}
        check(ui.labels().isEmpty()){"Disposed detail published old thread: ${ui.labels()}"}
        cases+="actual-page-dispose-retires-pending-root801-and-no-old-thread-publication"
        Files.writeString(output,buildJsonObject {
            put("passed",true);putJsonArray("cases"){cases.forEach{add(it)}}
            put("actualMainCommunityConsumer",true);put("actualMainOriginalCardLayoutReplyComposer",true)
            put("actualMainRepositorySessionRegistryGlobalPrefs",true);put("productOverrides",0)
            put("communityConsumerClassSource",Class.forName("com.bilipai.desktop.ui.CommunityDynamicScreensKt").protectionDomain.codeSource.location.toString())
            put("originalSessionClassSource",Class.forName("com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession").protectionDomain.codeSource.location.toString())
            put("pointerPairs",ui.pointers);put("editorSemanticTextEntries",ui.semanticsTextEntries);put("actualImeActions",ui.imeActions)
            put("rootTarget",701);put("actualPostedRoot",post.getValue("root"));put("actualPostedParent",post.getValue("parent"))
            put("initialCommentsBeforeDetail",false);put("initialCountBeforeDataPathIsReachable",false)
            put("refreshEventIsSyntheticParentCallback",true);put("noSessionFlowInjection",true)
            put("glassEnabled",false);put("wholeRootShellRuntimeAcceptance",false);put("realAccount",false);put("socketHTTP",false);put("HWND",false)
            putJsonArray("terminalRequests"){snapshot().forEach{add(buildJsonObject{put("path",it.path);put("oid",it.query["oid"]?:"");put("id",it.query["id"]?:"")})}}
        }.toString())
    } catch(failure:Throwable) {
        ui.screenshot(output.resolveSibling("failure-current-main02.png"))
        Files.writeString(output.resolveSibling("failure-current-main02.json"),buildJsonObject {
            put("passed",false);put("failure",failure.toString());putJsonArray("completedCases"){cases.forEach{add(it)}}
            putJsonArray("labels"){ui.labels().forEach{add(it)}};putJsonArray("paths"){snapshot().forEach{add(it.path)}}
        }.toString());throw failure
    } finally {
        initialHold.released.countDown();detailHold.getAndSet(null)?.released?.countDown();threadHold.getAndSet(null)?.released?.countDown()
        scene.close();cardSession.close();registry.close();cache.shutdownForRestore();terminal.dispatcher.executorService.shutdownNow();terminal.connectionPool.evictAll()
    }
}
