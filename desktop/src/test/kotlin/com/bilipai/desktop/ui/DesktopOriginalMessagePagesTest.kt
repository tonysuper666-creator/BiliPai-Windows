package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.*
import com.android.purebilibili.feature.message.feed.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.data.repository.ImFields
import com.android.purebilibili.data.repository.MessageImRepository
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlin.test.Test
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

private data class MessagePageTestHit(val method:String,val path:String,val query:Map<String,String>,val fields:Map<String,String>,val rawBody:ByteArray)
private data class MessagePageTestReply(val body:String="""{"code":0,"data":{}}""",val status:Int=200,val headers:Map<String,String> = emptyMap(),
    val bytes:ByteArray?=null)
private fun decode(raw:String)=raw.split('&').filter { it.isNotBlank() }.associate { item ->
    val parts=item.split('=',limit=2);URLDecoder.decode(parts[0],Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1){""},Charsets.UTF_8)
}
private class MessagePageTestFixture : AutoCloseable {
    val root=Files.createTempDirectory("bp-msg-owned-")
    val sessions=DesktopSessionStore(root.resolve("synthetic-session.json"),persistent=false)
    val repository=DesktopRepository(sessions)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val retained=AtomicBoolean(true)
    val visible=AtomicBoolean(true)
    val rootLock=Any()
    val canBeginEditor=AtomicBoolean(true)
    val editorActivity=AtomicInteger()
    @Volatile var onEditorActivity:()->Unit={}
    val pluginContext=DesktopPluginContext(DesktopPluginStore(root.resolve("global")))
    lateinit var community:DesktopCommunityRepository
    private val pageOwners=mutableListOf<DesktopOriginalMessagePageOwner>()
    val hits=ConcurrentLinkedQueue<MessagePageTestHit>()
    val executor=Executors.newCachedThreadPool()
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile var respond:(MessagePageTestHit)->MessagePageTestReply={ default(it) }
    lateinit var admission:DesktopMessagePageAdmission
    lateinit var services:DesktopMessagePageServices
    val hostAttempts=AtomicInteger()
    init {
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only-A","bili_jct" to "synthetic-only-csrf","buvid3" to "synthetic-only-buvid"),AccountSummary(42,"MessagePageTestFixture",""),imported=true)
        server.executor=executor
        server.createContext("/") { exchange ->
            val body=exchange.requestBody.use { it.readBytes() }
            val hit=MessagePageTestHit(exchange.requestMethod,exchange.requestURI.path,decode(exchange.requestURI.rawQuery.orEmpty()),
                if(exchange.requestHeaders.getFirst("Content-Type")?.startsWith("application/x-www-form-urlencoded")==true)decode(body.toString(Charsets.UTF_8))else emptyMap(),body)
            hits+=hit
            try {
                val reply=respond(hit);val bytes=reply.bytes ?: reply.body.toByteArray()
                exchange.responseHeaders.set("Content-Type","application/json")
                reply.headers.forEach { (name,value)->exchange.responseHeaders.set(name,value) }
                exchange.sendResponseHeaders(reply.status,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
            } catch (_:java.io.IOException) { } finally { exchange.close() }
        }
        server.start()
        val transport=repository.httpClient.newBuilder().retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
            // Keep the API origin for the production credential gate; only DNS/port use loopback.
            .dns { host ->
                check(host in hosts)
                listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }
            .addInterceptor { chain ->
                val request=chain.request();check(request.url.host in hosts)
                check(request.url.encodedPath in allowedPaths) { "Unexpected fixture route" };hostAttempts.incrementAndGet()
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").port(server.address.port).build()).build())
            }.build()
        admission=DesktopMessagePageAdmission(repository,repository.sessionEpoch,42,scope,retained::get,visible::get,
            { action->synchronized(rootLock) { if(retained.get()) {action();true}else false } },
            { services.userInfo(it) },{ services.videoInfo(it) },canBeginEditor::get,{editorActivity.incrementAndGet();onEditorActivity()})
        val store=DesktopBlockedUpStore(pluginContext)
        community=DesktopCommunityRepository(repository,store)
        // MessagePageTestFixture transport ONLY: actual Community factory and protocol/body/settings are unchanged.
        community.javaClass.getDeclaredField("client").apply { isAccessible=true;set(community,transport) }
        services=community.originalMessagePages(admission);admission.requests=services.requests;admission.management=services.management
        admission.im=services.im
    }
    suspend fun pageOwner():DesktopOriginalMessagePageOwner {
        val lifecycleOwner=object:androidx.lifecycle.LifecycleOwner {
            override val lifecycle:androidx.lifecycle.Lifecycle=androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply {
                currentState=androidx.lifecycle.Lifecycle.State.RESUMED
            }
        }
        // Only unused rendering ports reject calls. The actual message owner, repository,
        // account admission, settings projection and loopback request view remain real.
        val home=DesktopHomeEnvironment(
            DesktopOriginalHomePreferences.create(pluginContext.store,scope,false,{false}),pluginContext,lifecycleOwner,
            kotlinx.coroutines.flow.MutableStateFlow(false),unusedMessageUiPort(DesktopHomeAnalyticsPort::class.java),
            { DesktopOriginalHomeGlobalNamespace(pluginContext.store,it) },kotlinx.coroutines.flow.MutableStateFlow(null),
            {_,_->}, {}, {_,_->}, unusedMessageUiPort(DesktopHomeEmbeddedPages::class.java),{_,_->false},{},
            unusedMessageUiPort(DesktopHomeOverlayPorts::class.java))
        return DesktopOriginalMessagePageOwner(repository,community,repository.sessionEpoch,42,scope,
            retained::get,visible::get,{action->synchronized(rootLock){if(retained.get()){action();true}else false}},
            home,{},false,canBeginEditor::get,{editorActivity.incrementAndGet();onEditorActivity()}).also(pageOwners::add)
    }
    suspend fun <T> call(block:suspend()->T):T {
        val result=CompletableDeferred<T>();val job=admission.launch {
            try { result.complete(block()) } catch(e:Throwable) { result.completeExceptionally(e) }
        }
        return try { withTimeout(5000) { result.await() } } finally { job.cancelAndJoin() }
    }
    suspend fun waitFor(predicate:()->Boolean) { withTimeout(5000) { while(!predicate()) delay(10) } }
    fun default(hit:MessagePageTestHit):MessagePageTestReply = when(hit.path) {
        "/session_svr/v1/session_svr/single_unread" -> MessagePageTestReply("""{"code":0,"data":{"follow_unread":2,"dustbin_unread":1}}""")
        "/x/msgfeed/unread" -> MessagePageTestReply("""{"code":0,"data":{"reply":3,"at":2,"like":1,"sys_msg":4}}""")
        "/session_svr/v1/session_svr/get_sessions" -> MessagePageTestReply(sessionsJson(listOf(7L to 700L,8L to 600L),true))
        "/svr_sync/v1/svr_sync/fetch_session_msgs" -> MessagePageTestReply("""{"code":0,"data":{"messages":[{"sender_uid":7,"content":"{\"content\":\"actual\"}","msg_key":9,"msg_seqno":9},{"sender_uid":42,"content":"{\"content\":\"older\"}","msg_key":8,"msg_seqno":8}],"min_seqno":8,"max_seqno":9,"has_more":1,"e_infos":[{"text":"[doge]","url":"","size":1}]}}""")
        "/session_svr/v1/session_svr/session_detail" -> MessagePageTestReply("""{"code":0,"data":{"is_dnd":0,"is_intercept":0}}""")
        "/link_setting/v1/link_setting/get_msg_dnd" -> MessagePageTestReply("""{"code":0,"data":{"uid_settings":[{"id":7,"setting":0}]}}""")
        "/link_setting/v1/link_setting/is_limit" -> MessagePageTestReply("""{"code":0,"data":{"is_limit":false}}""")
        "/link_setting/v1/link_setting/get_session_ss" -> MessagePageTestReply("""{"code":0,"data":{"push_setting":0,"show_push_setting":1,"follow_status":1}}""")
        "/x/msgfeed/reply","/x/msgfeed/at" -> MessagePageTestReply(feedJson(listOf(10),10,900,false))
        "/x/msgfeed/like" -> MessagePageTestReply("""{"code":0,"data":{"latest":{"items":[],"cursor":{"is_end":true}},"total":{"items":[],"cursor":{"is_end":true}}}}""")
        "/x/sys-msg/query_notify_list" -> MessagePageTestReply("""{"code":0,"data":[{"id":4,"cursor":99,"title":"MessagePageTestFixture notice","content":"Actual original content","time_at":"2026-10-02 12:00:00"}]}""")
        "/x/dynamic/feed/draw/upload_bfs" -> MessagePageTestReply("""{"code":0,"data":{"image_url":"https://synthetic.invalid/image.png","image_width":2,"image_height":2,"img_size":9}}""")
        "/web_im/v1/web_im/send_msg" -> MessagePageTestReply("""{"code":0,"data":{"msg_key":101}}""")
        "/bilibili.app.im.v1.im/SessionMain" -> grpcReply(imPage(listOf(imSession(7,"Known7",700),imSession(8,"Known8",600)),imCursor(1,true)))
        in imPaths -> grpcReply(byteArrayOf())
        else -> MessagePageTestReply("""{"code":0}""")
    }
    suspend fun shutdown() { retained.set(false);pageOwners.forEach { it.closeAndJoin() };admission.retireAndJoin();scope.cancel();server.stop(0);executor.shutdownNow();repository.httpClient.dispatcher.executorService.shutdownNow();repository.httpClient.connectionPool.evictAll() }
    override fun close() { runBlocking { shutdown() } }
    companion object {
        // The IM gRPC host keeps its production origin too (upstream MessageImRepository, bilibili.app.im.v1.im).
        val hosts=setOf("api.vc.bilibili.com","api.bilibili.com","message.bilibili.com","app.bilibili.com")
        val imPaths=listOf("SessionMain","SessionSecondary","ClearUnread","DeleteSessionList","PinSession","UnpinSession",
            "GetImSettings","SetImSettings","KeywordBlockingList","KeywordBlockingAdd","KeywordBlockingDelete").map { "/bilibili.app.im.v1.im/$it" }.toSet()
        val allowedPaths=imPaths+setOf("/x/emote/user/panel/web","/x/emote/package","/session_svr/v1/session_svr/single_unread","/x/msgfeed/unread","/session_svr/v1/session_svr/get_sessions",
            "/svr_sync/v1/svr_sync/fetch_session_msgs","/session_svr/v1/session_svr/session_detail","/link_setting/v1/link_setting/get_msg_dnd",
            "/link_setting/v1/link_setting/is_limit","/link_setting/v1/link_setting/get_session_ss","/x/msgfeed/reply","/x/msgfeed/at","/x/msgfeed/like",
            "/x/sys-msg/query_notify_list","/x/sys-msg/update_cursor","/x/msgfeed/del","/x/msgfeed/notice","/x/dynamic/feed/draw/upload_bfs",
            "/web_im/v1/web_im/send_msg","/session_svr/v1/session_svr/update_ack","/session_svr/v1/session_svr/set_top","/session_svr/v1/session_svr/remove_session",
            "/link_setting/v1/link_setting/set_msg_dnd","/link_setting/v1/link_setting/set_push_ss","/session_svr/v1/session_svr/update_intercept",
            "/session_svr/v1/session_svr/batch_update_dustbin_ack","/session_svr/v1/session_svr/batch_rm_dustbin","/x/web-interface/card","/x/web-interface/view",
            "/x/msgfeed/like_detail","/x/sys-msg/del_notify_list")
    }
}
private fun sessionsJson(items:List<Pair<Long,Long>>,more:Boolean) = buildJsonObject {
    put("code",0);put("data",buildJsonObject { put("has_more",if(more)1 else 0);put("session_list",JsonArray(items.map { (id,time)->buildJsonObject {
        put("talker_id",id);put("session_type",1);put("session_ts",time);put("account_info",buildJsonObject { put("mid",id);put("name","Known$id");put("pic_url","known-$id") })
    } })) })
}.toString()
/** gRPC reply: one uncompressed frame, status in the HTTP headers (BiliGrpcClient reads either headers or trailers). */
private fun grpcReply(message:ByteArray,status:String="0",grpcMessage:String="") = MessagePageTestReply(
    bytes=ProtoWire.frame(message,gzipMinLength=Int.MAX_VALUE),
    headers=mapOf("Content-Type" to "application/grpc","grpc-status" to status)+(if(grpcMessage.isEmpty()) emptyMap() else mapOf("grpc-message" to grpcMessage)))
/** The request message of a gRPC hit (BiliGrpcClient frames, and gzips longer messages). */
private fun grpcRequest(hit:MessagePageTestHit)=ImFields(ProtoWire.unframe(hit.rawBody))
/** An IM session (upstream MessageImRepository.parseSession): private talker or a fold folder, name, time, unread, seqno. */
private fun imSession(talker:Long,name:String,time:Long,fold:Int=0,unread:Long=0,maxSeqno:Long=0)=ProtoWire.message(
    ProtoWire.bytes(1,if(fold>0) ProtoWire.bytes(3,ProtoWire.int64(1,fold.toLong())) else ProtoWire.bytes(1,ProtoWire.int64(1,talker))),
    ProtoWire.bytes(2,ProtoWire.string(1,name)),
    ProtoWire.bytes(3,if(unread>0) ProtoWire.message(ProtoWire.int32(1,2),ProtoWire.int64(2,unread),ProtoWire.string(3,unread.toString())) else byteArrayOf()),
    ProtoWire.int64(5,time),ProtoWire.int64(7,maxSeqno))
private fun imCursor(value:Long,more:Boolean)=ProtoWire.message(ProtoWire.int64(1,value),ProtoWire.bool(2,more))
/** SessionMain lists sessions in field 5; a folder page (SessionSecondary) in field 3. */
private fun imPage(sessions:List<ByteArray>,cursor:ByteArray,folder:Boolean=false)=ProtoWire.message(
    ProtoWire.bytes(1,cursor),*sessions.map { ProtoWire.bytes(if(folder)3 else 5,it) }.toTypedArray())
private fun feedJson(ids:List<Int>,cursor:Long,time:Long,end:Boolean) = buildJsonObject {
    put("code",0);put("data",buildJsonObject {put("cursor",buildJsonObject {put("id",cursor);put("time",time);put("is_end",end)});put("items",JsonArray(ids.map { buildJsonObject { put("id",it);put("reply_time",time) } }))})
}.toString()

private fun chatJson(ids: LongRange, more: Boolean, withdrawn: Long = 0) = buildJsonObject {
    put("code", 0)
    put("data", buildJsonObject {
        put("min_seqno", ids.first); put("max_seqno", ids.last); put("has_more", if (more) 1 else 0)
        put("messages", JsonArray(ids.reversed().map { id -> buildJsonObject {
            put("msg_key", id); put("msg_seqno", id); put("sender_uid", 7); put("receiver_id", 42)
            put("timestamp", id); put("content", "{\"content\":\"fixture $id\"}")
            put("msg_status", if (id == withdrawn) 1 else 0)
        } }))
    })
}.toString()

class DesktopV029ChatTimelineIntegrationTest {
    private suspend fun ready(f: MessagePageTestFixture, vm: ChatViewModel) {
        val field = vm.javaClass.getDeclaredField("latestMessagesJob").apply { isAccessible = true }
        f.waitFor { vm.uiState.value.messagesLoaded && (field.get(vm) as? Job)?.isActive != true }
    }

    @Test fun actualRefreshFillsGapAndPreservesPreviouslyLoadedHistory(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val resumed = AtomicBoolean(false)
            f.respond = { hit -> if (hit.path.endsWith("fetch_session_msgs")) {
                val cursor = hit.query["end_seqno"]?.toLongOrNull() ?: 0L
                MessagePageTestReply(when {
                    cursor == 40L -> chatJson(1L..40L, false)
                    cursor == 70L -> chatJson(40L..69L, true, 50)
                    resumed.get() -> chatJson(70L..99L, true)
                    else -> chatJson(40L..69L, true)
                })
            } else f.default(hit) }
            val vm = ChatViewModel(7, 1, f.admission); ready(f, vm)
            vm.loadMoreMessages(); f.waitFor { vm.uiState.value.messages.size == 69 && !vm.uiState.value.isLoadingMore }
            check(vm.uiState.value.minSeqno == 1L && !vm.uiState.value.hasMore)
            resumed.set(true); vm.refreshMessages()
            check(vm.uiState.value.messages.map { it.msg_seqno } == (1L..99L).toList())
            check(vm.uiState.value.messages.single { it.msg_seqno == 50L }.msg_status == 1)
            check(vm.uiState.value.minSeqno == 1L && !vm.uiState.value.hasMore)
            check(f.hits.count { it.query["end_seqno"] == "70" } == 1)
        }
    }

    @Test fun actualRefreshErrorRetainsContentAndRetryClearsError(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm = ChatViewModel(7, 1, f.admission); ready(f, vm)
            val before = vm.uiState.value.messages
            f.respond = { hit -> if (hit.path.endsWith("fetch_session_msgs"))
                MessagePageTestReply("""{"code":-400,"message":"controlled refresh failure"}""") else f.default(hit) }
            vm.refreshMessages()
            check(vm.uiState.value.messages === before && vm.uiState.value.error == null)
            check(vm.uiState.value.refreshError != null)
            f.respond = { f.default(it) }; vm.refreshMessages()
            check(vm.uiState.value.refreshError == null && vm.uiState.value.messages.map { it.msg_key } == listOf(8L, 9L))
        }
    }

    @Test fun visibleRefreshCancellationRejectsLateReply(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm = ChatViewModel(7, 1, f.admission); ready(f, vm)
            val before = vm.uiState.value.messages
            val entered = CompletableDeferred<Unit>(); val released = CountDownLatch(1)
            f.respond = { hit -> if (hit.path.endsWith("fetch_session_msgs")) {
                entered.complete(Unit); released.await(3, TimeUnit.SECONDS); MessagePageTestReply(chatJson(8L..10L, false))
            } else f.default(hit) }
            val refresh = launch { vm.refreshMessages() }
            withTimeout(5_000) { entered.await() }; refresh.cancelAndJoin(); released.countDown()
            check(vm.uiState.value.messages === before && vm.uiState.value.refreshError == null)
        }
    }

    @Test fun accountReplacementRetiresActualInFlightRefresh(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm = ChatViewModel(7, 1, f.admission); ready(f, vm)
            val before = vm.uiState.value.messages
            val entered = CompletableDeferred<Unit>(); val released = CountDownLatch(1)
            f.respond = { hit -> if (hit.path.endsWith("fetch_session_msgs")) {
                entered.complete(Unit); released.await(3, TimeUnit.SECONDS); MessagePageTestReply(chatJson(8L..10L, false))
            } else f.default(hit) }
            val refresh = launch { vm.refreshMessages() }
            withTimeout(5_000) { entered.await() }
            f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-replaced", "bili_jct" to "synthetic-replaced"), AccountSummary(42, "Fixture", ""))
            released.countDown(); withTimeout(5_000) { refresh.join() }
            check(vm.uiState.value.messages === before && vm.uiState.value.refreshError == null)
            check(refresh.isCancelled)
        }
    }

    @Test fun rejectedSendRetainsDraftAcknowledgementAndSuccessCanBeConsumed(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm = ChatViewModel(7, 1, f.admission); ready(f, vm)
            f.respond = { hit -> if (hit.path.endsWith("send_msg"))
                MessagePageTestReply("""{"code":-400,"message":"controlled send failure"}""") else f.default(hit) }
            vm.sendMessage("keep draft"); f.waitFor { !vm.uiState.value.isSending && vm.uiState.value.sendError != null }
            check(vm.uiState.value.sentText == null && vm.uiState.value.scrollToLatestVersion == 0L)
            f.respond = { f.default(it) }; vm.sendMessage("keep draft")
            f.waitFor { vm.uiState.value.sentText == "keep draft" && !vm.uiState.value.isSending }
            vm.consumeSentText("different draft"); check(vm.uiState.value.sentText == "keep draft")
            vm.consumeSentText("keep draft"); check(vm.uiState.value.sentText == null)
        }
    }

    @Test fun coveredPageCannotSetPendingOrSendAndRapidVisibleCallsSubmitOnce(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm = ChatViewModel(7, 1, f.admission); ready(f, vm)
            f.visible.set(false); vm.sendMessage("hidden")
            check(!vm.uiState.value.isSending && f.hits.none { it.path.endsWith("send_msg") })
            f.visible.set(true)
            val entered = CompletableDeferred<Unit>(); val released = CountDownLatch(1)
            f.respond = { hit -> if (hit.path.endsWith("send_msg")) {
                entered.complete(Unit); released.await(3, TimeUnit.SECONDS); f.default(hit)
            } else f.default(hit) }
            vm.sendMessage("visible"); vm.sendMessage("visible")
            withTimeout(5_000) { entered.await() }; vm.sendMessage("visible"); released.countDown()
            f.waitFor { vm.uiState.value.sentText == "visible" && !vm.uiState.value.isSending }
            check(f.hits.count { it.path.endsWith("send_msg") } == 1)
        }
    }

    @Test fun externalRefreshCallerRetiresNonCancellableFinalPublication(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val state = f.admission.stateFlow(0)
            val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
            val caller = launch {
                f.admission.awaitRead("chat-refresh") {
                    entered.complete(Unit)
                    withContext(NonCancellable) { released.await(); state.value = 1 }
                }
            }
            entered.await(); caller.cancel(); released.complete(Unit)
            withTimeout(5_000) { caller.join() }
            check(state.value == 0 && caller.isCancelled)
        }
    }
}


class DesktopOriginalMessagePagesTest {
    // TON-1120: upstream's inbox lists sessions over IM gRPC (SessionMain/SessionSecondary, opaque pagination, folder
    // pages) instead of the web get_sessions categories; these two replace the category tests with the same behaviour.
    @Test fun imInboxPagesByOpaquePaginationAndStopsWhenItDoesNotAdvance(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
            check(vm.uiState.value.sessions.map { it.talker_id }==listOf(7L,8L)) { "Initial IM sessions state=${vm.uiState.value} hits=${f.hits.map {it.path}}" }
            check(vm.uiState.value.hasMore && vm.uiState.value.feedUnreadData?.reply==3)
            check(grpcRequest(f.hits.single { it.path.endsWith("/SessionMain") }).bytes(2).isEmpty()) { "The first page sends no pagination" }
            val pages=ConcurrentLinkedQueue<ByteArray>()
            f.respond={ hit->if(hit.path.endsWith("/SessionMain")) {
                val cursor=grpcRequest(hit).bytes(2);pages+=cursor
                // A duplicate session and a new one, then a page whose pagination does not advance.
                if(cursor.contentEquals(imCursor(1,true))) grpcReply(imPage(listOf(imSession(8,"Known8",600),imSession(9,"Known9",500)),imCursor(2,true)))
                else grpcReply(imPage(emptyList(),imCursor(2,true)))
            }else f.default(hit) }
            vm.loadMoreSessions();f.waitFor { !vm.uiState.value.isLoadingMore && vm.uiState.value.sessions.size==3 }
            check(vm.uiState.value.sessions.map { it.talker_id }==listOf(7L,8L,9L) && vm.uiState.value.hasMore)
            vm.loadMoreSessions();f.waitFor { !vm.uiState.value.isLoadingMore && pages.size==2 }
            check(pages.map { ImFields(it).number(1) }==listOf(1L,2L)) { "More sends the server's opaque pagination back unchanged" }
            check(!vm.uiState.value.hasMore && vm.uiState.value.sessions.size==3) { "A page that does not advance ends the list" }
        }
    }
    @Test fun lateMainPageCannotOverwriteAnOpenedImFolder(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            f.respond={ hit->if(hit.path.endsWith("/SessionMain")) grpcReply(imPage(listOf(imSession(0,"拦截",800,fold=4),imSession(7,"Known7",700)),imCursor(1,false)))
                else f.default(hit) }
            val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading && vm.uiState.value.sessions.size==2 }
            val folder=vm.uiState.value.sessions.first();check(vm.entry(folder)?.folderPage==2)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.respond={ hit->when {
                hit.path.endsWith("/SessionMain") -> {entered.countDown();release.await(3,TimeUnit.SECONDS);grpcReply(imPage(listOf(imSession(100,"Late",900)),imCursor(1,false)))}
                hit.path.endsWith("/SessionSecondary") -> grpcReply(imPage(listOf(imSession(200,"Folded",300)),imCursor(1,false),folder=true))
                else -> f.default(hit)
            } }
            vm.refresh();check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
            check(vm.openFolder(folder));f.waitFor { !vm.uiState.value.isLoading && vm.uiState.value.sessions.firstOrNull()?.talker_id==200L }
            release.countDown();delay(100)
            check(vm.uiState.value.pageType==2 && vm.uiState.value.title=="拦截" && vm.uiState.value.sessions.single().talker_id==200L)
            check(grpcRequest(f.hits.single { it.path.endsWith("/SessionSecondary") }).number(3)==2L) { "The folder page asks for its own page type" }
            check(vm.back() && vm.uiState.value.pageType==1)
        }
    }
    @Test fun replyPaginationUsesExactIdTimeAndRefreshCancelsStaleMore(): Unit = runBlocking {
        // Reply pagination uses exact id time and refresh cancels stale more
        MessagePageTestFixture().use { f ->
        val vm=ReplyMeViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.respond={ hit->if(hit.path=="/x/msgfeed/reply") {
            if(hit.query["id"]=="10") {check(hit.query["reply_time"]=="900");entered.countDown();release.await(3,TimeUnit.SECONDS);MessagePageTestReply(feedJson(listOf(11),11,800,true))}
            else MessagePageTestReply(feedJson(listOf(20),20,1000,false))
        }else f.default(hit) }
        vm.loadMore();check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
        vm.refresh();f.waitFor { !vm.uiState.value.isRefreshing && vm.uiState.value.items.singleOrNull()?.id==20L }
        release.countDown();delay(100);check(!vm.uiState.value.isLoadingMore);check(vm.uiState.value.items.single().id==20L)
            }
    }
    @Test fun originalFourNotificationVMsAndSuccessfulSystemReadCursor(): Unit = runBlocking {
        // original four notification VMs and successful system read cursor
        MessagePageTestFixture().use { f ->
        val reply=ReplyMeViewModel(f.admission);val at=AtMeViewModel(f.admission);val like=LikeMeViewModel(f.admission);val notice=SystemNoticeViewModel(f.admission)
        f.waitFor { !reply.uiState.value.isLoading && !at.uiState.value.isLoading && !like.uiState.value.isLoading && !notice.uiState.value.isLoading }
        check(notice.uiState.value.error==null) { "System notice actual error=${notice.uiState.value.error}" };check(notice.uiState.value.items.single().cursor==99L)
        f.waitFor { f.hits.any { it.path.endsWith("update_cursor") } };val read=f.hits.first { it.path.endsWith("update_cursor") }
        check(read.query["cursor"]=="99" && read.query["csrf"]=="synthetic-only-csrf")
            }
    }
    @Test fun originalFeedDeleteAndNoticeExactParameters(): Unit = runBlocking {
        // original feed delete and notice exact parameters
        MessagePageTestFixture().use { f ->
        f.call { f.admission.requests.deleteFeedItem(3,91).getOrThrow();f.admission.requests.setLikeFeedNotice(92,true).getOrThrow() }
        val delete=f.hits.first { it.path=="/x/msgfeed/del" };check(delete.fields["tp"]=="3" && delete.fields["id"]=="91")
        val notice=f.hits.first { it.path=="/x/msgfeed/notice" };check(notice.fields["notice_state"]=="0")
        check(listOf(delete,notice).all { it.fields["csrf"]=="synthetic-only-csrf" && it.fields["csrf_token"]=="synthetic-only-csrf" })
            }
    }
    @Test fun upstreamLikeDetailPagesByLastMidOverThisChildsGatedTransport(): Unit = runBlocking {
        // TON-1109: upstream LikeDetailViewModel + MessageManagementRepository, built per message page child.
        MessagePageTestFixture().use { f ->
            f.respond={ hit->if(hit.path=="/x/msgfeed/like_detail") {
                if(hit.query["pn"]=="1") MessagePageTestReply("""{"code":0,"data":{"page":{"is_end":false},"card":{"business":"视频","title":"Synthetic card"},"items":[{"user":{"mid":71,"nickname":"A"},"like_time":1700000000},{"user":{"mid":72,"nickname":"B"},"like_time":1700000001}]}}""")
                else MessagePageTestReply("""{"code":0,"data":{"page":{"is_end":true},"items":[{"user":{"mid":73,"nickname":"C"},"like_time":1700000002}]}}""")
            } else f.default(hit) }
            val page=f.pageOwner();val vm=page.likeDetail(55)
            check(page.likeDetail(55)===vm) { "The open like detail keeps its view model" }
            vm.load(55);f.waitFor { !vm.uiState.value.loading && !vm.uiState.value.refreshing }
            check(vm.uiState.value.card?.title=="Synthetic card" && vm.uiState.value.items.map { it.user?.mid }==listOf(71L,72L) && vm.uiState.value.hasMore)
            vm.more();f.waitFor { !vm.uiState.value.loadingMore && vm.uiState.value.items.size==3 }
            check(!vm.uiState.value.hasMore)
            val pages=f.hits.filter { it.path=="/x/msgfeed/like_detail" }
            check(pages.map { it.query["card_id"] to it.query["pn"] }==listOf("55" to "1","55" to "2"))
            check(pages.last().query["last_mid"]=="72") { "More continues after the last loaded liker" }
            check(page.likeDetail(56)!==vm) { "Another card opens its own view model" }
        }
    }
    @Test fun upstreamSystemNoticeDeleteUsesThisChildsCsrfAndRemovesTheNotice(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm=SystemNoticeViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading && vm.uiState.value.items.isNotEmpty() }
            vm.remove(4);f.waitFor { !vm.uiState.value.operationBusy }
            check(vm.uiState.value.operationError==null) { "Delete error=${vm.uiState.value.operationError}" }
            check(vm.uiState.value.items.none { it.id==4 })
            val delete=f.hits.single { it.path=="/x/sys-msg/del_notify_list" }
            check(delete.method=="POST" && delete.query["csrf"]=="synthetic-only-csrf")
            val body=Json.parseToJsonElement(delete.rawBody.toString(Charsets.UTF_8)).jsonObject
            check(body["ids"]?.jsonArray?.map { it.jsonPrimitive.long }==listOf(4L) && body["type"]?.jsonPrimitive?.int==4 && body["csrf"]?.jsonPrimitive?.content=="synthetic-only-csrf")
        }
    }
    @Test fun retiredChildCannotSendAManagementRequest(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm=SystemNoticeViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
            check(f.admission.retireAndJoin())
            // A retired child rejects even the busy-state write: nothing is published or sent.
            runCatching { vm.remove(4) };delay(200)
            check(f.hits.none { it.path=="/x/sys-msg/del_notify_list" }) { "A retired message page must not delete for the account" }
        }
    }
    @Test fun chatOriginalOrderingEmotesControlAndActualACK(): Unit = runBlocking {
        // Chat original ordering emotes control and actual ACK
        MessagePageTestFixture().use { f ->
        val vm=ChatViewModel(7,1,f.admission);f.waitFor { !vm.uiState.value.isLoading && !vm.uiState.value.isSessionControlLoading }
        check(vm.uiState.value.messages.map { it.msg_key }==listOf(8L,9L));check(vm.uiState.value.emoteInfos.single().text=="[doge]")
        check(vm.currentUserMid==42L);check(vm.uiState.value.sessionControlInfo.isDnd==false)
        f.waitFor { f.hits.any { it.path.endsWith("update_ack") } };val ack=f.hits.first { it.path.endsWith("update_ack") }
        check(ack.fields["ack_seqno"]=="9" && ack.fields["talker_id"]=="7" && ack.fields["session_type"]=="1")
            }
    }
    @Test fun originalGroupDNDTopPushInterceptAndBatchParameters(): Unit = runBlocking {
        // original group DND top push intercept and batch parameters
        MessagePageTestFixture().use { f ->
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
    }
    // TON-1120: upstream pins an inbox session over IM (PinSession with the session's IM identity) and refreshes only on
    // success; a rejection keeps the server's sessions and reports the error (replaces the optimistic web set_top test).
    @Test fun imPinFailureKeepsAuthoritativeSessionsAndSuccessRefreshes(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
            val session=vm.uiState.value.sessions.first();val lists=f.hits.count { it.path.endsWith("/SessionMain") }
            f.respond={hit->if(hit.path.endsWith("/PinSession")) grpcReply(byteArrayOf(),"7","controlled rejection") else f.default(hit)}
            vm.toggleTop(session);f.waitFor { vm.uiState.value.operationError!=null && !vm.uiState.value.isBatchOperating }
            check(vm.uiState.value.sessions.all { it.top_ts==0L } && f.hits.count { it.path.endsWith("/SessionMain") }==lists)
            val pin=f.hits.single { it.path.endsWith("/PinSession") }
            check(grpcRequest(pin).bytes(1).contentEquals(vm.entry(session)!!.id)) { "PinSession carries the session's IM identity" }
            f.respond={ f.default(it) };vm.clearOperationError();vm.toggleTop(session)
            f.waitFor { f.hits.count { it.path.endsWith("/SessionMain") }>lists && !vm.uiState.value.isBatchOperating && !vm.uiState.value.isRefreshing }
            check(vm.uiState.value.operationError==null)
        }
    }
    @Test fun retiredChildCannotSendAnImRequest(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
            val im=f.hits.count { it.path in MessagePageTestFixture.imPaths }
            check(f.admission.retireAndJoin())
            runCatching { vm.refresh() };runCatching { vm.markAllRead() };delay(200)
            check(f.hits.count { it.path in MessagePageTestFixture.imPaths }==im) { "A retired message page must not send IM requests for the account" }
        }
    }
    @Test fun imCallOutsideAMessagePageFailsLoudly(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            // No process-wide gRPC client: without a message page's channel the call fails with a reason and sends nothing.
            val result=MessageImRepository.sessions()
            check(result.exceptionOrNull()?.message=="私信请求只能在消息页内发起") { "result=$result" }
            check(f.hits.none { it.path in MessagePageTestFixture.imPaths })
        }
    }
    @Test fun chatReadReceiptClearsTheSameSessionInTheInbox(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            f.respond={ hit->if(hit.path.endsWith("/SessionMain")) grpcReply(imPage(listOf(imSession(7,"Known7",700,unread=2,maxSeqno=9)),imCursor(1,false)))
                else f.default(hit) }
            val page=f.pageOwner();val inbox=page.inbox
            f.waitFor { !inbox.uiState.value.isLoading && inbox.uiState.value.sessions.singleOrNull()?.unread_count==2 }
            // The chat child is a separate admission of the same page; its read receipt (seqno 9) is the inbox's read event.
            val chat=page.chat(7,1);f.waitFor { f.hits.any { it.path.endsWith("update_ack") } && chat.uiState.value.messagesLoaded }
            f.waitFor { inbox.uiState.value.sessions.single().unread_count==0 }
            check(inbox.entry(inbox.uiState.value.sessions.single())?.unreadLabel=="")
        }
    }
    @Test fun chatEmotesLoadTheReplyPanelOverThisChildsApi(): Unit = runBlocking {
        MessagePageTestFixture().use { f ->
            val panel=listOf(1L,2L,53L,4L).joinToString(",") { """{"id":$it,"text":"P$it","emote":[{"id":$it,"text":"[e$it]","url":"https://synthetic.invalid/$it.png"}]}""" }
            f.respond={ hit->if(hit.path=="/x/emote/user/panel/web") MessagePageTestReply("""{"code":0,"data":{"packages":[$panel]}}""") else f.default(hit) }
            val vm=ChatViewModel(7,1,f.admission);f.waitFor { !vm.uiState.value.isLoading }
            vm.loadEmotes();f.waitFor { !vm.uiState.value.isEmoteLoading && vm.uiState.value.emotePackages.isNotEmpty() }
            check(vm.uiState.value.emotePackages.map { it.id }==listOf(1L,2L,53L,4L) && vm.uiState.value.emoteError==null)
            check(f.hits.single { it.path=="/x/emote/user/panel/web" }.query["business"]=="reply")
            check(f.hits.none { it.path=="/x/emote/package" }) { "A complete panel needs no standard-package lookup" }
        }
    }
    @Test fun sendPreservesTextCRTABAndWithdrawalPayloadModel(): Unit = runBlocking {
        // send preserves text CR TAB and withdrawal payload model
        MessagePageTestFixture().use { f ->
        f.call { f.admission.requests.sendTextMessage(7," hi\r\tquoted\" ",2).getOrThrow();f.admission.requests.withdrawMessage(7,101,2).getOrThrow() }
        val messages=f.hits.filter { it.path.endsWith("send_msg") };check(messages.size==2)
        val text=messages.first().fields;check(text["msg[receiver_type]"]=="2" && text["msg[sender_uid]"]=="42")
        check(Json.parseToJsonElement(text.getValue("msg[content]")).jsonObject.getValue("content").jsonPrimitive.content==" hi\r\tquoted\" ")
        check(messages.last().fields["msg[msg_type]"]=="5");check(messages.last().fields["msg[content]"]!!.contains("101"))
            }
    }
    @Test fun http503RetryAfter0MutationDoesNotReplay(): Unit = runBlocking {
        // HTTP503 RetryAfter0 mutation does not replay
        MessagePageTestFixture().use { f ->
        f.respond={ hit->if(hit.path.endsWith("send_msg"))MessagePageTestReply("""{"code":-1}""",503,mapOf("Retry-After" to "0"))else f.default(hit) }
        check(f.call { f.admission.requests.sendTextMessage(7,"user submit") }.isFailure)
        check(f.hits.count { it.path.endsWith("send_msg") }==1)
            }
    }
    @Test fun imageUploadValidatesOriginalCapAndSendsRealUploadedMetadata(): Unit = runBlocking {
        // image upload validates original cap and sends real uploaded metadata
        MessagePageTestFixture().use { f ->
        val vm=ChatViewModel(7,1,f.admission);f.waitFor { !vm.uiState.value.isLoading }
        val image=f.root.resolve("selected.png");Files.write(image,byteArrayOf(1,2,3,4))
        val context=object:android.content.Context(f.root.toFile()) { override fun getSharedPreferences(name:String,mode:Int)=error("unused") }
        vm.sendImageMessage(context,android.net.Uri.fromFile(image.toFile()));f.waitFor { f.hits.any { it.path.endsWith("send_msg") } && !vm.uiState.value.isUploadingImage }
        val upload=f.hits.first { it.path.endsWith("upload_bfs") };check(upload.rawBody.toString(Charsets.ISO_8859_1).contains("selected.png"))
        val sent=f.hits.first { it.path.endsWith("send_msg") };check(sent.fields["msg[msg_type]"]=="2")
        check(sent.fields["msg[content]"]!!.contains("synthetic.invalid/image.png"));check(sent.fields["msg[content]"]!!.contains("\"width\":2"))
        // The successful send deliberately refreshes/ACKs the chat. The size cap must
        // prevent another upload/send, while those original read requests may finish.
        val before=f.hits.count { it.path.endsWith("upload_bfs") || it.path.endsWith("send_msg") }
        Files.write(image,ByteArray(15*1024*1024+1));vm.sendImageMessage(context,android.net.Uri.fromFile(image.toFile()))
        f.waitFor { vm.uiState.value.sendError!=null };check(vm.uiState.value.sendError!!.contains("15MB"))
        check(f.hits.count { it.path.endsWith("upload_bfs") || it.path.endsWith("send_msg") }==before)
            }
    }
    @Test fun sameMIDNewEpochRejectsOldSenderAndStaleUI(): Unit = runBlocking {
        // same MID new epoch rejects old sender and stale UI
        MessagePageTestFixture().use { f ->
        val oldState=f.admission.stateFlow(1);val entered=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>()
        val old=f.admission.launch { entered.complete(Unit);withContext(NonCancellable){released.await()};oldState.value=2 }
        entered.await();f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only-B","bili_jct" to "different"),AccountSummary(42,"Fixture",""));released.complete(Unit);old.join()
        check(oldState.value==1);val before=f.hits.size
        val oldCall=f.admission.launch { f.admission.requests.sendTextMessage(7,"must not send") };oldCall.join();check(f.hits.size==before)
            }
    }
    @Test fun callerCancellationStopsRequestAndPostIOPublication(): Unit = runBlocking {
        // caller cancellation stops request and post-IO publication
        MessagePageTestFixture().use { f ->
        val state=f.admission.stateFlow(0);val entered=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>()
        val job=f.admission.launch { entered.complete(Unit);withContext(NonCancellable){released.await()};state.value=9 }
        entered.await();job.cancel();released.complete(Unit);job.join();check(state.value==0)
        f.retained.set(false);check(f.admission.retireAndJoin());check(!f.admission.isOwned())
            }
    }
    @Test fun coveredEntryReadsRetainedButCoveredMutationCannotSubmit(): Unit = runBlocking {
        // covered entry reads retained but covered mutation cannot submit
        MessagePageTestFixture().use { f ->
        f.visible.set(false);val vm=InboxViewModel(f.admission);f.waitFor { !vm.uiState.value.isLoading }
        check(vm.uiState.value.sessions.isNotEmpty());val before=f.hits.size
        vm.removeSession(vm.uiState.value.sessions.first());delay(100);check(f.hits.size==before)
        f.visible.set(true);vm.removeSession(vm.uiState.value.sessions.first());f.waitFor { f.hits.any { it.path.endsWith("remove_session") } }
            }
    }
}


@Suppress("UNCHECKED_CAST")
private fun <T> unusedMessageUiPort(type:Class<T>):T = java.lang.reflect.Proxy.newProxyInstance(
    type.classLoader,arrayOf(type)) { _,method,_-> error("Unexpected rendering port ${method.name}") } as T

/** Actual PageOwner/ChatViewModel/owned Job regression; no OS window or real account. */
class DesktopMessageUpdateInstallationHoldTest {
    private suspend fun ready(f:MessagePageTestFixture,vm:ChatViewModel) {
        val initialLoad = checkNotNull(vm.javaClass.getDeclaredField("latestMessagesJob")
            .apply { isAccessible = true }.get(vm) as? Job)
        // Loaded UI precedes the original asynchronous read ACK. Settle that startup
        // mutation before checking whether a later editor action starts any work.
        f.waitFor { initialLoad.isCompleted && vm.uiState.value.messagesLoaded &&
            !vm.uiState.value.isLoading && !child(vm).hasPendingEditorMutations() }
    }
    private fun child(vm:ChatViewModel):DesktopMessagePageAdmission =
        vm.javaClass.getDeclaredField("owner").apply{isAccessible=true}.get(vm) as DesktopMessagePageAdmission
    private fun sendJob(vm:ChatViewModel):Job {
        val admission=child(vm)
        @Suppress("UNCHECKED_CAST")
        val jobs=admission.javaClass.getDeclaredField("operations").apply{isAccessible=true}.get(admission) as Map<String,Job>
        return checkNotNull(jobs["chat-send"])
    }
    private class SendDrain(val job:Job,val release:CompletableDeferred<Unit>,val response:CountDownLatch):AutoCloseable {
        override fun close(){response.countDown();release.complete(Unit)}
    }
    private suspend fun blockedSend(f:MessagePageTestFixture,vm:ChatViewModel):SendDrain {
        val entered=CompletableDeferred<Unit>();val response=CountDownLatch(1)
        f.respond={ hit->if(hit.path.endsWith("send_msg")) {
            entered.complete(Unit);response.await(3,TimeUnit.SECONDS);f.default(hit)
        }else f.default(hit) }
        vm.sendMessage("synthetic draft; no real message")
        withTimeout(5_000){entered.await()}
        val job=sendJob(vm);val cleanupEntered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        // Attach bounded cleanup to the exact original send Job. Cancellation cannot drain
        // that actual Job until its child cleanup has finished; no synthetic busy counter.
        CoroutineScope(job+Dispatchers.Default).launch {
            withContext(NonCancellable){withTimeout(5_000){cleanupEntered.complete(Unit);release.await()}}
        }
        withTimeout(5_000){cleanupEntered.await()}
        return SendDrain(job,release,response)
    }

    @Test fun emptyInboxAndNotificationsDoNotHoldInstallation():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();val inbox=page.inbox
            f.waitFor{!inbox.uiState.value.isLoading}
            check(inbox.uiState.value.sessions.isNotEmpty())
            check(!page.blocksUpdateInstallation())
            page.replyMe
            check(!page.blocksUpdateInstallation())
        }
    }
    @Test fun actualPaneRetainsSameVmWhileCoveredAndExplicitBackReleases():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();var selected=0L
            check(page.selectPaneChat(7,1){selected=7});check(selected==7L && page.blocksUpdateInstallation())
            val vm=page.chat(7,1);ready(f,vm)
            val messages=vm.uiState.value.messages
            f.visible.set(false);f.canBeginEditor.set(false)
            check(page.retainPaneChat(7,1) && page.chat(7,1)===vm)
            check(page.blocksUpdateInstallation() && vm.uiState.value.messages===messages)
            check(!page.selectPaneChat(8,1){error("covered selection")})
            f.visible.set(true);f.canBeginEditor.set(true)
            page.keepPaneChat(0,1)
            check(!page.blocksUpdateInstallation())
        }
    }
    @Test fun installingRejectsNewPaneAndOriginalSendBeforeJobStart():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();f.canBeginEditor.set(false)
            check(!page.selectPaneChat(7,1){error("installing selection")})
            check(!page.retainPaneChat(7,1) && !page.blocksUpdateInstallation())
            check(runCatching{page.chat(7,1)}.exceptionOrNull() is CancellationException)
            f.canBeginEditor.set(true);val vm=page.chat(7,1);ready(f,vm)
            f.canBeginEditor.set(false);val hits=f.hits.count{it.path.endsWith("send_msg")}
            vm.sendMessage("must not start")
            check(!vm.uiState.value.isSending && !child(vm).hasPendingEditorMutations())
            check(f.hits.count{it.path.endsWith("send_msg")}==hits)
        }
    }
    @Test fun compactPaneExitRetainsExactSendUntilItsRealCleanupDrains():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();val vm=page.chat(7,1);ready(f,vm)
            blockedSend(f,vm).use { send->
                check(child(vm).hasPendingEditorMutations())
                page.keepPaneChat(0L,0)
                check(send.job.isCancelled && !send.job.isCompleted)
                check(page.blocksUpdateInstallation())
                send.close();withTimeout(5_000){send.job.join()}
                f.waitFor{!page.blocksUpdateInstallation()}
                check(vm.uiState.value.sentText==null)
            }
        }
    }
    @Test fun oldSendCompletionCannotReleaseSuccessorPaneReservation():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();val old=page.chat(7,1);ready(f,old)
            blockedSend(f,old).use { send->
                check(page.selectPaneChat(8,1){})
                val successor=page.chat(8,1);ready(f,successor);page.keepPaneChat(8,1)
                check(old!==successor && send.job.isCancelled && page.blocksUpdateInstallation())
                send.close();withTimeout(5_000){send.job.join()}
                f.waitFor{!child(old).hasPendingEditorMutations()}
                check(page.blocksUpdateInstallation() && page.chat(8,1)===successor)
                page.keepPaneChat(0,1);check(!page.blocksUpdateInstallation())
            }
        }
    }
    @Test fun retiredOwnerAndCloseAndJoinKeepExactPendingJobUntilDrained():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();val vm=page.chat(7,1);ready(f,vm)
            blockedSend(f,vm).use { send->
                f.retained.set(false);page.close()
                check(!page.isOwned() && page.blocksUpdateInstallation())
                val drained=async{page.closeAndJoin()}
                check(!send.job.isCompleted && !page.isDrained())
                check(!page.selectPaneChat(8,1){error("retired selection")})
                send.close();withTimeout(5_000){drained.await()}
                f.waitFor{!page.blocksUpdateInstallation()}
                check(page.isDrained() && vm.uiState.value.sentText==null)
            }
        }
    }
    @Test fun lazyMutationCancelledBeforeStartCannotLeakItsExactReservation():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();val vm=page.chat(7,1);ready(f,vm);val admission=child(vm)
            f.onEditorActivity={if(admission.hasPendingEditorMutations())f.scope.cancel()}
            val job=admission.launchMutation("chat-send"){error("cancelled LAZY body started")}
            check(job.isCancelled && job.isCompleted)
            check(!admission.hasPendingEditorMutations() && !page.blocksUpdateInstallation())
            check(f.hits.none{it.path.endsWith("send_msg")})
        }
    }
    @Test fun sameMidAccountEpochReplacementCannotRebindOldPane():Unit=runBlocking {
        MessagePageTestFixture().use { f->
            val page=f.pageOwner();val vm=page.chat(7,1);ready(f,vm)
            f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-new-epoch","bili_jct" to "synthetic"),AccountSummary(42,"Fixture",""))
            check(!page.isOwned() && !page.blocksUpdateInstallation())
            check(!page.retainPaneChat(7,1) && !page.selectPaneChat(8,1){error("old owner rebound")})
            val count=f.hits.count{it.path.endsWith("send_msg")};vm.sendMessage("old epoch")
            check(f.hits.count{it.path.endsWith("send_msg")}==count && !child(vm).hasPendingEditorMutations())
        }
    }
}
