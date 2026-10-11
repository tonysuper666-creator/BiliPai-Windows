package com.bilipai.desktop.data

import com.android.purebilibili.core.network.socket.DanmakuProtocol
import com.android.purebilibili.data.repository.DefaultLiveReportReasons
import com.android.purebilibili.feature.live.LiveRealtimeAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual original HTTP methods, owned Store/Session.start and original loopback WS decoder.
 * The only permitted socket is the fixture-owned RFC6455 127.0.0.1 random port.
 * All Bilibili API/HTML/SPI/WBI/read and mutation replies are memory-only; no real account/API write.
 * No private Session filter/state/apply mutation or production test seam.
 * Complete original Loopback/ReplyGate helpers are copied unchanged from the prior owned vote fixture. */
class DesktopLiveModerationOwnedSessionTest {
    private class Loopback : Closeable {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val connections = CopyOnWriteArrayList<Peer>()
        @Volatile private var closed = false
        private val acceptor = Thread({
            while (!closed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                val peer = Peer(socket); connections += peer
                Thread({ peer.run() }, "live-danmaku-fixture-peer").apply { isDaemon = true; start() }
            }
        }, "live-danmaku-fixture-accept").apply { isDaemon = true; start() }

        class Peer(private val socket: Socket) {
            val packets = CopyOnWriteArrayList<DanmakuProtocol.Packet>()
            val authenticated = CountDownLatch(1)
            val ended = CountDownLatch(1)
            @Volatile var error: Throwable? = null
            @Volatile var peerCloseObserved = false
            @Volatile var eofObserved = false
            private val output = socket.getOutputStream()
            private fun frame(opcode: Int, bytes: ByteArray) = synchronized(output) {
                check(bytes.size <= 16_384)
                output.write(0x80 or opcode)
                if (bytes.size < 126) output.write(bytes.size) else {
                    output.write(126); output.write(bytes.size ushr 8); output.write(bytes.size and 255)
                }
                output.write(bytes); output.flush()
            }
            fun normalClose() = frame(8, byteArrayOf(3, -24)) // server's actual RFC6455 1000 close
            fun message(text: String) = frame(2, DanmakuProtocol.encode(DanmakuProtocol.Packet(
                DanmakuProtocol.PROTO_VER_JSON, DanmakuProtocol.OP_MESSAGE, body = text.toByteArray())))
            fun run() {
                try {
                    // Longer than the five-second cleanup assertion: a missing
                    // disconnect cannot pass merely because this server timed out.
                    socket.soTimeout = 15_000
                    val input = DataInputStream(socket.getInputStream())
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read(); if (next < 0) throw IOException("Truncated fixture upgrade")
                        header.append(next.toChar()); check(header.length < 16_384)
                    }
                    val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                    output.flush()
                    while (true) {
                        val first = input.read(); if (first < 0) { eofObserved = true; break }
                        val second = input.readUnsignedByte(); check(second and 128 != 0)
                        val length = when (val short = second and 127) {
                            126 -> input.readUnsignedShort()
                            127 -> input.readLong().also { check(it in 0..16_384) }.toInt()
                            else -> short
                        }
                        check(length <= 16_384)
                        val mask = ByteArray(4); input.readFully(mask)
                        val payload = ByteArray(length); input.readFully(payload)
                        payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                        when (first and 15) {
                            2 -> {
                                val decoded = runBlocking { DanmakuProtocol.decode(payload) }; packets += decoded
                                if (decoded.any { it.operation == DanmakuProtocol.OP_AUTH }) {
                                    frame(2, DanmakuProtocol.encode(DanmakuProtocol.Packet(DanmakuProtocol.PROTO_VER_JSON,
                                        DanmakuProtocol.OP_AUTH_REPLY, body = "{\"code\":0}".toByteArray())))
                                    authenticated.countDown()
                                }
                            }
                            8 -> { peerCloseObserved = true; runCatching { frame(8, payload) }; break }
                            9 -> frame(10, payload)
                        }
                    }
                } catch (failure: Throwable) { error = failure }
                finally { runCatching { socket.close() }; ended.countDown() }
            }
            fun dispose() { runCatching { socket.close() } }
        }
        override fun close() { closed = true; server.close(); connections.forEach { it.dispose() }; acceptor.join(1_000) }
    }

    private class ReplyGate {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        fun hold() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Fixture reply was not released" } }
        fun open() = release.countDown()
    }

    private inner class Fixture(keywords: List<String> = emptyList(), users: List<Long> = emptyList()) {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val media = DesktopMediaRepository(repository)
        val loopback = Loopback()
        val requests = CopyOnWriteArrayList<Request>()
        val cookieReceipts = ConcurrentHashMap<Request, Triple<Long?, Boolean?, List<Cookie>>>()
        val actions = CopyOnWriteArrayList<LiveRealtimeAction>()
        val chatSessions = CopyOnWriteArrayList<DesktopLiveSession>()
        val actionsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val activeCalls = AtomicInteger()
        val serverKeywords = CopyOnWriteArrayList(keywords)
        val serverUsers = CopyOnWriteArrayList(users)
        val serverLevel = AtomicInteger(15)
        val gate = ReplyGate()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile private var heldPath: String? = null
        @Volatile private var heldOccurrence = 0
        @Volatile var loseResponse: String? = null
        fun holdNext(path: String) { heldOccurrence = count(path) + 1; heldPath = path }
        fun count(path: String): Int = counts[path]?.get() ?: 0
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-secondary", "bili_jct" to "secondary-csrf"),
                AccountSummary(41, "Secondary", ""))
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-live", "bili_jct" to "synthetic-csrf"),
                AccountSummary(42, "Fixture", ""))
            val transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request; activeCalls.incrementAndGet()
                    try {
                        if (request.header("Upgrade").equals("websocket", true)) {
                            if (!request.url.isHttps || request.url.port != 443 || request.method != "GET" ||
                                request.url.host != "fixture.invalid" || request.url.encodedPath != "/sub")
                                throw IOException("Unowned fixture socket")
                            val local = "http://127.0.0.1:${loopback.server.localPort}/sub".toHttpUrl()
                            chain.proceed(request.newBuilder().url(local).build())
                        } else {
                            val path = request.url.encodedPath
                            val host = when (path) { "/" -> "www.bilibili.com"
                                "/x/frontend/finger/spi", "/x/web-interface/nav" -> "api.bilibili.com"
                                else -> "api.live.bilibili.com" }
                            check(request.url.isHttps && request.url.port == 443 && request.url.host == host)
                            check(request.method == if (path in POST_PATHS) "POST" else "GET")
                            // Memory response precedes BridgeInterceptor, which normally reads the CookieJar.
                            // Read the SAME jar here while the actual outer owner interceptor's epoch/admission
                            // ThreadLocals are still installed. This observes real cookie selection, never fabricates
                            // a wire Cookie header or modifies this request's owner/credentials.
                            val capturedEpoch = sessions.requestGeneration.get()
                            val capturedAdmission = sessions.requestAdmission.get()?.invoke()
                            cookieReceipts[request] = Triple(capturedEpoch, capturedAdmission,
                                repository.httpClient.cookieJar.loadForRequest(request.url))
                            val occurrence = counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                            // Capture a read response before its gate, just like an already-arriving old server reply.
                            // Mutations below apply only to this memory fixture, never a live HTTP service.
                            val body = when (path) {
                                "/" -> "<html>memory-only visitor bootstrap</html>"
                                "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                                "/room/v1/Room/room_init" -> """{"code":0,"data":{"room_id":123,"live_status":1}}"""
                                "/xlive/web-room/v1/index/getInfoByRoom" -> """{"code":0,"data":{"room_info":{"room_id":123,"title":"Fixture","live_status":1},"anchor_info":{"base_info":{"uname":"Synthetic"}}}}"""
                                "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":42,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                                "/xlive/web-room/v1/index/getDanmuInfo" -> """{"code":0,"data":{"token":"fixture-token","host_list":[{"host":"fixture.invalid","wss_port":443}]}}"""
                                "/xlive/web-room/v1/dM/GetDMConfigByGroup" -> """{"code":0,"data":{"group":[{"color":[{"color":"16777215","name":"白","status":1}]}],"mode":[{"mode":1,"name":"滚动","status":1}]}}"""
                                "/xlive/web-room/v1/dM/gethistory" -> history()
                                "/xlive/app-room/v1/dm/interaction/votePanel" -> """{"code":0,"data":{"vote_info":{}}}"""
                                "/xlive/app-room/v1/dm/interaction/voteHistory" -> """{"code":0,"data":{"history":[]}}"""
                                SHIELD -> shield()
                                USER -> {
                                    val form = form(request); val uid = form.getValue("uid").toLong()
                                    if (form.getValue("type") == "1") serverUsers.addIfAbsent(uid) else serverUsers.remove(uid)
                                    """{"code":0,"message":"","data":null}"""
                                }
                                ADD -> { serverKeywords.addIfAbsent(form(request).getValue("keyword")); """{"code":0,"message":""}""" }
                                DELETE -> { serverKeywords.remove(form(request).getValue("keyword")); """{"code":0,"message":""}""" }
                                SILENT -> { serverLevel.set(form(request).getValue("level").toInt()); """{"code":0,"message":""}""" }
                                REPORT, REPORT_SC -> """{"code":0,"message":""}"""
                                else -> throw IOException("External transport forbidden: ${request.method} ${request.url}")
                            }
                            if (path == heldPath && occurrence == heldOccurrence) gate.hold()
                            if (path == loseResponse) throw IOException("Fixture response lost after memory-only server acceptance")
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory fixture")
                                .body(body.toResponseBody("application/json".toMediaType())).build()
                        }
                    } finally { activeCalls.decrementAndGet() }
                }.build()
            // Exact existing fixture transport replacement; no Store/lifetime/filter/apply authority is mutated.
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        }
        private fun shield(): String = JSONObject().put("code", 0).put("data", JSONObject().put("shield_info", JSONObject()
            .put("shield_rule", JSONObject().put("level", serverLevel.get()).put("medal", 7).put("verify", 2).put("rank", 1).put("phone", 1))
            .put("keyword_list", JSONArray(serverKeywords.toList()))
            .put("user_list", JSONArray(serverUsers.map { JSONObject().put("uid", it).put("uname", "fixture-$it") })))).toString()
        fun session(roomId: Long): DesktopLiveSession = DesktopLiveSession(repository, media, roomId).also { chat ->
            chatSessions += chat
            actionsScope.launch(start = CoroutineStart.UNDISPATCHED) { chat.actions.collect { actions += it } }
        }
        suspend fun startCanonical(): DesktopLiveSession {
            val room = media.liveRoom(9); assertEquals(123L, room.roomId)
            return session(room.roomId).also { it.start() }
        }
        // Observation only, never supplied to owner predicates or used to invoke private apply/state setters.
        fun sessionJob(chat: DesktopLiveSession): Job = (DesktopLiveSession::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(chat) as CoroutineScope).coroutineContext.job
        suspend fun dispose() = withContext(NonCancellable) {
            try {
                gate.open(); chatSessions.forEach { it.close() }
                withTimeout(5_000) { chatSessions.forEach { sessionJob(it).join() } }
                actionsScope.cancel(); withTimeout(5_000) { actionsScope.coroutineContext.job.join() }
                until { activeCalls.get() == 0 }
                val peers = loopback.connections.toList(); peers.forEach { awaitClosed(it) }
                assertEquals(peers, loopback.connections.toList(), "A late peer appeared after real Session/HTTP drain")
            } finally {
                gate.open()
                try { chatSessions.forEach { it.close() }; actionsScope.cancel() }
                finally { try { loopback.close() } finally {
                    try { repository.httpClient.dispatcher.executorService.shutdownNow() }
                    finally { repository.httpClient.connectionPool.evictAll() }
                } }
            }
        }
    }

    private suspend inline fun Fixture.useSuspending(block: (Fixture) -> Unit) {
        try { block(this) } finally { dispose() }
    }
    private suspend fun until(predicate: () -> Boolean) { withTimeout(5_000) { while (!predicate()) delay(5) } }
    private suspend fun await(latch: CountDownLatch) { withContext(Dispatchers.IO) { check(latch.await(5, TimeUnit.SECONDS)) } }
    private suspend fun awaitClosed(peer: Loopback.Peer) {
        await(peer.ended)
        assertFalse(peer.error is SocketTimeoutException, "A timeout is not socket cleanup")
        assertTrue(peer.error == null || peer.error is SocketException, "Unexpected peer failure: ${peer.error}")
        assertTrue(peer.peerCloseObserved || peer.eofObserved || peer.error is SocketException)
    }
    private suspend fun ready(f: Fixture, chat: DesktopLiveSession): Loopback.Peer {
        until { f.loopback.connections.singleOrNull()?.authenticated?.count == 0L }
        val peer = f.loopback.connections.single()
        withTimeout(5_000) {
            while (f.actions.none { it is LiveRealtimeAction.UpdateRoomTitle && it.title == "fixture-collector-ready" }) {
                peer.message("""{"cmd":"ROOM_CHANGE","data":{"title":"fixture-collector-ready"}}"""); delay(10)
            }
        }
        until { chat.state.value.connected }; return peer
    }
    private suspend fun barrier(f: Fixture, peer: Loopback.Peer, title: String) {
        peer.message(JSONObject().put("cmd", "ROOM_CHANGE").put("data", JSONObject().put("title", title)).toString())
        until { f.actions.any { it is LiveRealtimeAction.UpdateRoomTitle && it.title == title } }
    }

    @Test fun actualStartupHistoryAndWireUseUidKeywordRulesWhileSelfSystemAndSuperChatKeepTheirScope(): Unit = runBlocking {
        Fixture(listOf("blocked", "等级"), listOf(77, 42)).useSuspending { f ->
            val chat = f.startCanonical(); val peer = ready(f, chat)
            assertEquals(listOf("kept history"), chat.state.value.messages.map { it.text })
            assertEquals(listOf(77L, 42L), chat.shieldInfo.value.users.map { it.uid })
            assertEquals(15, chat.shieldInfo.value.level); assertEquals(1, chat.shieldInfo.value.phone)
            peer.message(danmaku(77, "wire uid hidden", "uid-hidden"))
            peer.message(danmaku(88, "blocked wire hidden", "keyword-hidden"))
            peer.message(danmaku(99, "wire kept", "wire-kept"))
            peer.message(danmaku(42, "blocked self", "self-kept"))
            peer.message("""{"cmd":"ROOM_SILENT_ON"}""")
            peer.message(superChat(77, "blocked sc", 1234))
            barrier(f, peer, "shield-first-batch")
            assertEquals(listOf("kept history", "wire kept", "blocked self", "直播间已开启等级禁言", "blocked sc"), chat.state.value.messages.map { it.text })
            assertTrue(chat.state.value.messages.single { it.text == "blocked self" }.isSelf)
            assertTrue(chat.state.value.messages.single { it.text.contains("等级") }.isSystem)
            assertTrue(chat.state.value.messages.single { it.text == "blocked sc" }.isSuperChat)
            assertEquals(listOf("wire kept", "blocked self", "直播间已开启等级禁言"), f.actions.filterIsInstance<LiveRealtimeAction.EmitChat>().map { it.item.text })
            assertEquals(listOf("blocked sc"), f.actions.filterIsInstance<LiveRealtimeAction.EmitSuperChat>().map { it.item.text })
            // Accepted original write + actual refresh also remove already-retained ordinary matches.
            chat.addShieldKeyword("kept")
            assertEquals(listOf("blocked self", "直播间已开启等级禁言", "blocked sc"), chat.state.value.messages.map { it.text })
            val before = f.actions.filterIsInstance<LiveRealtimeAction.EmitChat>().toList()
            peer.message(danmaku(99, "wire kept again", "after-refresh-hidden"))
            barrier(f, peer, "shield-after-refresh")
            assertEquals(before, f.actions.filterIsInstance<LiveRealtimeAction.EmitChat>())
            assertEquals(1, f.count(ADD)); assertEquals(2, f.count(SHIELD))
        }
    }

    @Test fun sevenOriginalMethodsUsePrimaryCookiesExactFormsAndSignedCanonicalShieldRead(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val chat = f.startCanonical(); val peer = ready(f, chat)
            chat.shieldUser(77); chat.unshieldUser(77); chat.addShieldKeyword("  blocked  ")
            chat.deleteShieldKeyword("blocked"); chat.setSilentRule("level", 19)
            peer.message(danmaku(99, "report ordinary", "123456789012"))
            peer.message(superChat(98, "report sc", 1234)); barrier(f, peer, "report-items-ready")
            val reason = DefaultLiveReportReasons.single { it.id == 3 }
            val ordinary = chat.state.value.messages.single { it.idStr == "123456789012" }
            val sc = chat.state.value.messages.single { it.superChatId == 1234L }
            chat.reportDanmaku(ordinary, reason); chat.reportDanmaku(sc, reason)
            val posts = f.requests.filter { it.method == "POST" }
            assertEquals(listOf(USER, USER, ADD, DELETE, SILENT, REPORT, REPORT_SC), posts.map { it.url.encodedPath })
            val csrf = mapOf("csrf" to "synthetic-csrf", "csrf_token" to "synthetic-csrf")
            assertEquals(csrf + mapOf("uid" to "77", "roomid" to "123", "type" to "1"), form(posts[0]))
            assertEquals(csrf + mapOf("uid" to "77", "roomid" to "123", "type" to "0"), form(posts[1]))
            assertEquals(csrf + mapOf("keyword" to "blocked"), form(posts[2]))
            assertEquals(csrf + mapOf("keyword" to "blocked"), form(posts[3]))
            assertEquals(csrf + mapOf("type" to "level", "level" to "19"), form(posts[4]))
            assertEquals(csrf + mapOf("id" to "0", "roomid" to "123", "tuid" to "99", "msg" to "report ordinary",
                "reason" to reason.apiReason, "ts" to "1700000000", "sign" to "fixture-check", "reason_id" to "3",
                "token" to "", "dm_type" to "1", "id_str" to "123456789012", "visit_id" to ""), form(posts[5]))
            assertEquals(csrf + mapOf("id" to "1234", "roomid" to "123", "uid" to "98", "msg" to "report sc",
                "reason" to reason.apiReason, "ts" to "1700000001", "sign" to "", "reason_id" to reason.apiReason,
                "token" to "fixture-sc-token", "id_str" to "1234", "visit_id" to ""), form(posts[6]))
            posts.forEach { assertTrue(assertNotNull(it.body).isOneShot()); assertTrue(it.url.queryParameterNames.isEmpty()) }
            val reads = f.requests.filter { it.url.encodedPath == SHIELD }
            assertEquals(6, reads.size) // Startup + five accepted refresh operations; reports never mutate shield state.
            reads.forEach { assertEquals("GET", it.method); assertSignedShield(it) }
            (reads + posts).forEach { request ->
                val receipt = f.cookieReceipts.getValue(request)
                assertEquals(f.repository.sessionEpoch, receipt.first); assertEquals(true, receipt.second)
                val cookies = receipt.third.associate { it.name to it.value }
                assertEquals("synthetic-live", cookies["SESSDATA"]); assertEquals("synthetic-csrf", cookies["bili_jct"])
                assertFalse("synthetic-secondary" in cookies.values); assertFalse("secondary-csrf" in cookies.values)
            }
            assertEquals(1, f.requests.count { it.url.encodedPath == "/room/v1/Room/room_init" })
            assertEquals(19, chat.shieldInfo.value.level); assertFalse(chat.state.value.moderating)
            val before = posts.size
            assertFailsWith<IllegalArgumentException> { chat.reportDanmaku(ordinary.copy(), reason) }
            assertEquals(before, f.requests.count { it.method == "POST" }) // Equal copy is not the exact retained item owner.
        }
    }

    @Test fun lostMutationReplyIsSentOnceAndCannotTriggerAutomaticRefreshOrOptimisticShieldPublication(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val chat = f.startCanonical(); ready(f, chat)
            val before = chat.shieldInfo.value; val reads = f.count(SHIELD)
            f.loseResponse = ADD
            assertFailsWith<Exception> { chat.addShieldKeyword("server-may-have-applied") }
            assertEquals(1, f.count(ADD)); assertEquals(reads, f.count(SHIELD)); assertSame(before, chat.shieldInfo.value)
            assertFalse(chat.state.value.moderating); assertNotNull(chat.state.value.error)
            assertTrue(assertNotNull(f.requests.single { it.url.encodedPath == ADD }.body).isOneShot())
            assertTrue("server-may-have-applied" in f.serverKeywords)
            f.loseResponse = null; chat.refreshShieldInfo()
            assertEquals(listOf("server-may-have-applied"), chat.shieldInfo.value.keywords.map { it.keyword })
            assertEquals(1, f.count(ADD)) // User read reconciliation, never an implicit second mutation.
        }
    }

    @Test fun lateActualInitialHydrationCannotOverwriteShieldRefreshAfterAcceptedWrite(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.holdNext(SHIELD)
            val chat = f.startCanonical(); await(f.gate.entered)
            assertEquals(1, f.count(SHIELD)); assertTrue(f.loopback.connections.isEmpty())
            chat.addShieldKeyword("kept") // Separate real caller can complete while initial read response is held.
            val accepted = chat.shieldInfo.value
            assertEquals(listOf("kept"), accepted.keywords.map { it.keyword }); assertEquals(2, f.count(SHIELD))
            assertEquals(1, f.count(ADD)); assertFalse(chat.state.value.moderating)
            f.gate.open(); val peer = ready(f, chat)
            assertSame(accepted, chat.shieldInfo.value)
            assertFalse(chat.state.value.messages.any { it.text == "kept history" })
            peer.message(danmaku(99, "kept late wire", "late-hydration-hidden")); barrier(f, peer, "late-hydration-barrier")
            assertFalse(chat.state.value.messages.any { it.idStr == "late-hydration-hidden" })
            assertFalse(f.actions.filterIsInstance<LiveRealtimeAction.EmitChat>().any { it.item.idStr == "late-hydration-hidden" })
        }
    }

    @Test fun realAccountAbaCloseAndCallerCancellationRejectLateWriteAndRefreshPublication(): Unit = runBlocking {
        for (phase in listOf("write", "refresh")) for (retirement in listOf("account", "close", "caller")) {
            Fixture().useSuspending { f ->
                val chat = f.startCanonical(); val peer = ready(f, chat)
                val before = chat.shieldInfo.value; val epoch = f.repository.sessionEpoch
                val initialReads = f.count(SHIELD)
                if (phase == "refresh") f.serverKeywords.add("late-read")
                f.holdNext(if (phase == "write") ADD else SHIELD)
                val operation = async { if (phase == "write") chat.addShieldKeyword("late-write") else chat.refreshShieldInfo() }
                try {
                    await(f.gate.entered)
                    when (retirement) {
                        "account" -> {
                            assertTrue(f.sessions.activateAccount(41)); assertTrue(f.sessions.activateAccount(42))
                            assertEquals(42L, f.sessions.activeAccountMid()); assertTrue(f.repository.sessionEpoch > epoch)
                            until { !f.sessionJob(chat).isActive }
                        }
                        "close" -> chat.close()
                        "caller" -> operation.cancel()
                    }
                    f.gate.open()
                    assertFailsWith<CancellationException> { operation.await() }
                    until { f.activeCalls.get() == 0 }
                    assertSame(before, chat.shieldInfo.value, "$phase/$retirement")
                    assertEquals(initialReads + if (phase == "refresh") 1 else 0, f.count(SHIELD))
                    assertEquals(if (phase == "write") 1 else 0, f.count(ADD))
                    assertFalse(chat.state.value.moderating); assertNull(chat.state.value.error)
                    if (retirement == "caller") {
                        assertTrue(chat.state.value.connected)
                        // A canceled caller does not retire the Session or poison its real moderation guard.
                        chat.refreshShieldInfo()
                        assertEquals(listOf(if (phase == "write") "late-write" else "late-read"), chat.shieldInfo.value.keywords.map { it.keyword })
                        chat.addShieldKeyword("current-caller-retry")
                        assertEquals(listOf(if (phase == "write") "late-write" else "late-read", "current-caller-retry"),
                            chat.shieldInfo.value.keywords.map { it.keyword })
                        assertFalse(chat.state.value.moderating)
                    } else {
                        withTimeout(5_000) { f.sessionJob(chat).join() }; awaitClosed(peer)
                        val count = f.requests.size
                        assertFailsWith<IllegalStateException> { chat.addShieldKeyword("retired-never-send") }
                        chat.start(); assertEquals(count, f.requests.size); assertEquals(1, f.loopback.connections.size)
                        assertFalse(chat.state.value.connected); assertEquals("弹幕连接已关闭", chat.state.value.status)
                    }
                } finally { f.gate.open(); operation.cancelAndJoin() }
            }
        }
    }

    private companion object {
        const val SHIELD = "/xlive/web-room/v1/index/getInfoByUser"
        const val USER = "/liveact/shield_user"
        const val ADD = "/xlive/web-ucenter/v1/banned/AddShieldKeyword"
        const val DELETE = "/xlive/web-ucenter/v1/banned/DelShieldKeyword"
        const val SILENT = "/liveact/user_silent"
        const val REPORT = "/xlive/web-ucenter/v1/dMReport/Report"
        const val REPORT_SC = "/av/v1/SuperChat/report"
        val POST_PATHS = setOf(USER, ADD, DELETE, SILENT, REPORT, REPORT_SC)
        fun form(request: Request): Map<String, String> {
            val bytes = okio.Buffer(); assertNotNull(request.body).writeTo(bytes)
            return bytes.readUtf8().split('&').associate { field ->
                URLDecoder.decode(field.substringBefore('='), "UTF-8") to URLDecoder.decode(field.substringAfter('='), "UTF-8")
            }
        }
        fun assertSignedShield(request: Request) {
            assertEquals(setOf("room_id", "from", "web_location", "wts", "w_rid"), request.url.queryParameterNames)
            assertEquals("123", request.url.queryParameter("room_id")); assertEquals("0", request.url.queryParameter("from"))
            assertEquals("444.8", request.url.queryParameter("web_location"))
            assertTrue(assertNotNull(request.url.queryParameter("wts")).toLong() > 0)
            // Known fixture NAV keys produce this literal mixin key; verify the received signed request.
            val query = request.url.queryParameterNames.filter { it != "w_rid" }.sorted().joinToString("&") {
                "$it=${request.url.queryParameter(it)}"
            }
            val expected = MessageDigest.getInstance("MD5").digest((query + "1022a87ffdaf532cb45ee953dce8c96d").toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, request.url.queryParameter("w_rid"))
        }
        fun history(): String = JSONObject().put("code", 0).put("data", JSONObject().put("room", JSONArray()
            .put(historyItem(77, "uid history hidden", "history-uid"))
            .put(historyItem(88, "blocked history hidden", "history-keyword"))
            .put(historyItem(99, "kept history", "history-kept"))
            .put(historyItem(42, "blocked own history", "history-own")))).toString()
        fun historyItem(uid: Long, text: String, id: String): JSONObject = JSONObject().put("text", text).put("id_str", id)
            .put("user", JSONObject().put("uid", uid).put("base", JSONObject().put("name", "fixture-$uid")))
            .put("check_info", JSONObject().put("ts", 1700000000).put("ct", "fixture-check"))
        fun danmaku(uid: Long, text: String, id: String): String {
            val meta = JSONArray(); repeat(16) { meta.put(0) }
            meta.put(1, 1).put(3, 16777215).put(15, JSONObject().put("extra", JSONObject().put("id_str", id).put("dm_type", 1).toString()))
            val info = JSONArray().put(meta).put(text).put(JSONArray().put(uid).put("fixture-$uid").put(0))
            while (info.length() < 10) info.put(0)
            info.put(9, JSONObject().put("ts", 1700000000).put("ct", "fixture-check"))
            return JSONObject().put("cmd", "DANMU_MSG").put("info", info).toString()
        }
        fun superChat(uid: Long, text: String, id: Long): String = JSONObject().put("cmd", "SUPER_CHAT_MESSAGE")
            .put("data", JSONObject().put("uid", uid).put("id", id).put("message", text).put("price", 30).put("time", 60)
                .put("ts", 1700000001).put("token", "fixture-sc-token").put("user_info", JSONObject().put("uname", "fixture-$uid"))).toString()
    }
}
