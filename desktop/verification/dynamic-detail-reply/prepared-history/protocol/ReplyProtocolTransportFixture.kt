package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.core.network.grpc.DesktopDynamicBiliGrpcClient
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc
import com.android.purebilibili.data.repository.DesktopDynamicCommentProtocol
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

// Compile with existing Operations + member fragment and original API/models/ProtoWire once.
// Every response is terminal at application interceptor; no chain.proceed/socket fallback.
// AUTH/GUEST header labels select fixture services and do not prove BridgeInterceptor cookies.
fun main(args:Array<String>):Unit = runBlocking {
    val temp=Files.createTempDirectory("raw-reply-protocol-")
    val sessions=DesktopSessionStore(temp.resolve("fake-session.json"),persistent=false)
    fun credentials(label:String)=sessions.saveAccount(mapOf("SESSDATA" to "not-real-$label","bili_jct" to "fixture-csrf","buvid3" to "fixture-buvid"),AccountSummary(42L,"fixture",""))
    credentials("one")
    sessions.saveSpiCookies(mapOf("buvid3" to "fixture-buvid"))
    val repository=DesktopRepository(sessions)
    val rows=Collections.synchronizedList(mutableListOf<Pair<Request,ByteArray>>())
    val scenario=AtomicReference("grpc")
    val hold=AtomicReference<Pair<String,Triple<CountDownLatch,CountDownLatch,CountDownLatch>>?>(null)
    val active=AtomicReference<Triple<CountDownLatch,CountDownLatch,CountDownLatch>?>(null)
    val grpcBase="/bilibili.main.community.reply.v1.Reply/"
    val rest="""{"code":0,"data":{"cursor":{"all_count":33,"is_end":false,"pagination_reply":{"next_offset":"rest-next"}},"replies":[{"rpid":810,"oid":900,"type":17,"root":700,"parent":701,"dialog":77,"action":2,"like":8,"member":{"mid":"88","uname":"rest"},"content":{"message":"rest text","at_name_to_mid":{"@Rest":88},"pictures":[{"img_src":"https://fixture.invalid/p.png","img_width":13,"img_height":17,"img_size":1.25}]},"reply_control":{"location":"IP属地：测试","is_up_top":true,"translation_switch":2}}]}}"""
    val empty="""{"code":0,"data":{"cursor":{"all_count":33},"replies":[]}}"""
    val terminal=repository.httpClient.newBuilder().addInterceptor { chain ->
        val request=chain.request();val path=request.url.encodedPath
        rows+=request to Buffer().also { request.body?.writeTo(it) }.readByteArray()
        val waiting=hold.get()?.takeIf { it.first==path }?.let { hold.getAndSet(null)?.second }
        try {
            waiting?.let { (entered,released,_) -> entered.countDown();check(released.await(4,TimeUnit.SECONDS)) }
            var status="0"
            val bytes:ByteArray
            val contentType:String
            if(path.startsWith(grpcBase)) {
                contentType="application/grpc"
                bytes=ProtoWire.frame(when(path.substringAfterLast('/')) {
                    "MainList" -> if(scenario.get() in setOf("grpc","restore")) rawReplyMainFixture(scenario.get()!="restore") else {status="13";byteArrayOf()}
                    "DetailList" -> rawReplyDetailFixture()
                    "DialogList" -> rawReplyDialogFixture()
                    "TranslateReply" -> rawReplyTranslateFixture(701)
                    else -> throw IOException("Unexpected gRPC path "+path)
                })
            } else {
                contentType="application/json"
                val mode=request.header("X-Reply-Fixture-Mode").orEmpty()
                bytes=when(path) {
                    "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"fixture","wbi_img":{"img_url":"https://fixture.invalid/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://fixture.invalid/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
                    "/x/v2/reply/count" -> if(scenario.get()=="countError")"""{"code":-101,"message":"fixture count failure"}""" else """{"code":0,"data":{"count":33}}"""
                    "/x/v2/reply/wbi/main","/x/v2/reply/main","/x/v2/reply" -> when(scenario.get()) {
                        "authGuest" -> if(path.endsWith("wbi/main")&&mode=="GUEST")rest else """{"code":-101,"message":"fixture auth failure"}"""
                        "nonWbi" -> if(path=="/x/v2/reply"&&mode=="GUEST")rest else """{"code":-403,"message":"fixture candidate"}"""
                        "hotEmpty" -> if(path=="/x/v2/reply")"""{"code":0,"data":{"hots":[{"rpid":811,"content":{"message":"hot"}}]}}""" else empty
                        "restore" -> """{"code":-400,"message":"fixture REST failure"}"""
                        else -> rest
                    }
                    "/x/v2/reply/reply" -> rest
                    "/x/v2/reply/add" -> """{"code":0,"data":{"reply":{"rpid":9001,"mid":88,"ctime":10,"content":{"message":"posted"}}}}"""
                    "/x/dynamic/feed/draw/upload_bfs" -> """{"code":0,"data":{"image_url":"https://fixture.invalid/uploaded.png","image_width":13,"image_height":17,"img_size":1.25}}"""
                    "/x/v2/reply/action","/x/v2/reply/hate","/x/v2/reply/del","/x/v2/reply/top","/x/v2/reply/report" -> """{"code":0}"""
                    else -> throw IOException("Unexpected fixture path "+path+"; no socket fallback")
                }.toByteArray()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .header("grpc-status",status).body(bytes.toResponseBody(contentType.toMediaType())).build()
        } finally { waiting?.third?.countDown() }
    }.build()
    fun labelledApi(label:String):BilibiliApi {
        val client=terminal.newBuilder().apply {
            cookieJar(if(label=="GUEST")CookieJar.NO_COOKIES else repository.httpClient.cookieJar)
            interceptors().add(0,Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Reply-Fixture-Mode",label).build()) })
        }.build()
        return Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(Json {ignoreUnknownKeys=true}.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
    }
    val auth=labelledApi("AUTH");val guest=labelledApi("GUEST")
    fun field(name:String,value:Any)=repository.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(repository,value)
    field("client",terminal);field("api",auth)
    fun warmVisitor(){field("visitorInitialized",true);field("visitorGeneration",sessions.generation)}
    warmVisitor()
    var alive=true;var signing=true
    fun newOperations():DesktopDynamicCardOperations {
        val ops=DesktopDynamicCardOperations(repository,stillOwned={alive})
        fun owned(){if(!ops.isOwned())throw CancellationException("fixture owner retired")}
        val actualGrpc=ops.javaClass.getDeclaredField("grpc").apply {isAccessible=true}.get(ops) as DesktopDynamicBiliGrpcClient
        val rawGrpc=DesktopDynamicCommentGrpc(actualGrpc::request,::owned)
        val protocol=DesktopDynamicCommentProtocol(auth,guest,rawGrpc,
            {owned();sessions.currentCookies()["SESSDATA"]?.isNotEmpty()==true},
            {params -> if(!signing)throw IOException("fixture signing unavailable");repository.signWebParams(params)},::owned)
        ops.javaClass.getDeclaredField("commentProtocol").apply {isAccessible=true}.set(ops,protocol)
        return ops
    }
    var ops=newOperations()
    fun snapshot()=synchronized(rows){rows.toList()}
    fun after(index:Int)=snapshot().drop(index).filter {it.first.url.encodedPath!="/x/web-interface/nav"}
    fun only(index:Int,path:String)=after(index).single {it.first.url.encodedPath==path}
    fun form(row:Pair<Request,ByteArray>)=row.second.toString(Charsets.UTF_8).split('&').filter {it.isNotEmpty()}.associate {
        val pair=it.split('=',limit=2);URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")
    }
    fun wire(row:Pair<Request,ByteArray>)=ProtoWire.parseFields(ProtoWire.unframe(row.second))
    val cases=mutableListOf<String>()
    try {
        var start=snapshot().size
        assertEquals(33,ops.getCommentCountForSubject(900,11).getOrThrow())
        assertEquals("900",only(start,"/x/v2/reply/count").first.url.queryParameter("oid"))
        assertEquals(listOf("AUTH"),after(start).map {it.first.header("X-Reply-Fixture-Mode")})
        scenario.set("countError");start=snapshot().size
        assertTrue(ops.getCommentCountForSubject(900,11).isFailure)
        assertEquals(listOf("/x/v2/reply/count"),after(start).map {it.first.url.encodedPath})
        assertEquals(listOf("AUTH"),after(start).map {it.first.header("X-Reply-Fixture-Mode")})
        scenario.set("grpc")
        cases+="raw-count-original-subject"
        val directGrpc=ops.javaClass.getDeclaredField("commentGrpc").apply {isAccessible=true}.get(ops) as DesktopDynamicCommentGrpc
        assertRawReplyFixture(directGrpc.parseMainListReply(rawReplyMainFixture()).replies!!.single())
        directGrpc.getMainList(900,11,3,null).getOrThrow()
        start=snapshot().size
        val data=ops.getCommentsForSubject(900,11,1).getOrThrow()
        assertRawReplyFixture(data.replies!!.single())
        assertEquals(33,data.cursor.allCount);assertEquals("grpc-next",data.grpcNextOffset)
        assertEquals(3,data.collectTopReplies().size);assertTrue(data.control!!.inputDisable)
        assertEquals(listOf(grpcBase+"MainList"),after(start).map {it.first.url.encodedPath})
        val main=wire(only(start,grpcBase+"MainList"))
        assertEquals(900L,main.single {it.number==1}.varint);assertEquals(11L,main.single {it.number==2}.varint)
        assertEquals(3L,main.single {it.number==9}.varint);assertFalse(main.any {it.number==10})
        cases+="grpc-main-request-and-full-raw-parser"
        start=snapshot().size
        val sub=ops.getSortedSubCommentsForSubject(900,11,700,3,"opaque-next",701).getOrThrow()
        assertEquals(700L,sub.root!!.rpid);assertEquals(701L,sub.replies!!.single().rpid)
        assertEquals(5,sub.page.count);assertEquals("detail-next",sub.grpcNextOffset)
        val detail=wire(only(start,grpcBase+"DetailList"))
        assertEquals(701L,detail.single {it.number==4}.varint);assertEquals(3L,detail.single {it.number==7}.varint)
        val cursor=ProtoWire.parseFields(detail.single {it.number==8}.bytes)
        assertEquals(20L,cursor.single {it.number==1}.varint);assertEquals("opaque-next",ProtoWire.stringValue(cursor.single {it.number==2}))
        assertTrue(after(start).all {it.first.url.encodedPath.startsWith(grpcBase)})
        start=snapshot().size;assertTrue(ops.getSortedSubCommentsForSubject(900,11,700,4).isFailure);assertTrue(after(start).isEmpty())
        cases+="sorted-sub-grpc-only-mode-target-cursor"
        start=snapshot().size
        assertEquals("dialog-next",ops.getDialogCommentsForSubject(900,11,700,77,1).getOrThrow().grpcNextOffset)
        assertEquals(77L,wire(only(start,grpcBase+"DialogList")).single {it.number==4}.varint)
        assertEquals("translated & text",ops.translateReply(11,900,701).getOrThrow())
        val translated=wire(only(start,grpcBase+"TranslateReply"))
        assertEquals(11L,translated.single {it.number==1}.varint)
        assertContentEquals(ProtoWire.parseFields(ProtoWire.packedInt64(3,listOf(701))).single().bytes,translated.single {it.number==3}.bytes)
        start=snapshot().size;assertTrue(ops.getDialogCommentsForSubject(900,11,700,77,2).isFailure);assertTrue(after(start).isEmpty())
        cases+="dialog-and-translate-original-wire"
        scenario.set("rest");start=snapshot().size
        val read=ops.getCommentsForSubject(900,17,2,paginationOffset="rest-offset").getOrThrow()
        assertEquals(810L,read.replies!!.single().rpid);assertEquals(2,read.replies!!.single().action);assertEquals("rest-next",read.grpcNextOffset)
        val query=only(start,"/x/v2/reply/wbi/main").first.url
        assertEquals("""{"offset":"rest-offset"}""",query.queryParameter("pagination_str"))
        assertEquals("1315875",query.queryParameter("web_location"));assertEquals("1",query.queryParameter("plat"))
        assertEquals(32,query.queryParameter("w_rid")!!.length);assertNotNull(query.queryParameter("wts"))
        start=snapshot().size;ops.getCommentsForSubject(900,17,2).getOrThrow()
        assertEquals("2",only(start,"/x/v2/reply/wbi/main").first.url.queryParameter("next"))
        cases+="actual-nav-backed-wbi-cursor-and-next"
        scenario.set("authGuest");start=snapshot().size
        assertEquals(810L,ops.getCommentsForSubject(900,17,1).getOrThrow().replies!!.single().rpid)
        assertEquals(listOf("AUTH","AUTH","GUEST","AUTH","GUEST","GUEST"),after(start).map {it.first.header("X-Reply-Fixture-Mode")})
        assertEquals(listOf("/x/v2/reply/wbi/main","/x/v2/reply/main","/x/v2/reply/main","/x/v2/reply","/x/v2/reply","/x/v2/reply/wbi/main"),after(start).map {it.first.url.encodedPath})
        scenario.set("nonWbi");signing=false;start=snapshot().size
        ops.getCommentsForSubject(900,17,1,mode=2).getOrThrow()
        assertEquals(listOf("AUTH","GUEST","AUTH","GUEST"),after(start).map {it.first.header("X-Reply-Fixture-Mode")})
        assertTrue(after(start).filter {it.first.url.encodedPath=="/x/v2/reply"}.all {it.first.url.queryParameter("sort")=="0"})
        signing=true;cases+="auth-guest-main-legacy-candidate-order"
        scenario.set("hotEmpty");start=snapshot().size
        assertEquals(811L,ops.getCommentsForSubject(900,17,1,mode=3).getOrThrow().hots!!.single().rpid)
        assertEquals(listOf("/x/v2/reply/wbi/main","/x/v2/reply/main","/x/v2/reply/wbi/main","/x/v2/reply"),after(start).map {it.first.url.encodedPath})
        scenario.set("restore");start=snapshot().size
        assertEquals(700L,ops.getCommentsForSubject(900,11,1,fallbackOnMissingLocation=true).getOrThrow().replies!!.single().rpid)
        assertEquals(listOf(grpcBase+"MainList","/x/v2/reply/wbi/main"),after(start).map {it.first.url.encodedPath})
        cases+="hot-empty-compat-and-valid-grpc-restoration"
        scenario.set("rest");start=snapshot().size
        ops.addCommentForSubject(900,11,"root").getOrThrow()
        val root=form(only(start,"/x/v2/reply/add"))
        for(key in listOf("root","parent","pictures","sync_to_dynamic"))assertFalse(key in root)
        var recorded=0;start=snapshot().size
        ops.addCommentForSubject(900,11,"reply",700,701,listOf(ReplyPicture("image",0,0,0f)),true) {reply,oid,type,r,p,message,time ->
            assertEquals(9001L,reply.rpid);assertEquals(900L,oid);assertEquals(11,type);assertEquals(700L,r);assertEquals(701L,p);assertEquals("reply",message);assertEquals(10000L,time);recorded++
        }.getOrThrow()
        val submitted=form(only(start,"/x/v2/reply/add"))
        assertEquals("1",submitted["sync_to_dynamic"]);assertEquals("700",submitted["root"]);assertEquals("701",submitted["parent"])
        val picture=Json.parseToJsonElement(submitted.getValue("pictures")).jsonArray.single().jsonObject
        assertEquals(setOf("img_src","img_width","img_height","img_size"),picture.keys);assertEquals(0,picture.getValue("img_width").jsonPrimitive.int);assertEquals(1,recorded)
        cases+="add-root-parent-picture-defaults-sync-owned-record"
        start=snapshot().size
        ops.likeCommentForSubject(900,11,701,true).getOrThrow();ops.likeCommentForSubject(900,11,701,false).getOrThrow()
        ops.hateCommentForSubject(900,11,701,true).getOrThrow();ops.hateCommentForSubject(900,11,701,false).getOrThrow()
        ops.deleteCommentForSubject(900,11,701).getOrThrow()
        ops.setCommentTopForSubject(900,11,701,false).getOrThrow();ops.setCommentTopForSubject(900,11,701,true).getOrThrow()
        ops.reportCommentForSubject(900,11,701,8,"report detail").getOrThrow()
        val mutations=after(start).map(::form)
        assertTrue(mutations.all {it["oid"]=="900"&&it["type"]=="11"&&it["rpid"]=="701"&&it["csrf"]=="fixture-csrf"})
        assertEquals(listOf("1","0","1","0",null,"1","0",null),mutations.map {it["action"]})
        assertEquals("8",mutations.last()["reason"]);assertEquals("report detail",mutations.last()["content"])
        cases+="like-hate-delete-top-report-original-forms"
        start=snapshot().size
        assertEquals(ReplyPicture("https://fixture.invalid/uploaded.png",13,17,1.25f),ops.uploadCommentImage("fixture.png","image/png","image".toByteArray()).getOrThrow())
        val upload=only(start,"/x/dynamic/feed/draw/upload_bfs").second.toString(Charsets.UTF_8)
        for(value in listOf("""name="file_up"; filename="fixture.png"""","image/png","daily","new_dyn","fixture-csrf"))assertTrue(value in upload)
        cases+="sole-existing-editor-multipart-body"
        suspend fun retired(path:String,change:()->Unit,call:suspend()->Unit) {
            val latch=Triple(CountDownLatch(1),CountDownLatch(1),CountDownLatch(1));active.set(latch);hold.set(path to latch)
            val began=snapshot().size;var callback=0;val job=async(Dispatchers.IO) {call();callback++}
            try {
                assertTrue(withContext(Dispatchers.IO){latch.first.await(4,TimeUnit.SECONDS)})
                change();latch.second.countDown()
                try {job.await();fail("Retired operation completed")}catch(_:CancellationException){}
                assertTrue(withContext(Dispatchers.IO){latch.third.await(4,TimeUnit.SECONDS)})
                assertEquals(0,callback);assertEquals(1,after(began).count {it.first.url.encodedPath==path})
            } finally {latch.second.countDown();job.cancelAndJoin();active.set(null)}
        }
        retired("/x/v2/reply/wbi/main",{credentials("same-mid-new-epoch");warmVisitor()}) {ops.getCommentsForSubject(900,17,1).getOrThrow()}
        start=snapshot().size;try {ops.likeCommentForSubject(900,11,701,true);fail("Old owner accepted")}catch(_:CancellationException){}
        assertTrue(after(start).isEmpty());ops=newOperations()
        retired("/x/v2/reply/add",{alive=false}) {ops.addCommentForSubject(900,11,"no late record",onPublishedRecord={_,_,_,_,_,_,_->recorded++}).getOrThrow()}
        assertEquals(1,recorded);cases+="same-mid-epoch-and-closed-owner-no-late-result-or-record"
        alive=true;ops=newOperations()
        val cancelled=Triple(CountDownLatch(1),CountDownLatch(1),CountDownLatch(1))
        active.set(cancelled);hold.set("/x/v2/reply/wbi/main" to cancelled);start=snapshot().size
        var lateCancelledCallback=0
        val cancelledRead=async(Dispatchers.IO) {ops.getCommentsForSubject(900,17,1).getOrThrow();lateCancelledCallback++}
        try {
            assertTrue(withContext(Dispatchers.IO){cancelled.first.await(4,TimeUnit.SECONDS)})
            cancelledRead.cancel();cancelled.second.countDown()
            try {cancelledRead.await();fail("Cancelled read completed")}catch(_:CancellationException){}
            assertTrue(withContext(Dispatchers.IO){cancelled.third.await(4,TimeUnit.SECONDS)})
            cancelledRead.join();assertEquals(0,lateCancelledCallback)
            assertEquals(listOf("/x/v2/reply/wbi/main"),after(start).map {it.first.url.encodedPath})
        } finally {cancelled.second.countDown();cancelledRead.cancelAndJoin();active.set(null)}
        cases+="explicit-cancellation-no-fallback-or-late-callback"
        val result=buildJsonObject {
            put("passed",true);put("actualSockets",false);put("persistentCredentials",false);put("MainIntegration",false)
            put("actualOriginalApiAndModels",true);put("fakeServiceModeLabelsNotCookieBridgeProof",true)
            put("cases",buildJsonArray {cases.forEach {add(it)}})
        }
        if(args.isNotEmpty())Files.writeString(Path.of(args[0]),result.toString()+"\n")
        println("PASS: "+cases.size+" raw reply protocol groups; original API/models/parser; no sockets")
    } finally {
        active.get()?.second?.countDown();hold.get()?.second?.second?.countDown()
        terminal.dispatcher.executorService.shutdownNow();terminal.connectionPool.evictAll()
    }
}

