package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.*
import com.android.purebilibili.feature.message.feed.*
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class Hit(val method:String,val path:String,val query:Map<String,String>,val fields:Map<String,String>,val rawBody:ByteArray)
internal data class Reply(val body:String="""{"code":0,"data":{}}""",val status:Int=200,val headers:Map<String,String> = emptyMap())
private fun decode(raw:String)=raw.split('&').filter { it.isNotBlank() }.associate { item ->
    val parts=item.split('=',limit=2);URLDecoder.decode(parts[0],Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1){""},Charsets.UTF_8)
}
internal class Fixture : AutoCloseable {
    val root=Files.createTempDirectory("bp-msg-owned-")
    val sessions=DesktopSessionStore(root.resolve("synthetic-session.json"),persistent=false)
    val repository=DesktopRepository(sessions)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val retained=AtomicBoolean(true)
    val visible=AtomicBoolean(true)
    val rootLock=Any()
    val hits=ConcurrentLinkedQueue<Hit>()
    val executor=Executors.newCachedThreadPool()
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile var respond:(Hit)->Reply={ default(it) }
    lateinit var admission:DesktopMessagePageAdmission
    lateinit var services:DesktopMessagePageServices
    val hostAttempts=AtomicInteger()
    init {
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only-A","bili_jct" to "synthetic-only-csrf"),AccountSummary(42,"Fixture",""))
        server.executor=executor
        server.createContext("/") { exchange ->
            val body=exchange.requestBody.use { it.readBytes() }
            val hit=Hit(exchange.requestMethod,exchange.requestURI.path,decode(exchange.requestURI.rawQuery.orEmpty()),
                if(exchange.requestHeaders.getFirst("Content-Type")?.startsWith("application/x-www-form-urlencoded")==true)decode(body.toString(Charsets.UTF_8))else emptyMap(),body)
            hits+=hit
            try {
                val reply=respond(hit);val bytes=reply.body.toByteArray()
                exchange.responseHeaders.set("Content-Type","application/json")
                reply.headers.forEach { (name,value)->exchange.responseHeaders.set(name,value) }
                exchange.sendResponseHeaders(reply.status,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
            } catch (_:java.io.IOException) { } finally { exchange.close() }
        }
        server.start()
        val transport=repository.httpClient.newBuilder().retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
            .addInterceptor { chain ->
                val request=chain.request();check(request.url.host in setOf("api.vc.bilibili.com","api.bilibili.com","message.bilibili.com"))
                check(request.url.encodedPath in allowedPaths) { "Unexpected fixture route" };hostAttempts.incrementAndGet()
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.address.port).build()).build())
            }.build()
        admission=DesktopMessagePageAdmission(repository,repository.sessionEpoch,42,scope,retained::get,visible::get,
            { action->synchronized(rootLock) { if(retained.get()) {action();true}else false } },
            { services.userInfo(it) },{ services.videoInfo(it) })
        val store=DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root.resolve("global"))))
        val community=DesktopCommunityRepository(repository,store)
        // Fixture transport ONLY: actual Community factory and protocol/body/settings are unchanged.
        community.javaClass.getDeclaredField("client").apply { isAccessible=true;set(community,transport) }
        services=community.originalMessagePages(admission);admission.requests=services.requests
    }
    suspend fun <T> call(block:suspend()->T):T {
        val result=CompletableDeferred<T>();val job=admission.launch {
            try { result.complete(block()) } catch(e:Throwable) { result.completeExceptionally(e) }
        }
        return try { withTimeout(5000) { result.await() } } finally { job.cancelAndJoin() }
    }
    suspend fun waitFor(predicate:()->Boolean) { withTimeout(5000) { while(!predicate()) delay(10) } }
    fun default(hit:Hit):Reply = when(hit.path) {
        "/session_svr/v1/session_svr/single_unread" -> Reply("""{"code":0,"data":{"follow_unread":2,"dustbin_unread":1}}""")
        "/x/msgfeed/unread" -> Reply("""{"code":0,"data":{"reply":3,"at":2,"like":1,"sys_msg":4}}""")
        "/session_svr/v1/session_svr/get_sessions" -> Reply(sessionsJson(listOf(7L to 700L,8L to 600L),true))
        "/svr_sync/v1/svr_sync/fetch_session_msgs" -> Reply("""{"code":0,"data":{"messages":[{"sender_uid":7,"content":"{\"content\":\"actual\"}","msg_key":9,"msg_seqno":9},{"sender_uid":42,"content":"{\"content\":\"older\"}","msg_key":8,"msg_seqno":8}],"min_seqno":8,"max_seqno":9,"has_more":1,"e_infos":[{"text":"[doge]","url":"","size":1}]}}""")
        "/session_svr/v1/session_svr/session_detail" -> Reply("""{"code":0,"data":{"is_dnd":0,"is_intercept":0}}""")
        "/link_setting/v1/link_setting/get_msg_dnd" -> Reply("""{"code":0,"data":{"uid_settings":[{"id":7,"setting":0}]}}""")
        "/link_setting/v1/link_setting/is_limit" -> Reply("""{"code":0,"data":{"is_limit":false}}""")
        "/link_setting/v1/link_setting/get_session_ss" -> Reply("""{"code":0,"data":{"push_setting":0,"show_push_setting":1,"follow_status":1}}""")
        "/x/msgfeed/reply","/x/msgfeed/at" -> Reply(feedJson(listOf(10),10,900,false))
        "/x/msgfeed/like" -> Reply("""{"code":0,"data":{"latest":{"items":[],"cursor":{"is_end":true}},"total":{"items":[],"cursor":{"is_end":true}}}}""")
        "/x/sys-msg/query_notify_list" -> Reply("""{"code":0,"data":[{"id":4,"cursor":99,"title":"Fixture notice","content":"Actual original content","time_at":"2026-10-02 12:00:00"}]}""")
        "/x/dynamic/feed/draw/upload_bfs" -> Reply("""{"code":0,"data":{"image_url":"https://synthetic.invalid/image.png","image_width":2,"image_height":2,"img_size":9}}""")
        "/web_im/v1/web_im/send_msg" -> Reply("""{"code":0,"data":{"msg_key":101}}""")
        else -> Reply("""{"code":0}""")
    }
    suspend fun shutdown() { retained.set(false);admission.retireAndJoin();scope.cancel();server.stop(0);executor.shutdownNow();repository.httpClient.dispatcher.executorService.shutdownNow();repository.httpClient.connectionPool.evictAll() }
    override fun close() { runBlocking { shutdown() } }
    companion object {
        val allowedPaths=setOf("/session_svr/v1/session_svr/single_unread","/x/msgfeed/unread","/session_svr/v1/session_svr/get_sessions",
            "/svr_sync/v1/svr_sync/fetch_session_msgs","/session_svr/v1/session_svr/session_detail","/link_setting/v1/link_setting/get_msg_dnd",
            "/link_setting/v1/link_setting/is_limit","/link_setting/v1/link_setting/get_session_ss","/x/msgfeed/reply","/x/msgfeed/at","/x/msgfeed/like",
            "/x/sys-msg/query_notify_list","/x/sys-msg/update_cursor","/x/msgfeed/del","/x/msgfeed/notice","/x/dynamic/feed/draw/upload_bfs",
            "/web_im/v1/web_im/send_msg","/session_svr/v1/session_svr/update_ack","/session_svr/v1/session_svr/set_top","/session_svr/v1/session_svr/remove_session",
            "/link_setting/v1/link_setting/set_msg_dnd","/link_setting/v1/link_setting/set_push_ss","/session_svr/v1/session_svr/update_intercept",
            "/session_svr/v1/session_svr/batch_update_dustbin_ack","/session_svr/v1/session_svr/batch_rm_dustbin","/x/web-interface/card","/x/web-interface/view")
    }
}
private fun sessionsJson(items:List<Pair<Long,Long>>,more:Boolean) = buildJsonObject {
    put("code",0);put("data",buildJsonObject { put("has_more",if(more)1 else 0);put("session_list",JsonArray(items.map { (id,time)->buildJsonObject {
        put("talker_id",id);put("session_type",1);put("session_ts",time);put("account_info",buildJsonObject { put("mid",id);put("name","Known$id");put("pic_url","known-$id") })
    } })) })
}.toString()
private fun feedJson(ids:List<Int>,cursor:Long,time:Long,end:Boolean) = buildJsonObject {
    put("code",0);put("data",buildJsonObject {put("cursor",buildJsonObject {put("id",cursor);put("time",time);put("is_end",end)});put("items",JsonArray(ids.map { buildJsonObject { put("id",it);put("reply_time",time) } }))})
}.toString()

fun main(args:Array<String>) = runBlocking {
    saveMessageLoadedIdentity(Path.of(args[0]).parent.resolve("actual-loaded-12-class-identities.json"))
    val results=mutableListOf<String>()
    suspend fun test(name:String,action:suspend(Fixture)->Unit) { Fixture().use { action(it) };results+=name;println("PASS $name") }
    test("original Inbox categories unread and cursor duplicate retry") { f ->
        val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
        check(vm.uiState.value.sessions.map { it.talker_id }==listOf(7L,8L)) { "Original initial sessions state=${vm.uiState.value} hits=${f.hits.map {it.path}}" };check(vm.uiState.value.endTs==599_999_999L)
        check(vm.uiState.value.unreadData?.follow_unread==2);check(MessageSessionCategory.values().map { it.apiSessionType }==listOf(4,9,2,8,3,5,7))
        val pages=AtomicInteger();f.respond={ hit->if(hit.path.endsWith("get_sessions") && hit.query["end_ts"]!="0") {
            if(pages.incrementAndGet()==1) Reply(sessionsJson(listOf(8L to 600L),true)) else Reply(sessionsJson(listOf(9L to 500L),false))
        }else f.default(hit) }
        vm.loadMoreSessions();f.waitFor { !vm.uiState.value.isLoadingMore && vm.uiState.value.sessions.size==3 }
        check(pages.get()==2);check(vm.uiState.value.endTs==499_999_999L)
        check(f.hits.filter { it.path.endsWith("get_sessions") }.all {it.query["size"]=="100" && it.query["unfollow_fold"]=="0" && it.query["group_fold"]=="1"})
    }
    test("late previous category cannot overwrite selected category") { f ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.respond={ hit->if(hit.path.endsWith("get_sessions")) {
            if(hit.query["session_type"]=="4") {entered.countDown();release.await(3,TimeUnit.SECONDS);Reply(sessionsJson(listOf(100L to 200L),false))}
            else Reply(sessionsJson(listOf(200L to 300L),false))
        }else f.default(hit) }
        val vm=InboxViewModel(f.admission);check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
        vm.selectCategory(MessageSessionCategory.Follow);f.waitFor { !vm.uiState.value.isLoading && vm.uiState.value.sessions.firstOrNull()?.talker_id==200L }
        release.countDown();delay(100);check(vm.uiState.value.selectedCategory==MessageSessionCategory.Follow);check(vm.uiState.value.sessions.single().talker_id==200L)
    }
    test("Reply pagination uses exact id time and refresh cancels stale more") { f ->
        val vm=ReplyMeViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.respond={ hit->if(hit.path=="/x/msgfeed/reply") {
            if(hit.query["id"]=="10") {check(hit.query["reply_time"]=="900");entered.countDown();release.await(3,TimeUnit.SECONDS);Reply(feedJson(listOf(11),11,800,true))}
            else Reply(feedJson(listOf(20),20,1000,false))
        }else f.default(hit) }
        vm.loadMore();check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
        vm.refresh();f.waitFor { !vm.uiState.value.isRefreshing && vm.uiState.value.items.singleOrNull()?.id==20L }
        release.countDown();delay(100);check(!vm.uiState.value.isLoadingMore);check(vm.uiState.value.items.single().id==20L)
    }
    test("original four notification VMs and successful system read cursor") { f ->
        val reply=ReplyMeViewModel(f.admission);val at=AtMeViewModel(f.admission);val like=LikeMeViewModel(f.admission);val notice=SystemNoticeViewModel(f.admission)
        f.waitFor { !reply.uiState.value.isLoading && !at.uiState.value.isLoading && !like.uiState.value.isLoading && !notice.uiState.value.isLoading }
        check(notice.uiState.value.error==null) { "System notice actual error=${notice.uiState.value.error}" };check(notice.uiState.value.items.single().cursor==99L)
        f.waitFor { f.hits.any { it.path.endsWith("update_cursor") } };val read=f.hits.first { it.path.endsWith("update_cursor") }
        check(read.query["cursor"]=="99" && read.query["csrf"]=="synthetic-only-csrf")
    }
    test("original feed delete and notice exact parameters") { f ->
        f.call { f.admission.requests.deleteFeedItem(3,91).getOrThrow();f.admission.requests.setLikeFeedNotice(92,true).getOrThrow() }
        val delete=f.hits.first { it.path=="/x/msgfeed/del" };check(delete.fields["tp"]=="3" && delete.fields["id"]=="91")
        val notice=f.hits.first { it.path=="/x/msgfeed/notice" };check(notice.fields["notice_state"]=="0")
        check(listOf(delete,notice).all { it.fields["csrf"]=="synthetic-only-csrf" && it.fields["csrf_token"]=="synthetic-only-csrf" })
    }
    test("Chat original ordering emotes control and actual ACK") { f ->
        val vm=ChatViewModel(7,1,f.admission);f.waitFor { !vm.uiState.value.isLoading && !vm.uiState.value.isSessionControlLoading }
        check(vm.uiState.value.messages.map { it.msg_key }==listOf(8L,9L));check(vm.uiState.value.emoteInfos.single().text=="[doge]")
        check(vm.currentUserMid==42L);check(vm.uiState.value.sessionControlInfo.isDnd==false)
        f.waitFor { f.hits.any { it.path.endsWith("update_ack") } };val ack=f.hits.first { it.path.endsWith("update_ack") }
        check(ack.fields["ack_seqno"]=="9" && ack.fields["talker_id"]=="7" && ack.fields["session_type"]=="1")
    }
    test("original group DND top push intercept and batch parameters") { f ->
        f.call {
            f.admission.requests.setSessionDnd(99,2,true).getOrThrow();f.admission.requests.setSessionPushMuted(7,true).getOrThrow()
            f.admission.requests.setSessionIntercept(7,true).getOrThrow();f.admission.requests.setSessionTop(7,1,true).getOrThrow()
            f.admission.requests.markDustbinRead().getOrThrow();f.admission.requests.clearDustbinSessions().getOrThrow()
        }
        val dnd=f.hits.first {it.path.endsWith("set_msg_dnd")};check(dnd.fields["uid"]=="42" && dnd.fields["dnd_group_id"]=="99" && "dnd_uid" !in dnd.fields && dnd.fields["setting"]=="1")
        check(f.hits.first {it.path.endsWith("set_push_ss")}.fields["talker_uid"]=="7")
        check(f.hits.first {it.path.endsWith("update_intercept")}.fields["status"]=="1")
        check(f.hits.first {it.path.endsWith("set_top")}.fields["op_type"]=="0")
        check(f.hits.count {it.path.endsWith("batch_update_dustbin_ack") || it.path.endsWith("batch_rm_dustbin")}==2)
        check(f.hits.filter {it.method=="POST"}.all {it.fields["csrf"]=="synthetic-only-csrf" && it.fields["csrf_token"]=="synthetic-only-csrf"})
    }
    test("original optimistic top failure refreshes authoritative sessions") { f ->
        val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading };val before=f.hits.size
        f.respond={hit->if(hit.path.endsWith("set_top"))Reply("""{"code":-400,"message":"controlled rejection"}""")else f.default(hit)}
        vm.toggleTop(vm.uiState.value.sessions.first());f.waitFor {f.hits.size>before && f.hits.count {it.path.endsWith("get_sessions")}>=2 && !vm.uiState.value.isRefreshing}
        check(vm.uiState.value.sessions.all {it.top_ts==0L});check(f.hits.count {it.path.endsWith("set_top")}==1)
    }
    test("send preserves text CR TAB and withdrawal payload model") { f ->
        f.call { f.admission.requests.sendTextMessage(7," hi\r\tquoted\" ",2).getOrThrow();f.admission.requests.withdrawMessage(7,101,2).getOrThrow() }
        val messages=f.hits.filter { it.path.endsWith("send_msg") };check(messages.size==2)
        val text=messages.first().fields;check(text["msg[receiver_type]"]=="2" && text["msg[sender_uid]"]=="42")
        check(Json.parseToJsonElement(text.getValue("msg[content]")).jsonObject.getValue("content").jsonPrimitive.content==" hi\r\tquoted\" ")
        check(messages.last().fields["msg[msg_type]"]=="5");check(messages.last().fields["msg[content]"]!!.contains("101"))
    }
    test("HTTP503 RetryAfter0 mutation does not replay") { f ->
        f.respond={ hit->if(hit.path.endsWith("send_msg"))Reply("""{"code":-1}""",503,mapOf("Retry-After" to "0"))else f.default(hit) }
        check(f.call { f.admission.requests.sendTextMessage(7,"user submit") }.isFailure)
        check(f.hits.count { it.path.endsWith("send_msg") }==1)
    }
    test("image upload validates original cap and sends real uploaded metadata") { f ->
        val vm=ChatViewModel(7,1,f.admission);f.waitFor { !vm.uiState.value.isLoading }
        val image=f.root.resolve("selected.png");Files.write(image,byteArrayOf(1,2,3,4))
        vm.sendImageMessage(DesktopMessageLocalImage(image,"image/png"));f.waitFor { f.hits.any { it.path.endsWith("send_msg") } && !vm.uiState.value.isUploadingImage }
        val upload=f.hits.first { it.path.endsWith("upload_bfs") };check(upload.rawBody.toString(Charsets.ISO_8859_1).contains("selected.png"))
        val sent=f.hits.first { it.path.endsWith("send_msg") };check(sent.fields["msg[msg_type]"]=="2")
        check(sent.fields["msg[content]"]!!.contains("synthetic.invalid/image.png"));check(sent.fields["msg[content]"]!!.contains("\"width\":2"))
        // The successful send deliberately refreshes/ACKs the chat. The size cap must
        // prevent another upload/send, while those original read requests may finish.
        val before=f.hits.count { it.path.endsWith("upload_bfs") || it.path.endsWith("send_msg") }
        Files.write(image,ByteArray(15*1024*1024+1));vm.sendImageMessage(DesktopMessageLocalImage(image,"image/png"))
        f.waitFor { vm.uiState.value.sendError!=null };check(vm.uiState.value.sendError!!.contains("15MB"))
        check(f.hits.count { it.path.endsWith("upload_bfs") || it.path.endsWith("send_msg") }==before)
    }
    test("same MID new epoch rejects old sender and stale UI") { f ->
        val oldState=f.admission.stateFlow(1);val entered=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>()
        val old=f.admission.launch { entered.complete(Unit);withContext(NonCancellable){released.await()};oldState.value=2 }
        entered.await();f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only-B","bili_jct" to "different"),AccountSummary(42,"Fixture",""));released.complete(Unit);old.join()
        check(oldState.value==1);val before=f.hits.size
        val oldCall=f.admission.launch { f.admission.requests.sendTextMessage(7,"must not send") };oldCall.join();check(f.hits.size==before)
    }
    test("caller cancellation stops request and post-IO publication") { f ->
        val state=f.admission.stateFlow(0);val entered=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>()
        val job=f.admission.launch { entered.complete(Unit);withContext(NonCancellable){released.await()};state.value=9 }
        entered.await();job.cancel();released.complete(Unit);job.join();check(state.value==0)
        f.retained.set(false);check(f.admission.retireAndJoin());check(!f.admission.isOwned())
    }
    test("covered entry reads retained but covered mutation cannot submit") { f ->
        f.visible.set(false);val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
        check(vm.uiState.value.sessions.isNotEmpty());val before=f.hits.size
        vm.removeSession(vm.uiState.value.sessions.first());delay(100);check(f.hits.size==before)
        f.visible.set(true);vm.removeSession(vm.uiState.value.sessions.first());f.waitFor { f.hits.any { it.path.endsWith("remove_session") } }
    }
    val report=buildJsonObject { put("passed",true);put("actualMethods",results.size);put("checks",JsonArray(results.map(::JsonPrimitive)));put("actualCommunityFactory",true);put("fixtureOnlyTransportFieldOverride",true);put("productionCredentialRead",false);put("rootAccepted",false) }
    Files.writeString(Path.of(args[0]),report.toString());println("PASS ${results.size} actual original protocol/VM fixtures")
}
