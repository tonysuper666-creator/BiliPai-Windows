package com.bilipai.desktop.data

import com.android.purebilibili.core.network.socket.DanmakuProtocol
import com.android.purebilibili.feature.live.LiveRealtimeAction
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.ui.DesktopLivePageMemory
import com.bilipai.desktop.ui.captureLiveSourceBinding
import com.bilipai.desktop.ui.desktopLiveRecoveryPorts
import com.bilipai.desktop.ui.installLivePlayback
import com.bilipai.desktop.ui.nativePlaybackSource
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
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real Store/owned HTTP/Session.start/original WS decoder and vote parser/merge.
 * Only the exact fixture-owned 127.0.0.1 random-port RFC6455 endpoint reaches a socket;
 * Bilibili API, HTML, SPI, WBI and play-URL replies are memory-only.
 * No private owner/state/apply mutation, external account, browser, DLL or GPU.
 * Native source tests cover source-CAS/command admission, without ABI output or seek ACK. */
class DesktopLiveVoteOwnedSessionTest {
    // Copied complete existing Loopback helper, with one RFC6455 length-encoding edit.
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

    private inner class Fixture(private val blockedPath: String? = null) {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val media = DesktopMediaRepository(repository)
        val loopback = Loopback()
        val requests = CopyOnWriteArrayList<Request>()
        val actions = CopyOnWriteArrayList<LiveRealtimeAction>()
        val chatSessions = CopyOnWriteArrayList<DesktopLiveSession>()
        val actionsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val activeCalls = AtomicInteger()
        val playReplies = AtomicInteger()
        val gate = ReplyGate()
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
                            check(request.method == "GET" && request.url.isHttps && request.url.port == 443 && request.url.host == host)
                            if (path == blockedPath) gate.hold()
                            val body = when (path) {
                                "/" -> "<html>memory-only visitor bootstrap</html>"
                                "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                                "/room/v1/Room/room_init" -> """{"code":0,"data":{"room_id":123,"live_status":1}}"""
                                "/xlive/web-room/v1/index/getInfoByRoom" -> """{"code":0,"data":{"room_info":{"room_id":123,"title":"Fixture","live_status":1},"anchor_info":{"base_info":{"uname":"Synthetic"}}}}"""
                                "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":42,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                                "/xlive/web-room/v1/index/getDanmuInfo" -> """{"code":0,"data":{"token":"fixture-token","host_list":[{"host":"fixture.invalid","wss_port":443}]}}"""
                                "/xlive/web-room/v1/dM/GetDMConfigByGroup" -> """{"code":0,"data":{"group":[{"color":[{"color":"16777215","name":"白","status":1}]}],"mode":[{"mode":1,"name":"滚动","status":1}]}}"""
                                "/xlive/web-room/v1/dM/gethistory" -> """{"code":0,"data":{"room":[]}}"""
                                PANEL -> JSONObject().put("code", 0).put("data", JSONObject()
                                    .put("vote_info", vote(500, 4, "  snapshot question  "))).toString()
                                HISTORY -> JSONObject().put("code", 0).put("data", JSONObject().put("history", JSONArray()
                                    .put(vote(88, 5, "  finished snapshot  ")).put(vote(89, 5, "  ")))).toString()
                                "/xlive/web-room/v2/index/getRoomPlayInfo" -> {
                                    val index = playReplies.incrementAndGet()
                                    """{"code":0,"data":{"current_quality":150,"quality_description":[{"qn":150,"desc":"高清"}],"durl":[{"url":"https://cdn.fixture.invalid/live-$index.flv"}]}}"""
                                }
                                else -> throw IOException("External transport forbidden: ${request.method} ${request.url}")
                            }
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory fixture")
                                .body(body.toResponseBody("application/json".toMediaType())).build()
                        }
                    } finally { activeCalls.decrementAndGet() }
                }.build()
            // Existing fixture transport replacement only; no Store/lifetime/native authority is modified.
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        }
        fun session(roomId: Long): DesktopLiveSession = DesktopLiveSession(repository, media, roomId).also { chat ->
            chatSessions += chat
            actionsScope.launch(start = CoroutineStart.UNDISPATCHED) { chat.actions.collect { actions += it } }
        }
        suspend fun startCanonical(): DesktopLiveSession {
            val room = media.liveRoom(9)
            assertEquals(123L, room.roomId)
            return session(room.roomId).also { it.start() }
        }
        // Read-only observation for joining actual coroutine work, never supplied to ownership predicates.
        fun sessionJob(chat: DesktopLiveSession): Job = (DesktopLiveSession::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(chat) as CoroutineScope).coroutineContext.job
        suspend fun dispose() = withContext(NonCancellable) {
            try {
                gate.open(); chatSessions.forEach { it.close() }
                withTimeout(5_000) { chatSessions.forEach { sessionJob(it).join() } }
                actionsScope.cancel(); withTimeout(5_000) { actionsScope.coroutineContext.job.join() }
                until { activeCalls.get() == 0 }
                // Capture peers only after real Session/HTTP work drains, before forced disposal.
                val peers = loopback.connections.toList()
                peers.forEach { awaitClosed(it) }
                assertEquals(peers, loopback.connections.toList(), "A late fixture peer appeared after Session/HTTP drain")
            } finally {
                gate.open()
                try {
                    chatSessions.forEach { it.close() }; actionsScope.cancel()
                } finally {
                    try { loopback.close() } finally {
                        try { repository.httpClient.dispatcher.executorService.shutdownNow() }
                        finally { repository.httpClient.connectionPool.evictAll() }
                    }
                }
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
        assertFalse(peer.error is SocketTimeoutException, "A server timeout is not socket cleanup")
        assertTrue(peer.error == null || peer.error is SocketException,
            "Unexpected fixture peer protocol failure: ${peer.error}")
        assertTrue(peer.peerCloseObserved || peer.eofObserved || peer.error is SocketException,
            "Expected actual peer close/EOF/reset, received ${peer.error}")
    }
    private suspend fun ready(f: Fixture, chat: DesktopLiveSession): Loopback.Peer {
        until { f.loopback.connections.singleOrNull()?.authenticated?.count == 0L }
        val peer = f.loopback.connections.single()
        // A non-vote wire probe confirms the actual decoder/Session collector is ready.
        // It changes neither chat messages nor votes and is not an ownership input.
        withTimeout(5_000) {
            while (f.actions.none { it is LiveRealtimeAction.UpdateRoomTitle && it.title == "fixture-collector-ready" }) {
                peer.message("""{"cmd":"ROOM_CHANGE","data":{"title":"fixture-collector-ready"}}""")
                delay(10)
            }
        }
        until { chat.state.value.connected }
        return peer
    }
    private suspend fun heldHydrationChildren(f: Fixture, chat: DesktopLiveSession): Pair<Job, List<Job>> {
        var startup: Job? = null
        until {
            // Actual start child has two children here: blocked hydrate + persistent connection poll.
            startup = f.sessionJob(chat).children.singleOrNull { it.children.count() == 2 }
            startup != null
        }
        val actual = checkNotNull(startup)
        return actual to actual.children.toList().also { assertEquals(2, it.size); assertTrue(it.all { child -> child.isActive }) }
    }

    @Test fun actualCanonicalHydrationUsesBothGetEndpointsWhileSocketStartupDoesNotWait(): Unit = runBlocking {
        Fixture(PANEL).useSuspending { f ->
            val chat = f.startCanonical(); await(f.gate.entered)
            val peer = ready(f, chat)
            assertEquals(1, f.requests.count { it.url.encodedPath == PANEL })
            assertFalse(f.requests.any { it.url.encodedPath == HISTORY })
            assertNull(chat.voteSnapshot.value.current)
            val initialRoom = f.requests.first { it.url.encodedPath.endsWith("/room_init") }
            assertEquals("9", initialRoom.url.queryParameter("id"))
            val auth = peer.packets.first { it.operation == DanmakuProtocol.OP_AUTH }
            assertEquals(123L, JSONObject(String(auth.body)).getLong("roomid"))
            assertEquals(42L, JSONObject(String(auth.body)).getLong("uid"))
            assertTrue(f.requests.any { it.url.encodedPath == "/" })
            assertEquals(1, f.requests.count { it.url.encodedPath.endsWith("/spi") })
            val token = f.requests.single { it.url.encodedPath.endsWith("getDanmuInfo") }.url
            assertEquals("123", token.queryParameter("id")); assertFalse(token.queryParameter("w_rid").isNullOrBlank())
            f.gate.open()
            until { chat.voteSnapshot.value.current?.interactionId == 500L }
            val snapshot = chat.voteSnapshot.value
            assertEquals("snapshot question", snapshot.current?.question)
            assertEquals(listOf(1f, 0f), snapshot.current?.options?.map { it.percent })
            assertEquals(listOf(88L), snapshot.history.map { it.interactionId })
            assertEquals("finished snapshot", snapshot.history.single().question)
            val getRequests = f.requests.filter { it.url.encodedPath in setOf(PANEL, HISTORY) }
            assertEquals(listOf(PANEL, HISTORY), getRequests.map { it.url.encodedPath })
            getRequests.forEach { assertEquals("GET", it.method); assertEquals(setOf("room_id"), it.url.queryParameterNames)
                assertEquals("123", it.url.queryParameter("room_id")) }
            val requestCount = f.requests.size
            chat.start()
            assertEquals(requestCount, f.requests.size); assertEquals(1, f.loopback.connections.size)
            assertTrue(chat.state.value.messages.isEmpty()) // HTTP hydration does not invent a realtime announcement.
        }
    }

    @Test fun realVotePacketWinsOverLateInitialSnapshotAfterActualHydrationJobCompletes(): Unit = runBlocking {
        Fixture(HISTORY).useSuspending { f ->
            val chat = f.startCanonical(); await(f.gate.entered)
            val peer = ready(f, chat)
            val (startup, children) = heldHydrationChildren(f, chat)
            peer.message(packet(900, 4, "wire vote first"))
            until { chat.voteSnapshot.value.current?.interactionId == 900L }
            val realtime = chat.voteSnapshot.value
            assertTrue(realtime.history.isEmpty())
            until { chat.state.value.messages.count { it.uname == "投票" } == 1 }
            assertEquals(1, chat.state.value.messages.count { it.uname == "投票" })
            f.gate.open()
            until { children.count { it.isCompleted } == 1 }
            assertTrue(startup.isActive); assertEquals(1, children.count { it.isActive })
            assertSame(realtime, chat.voteSnapshot.value)
            assertEquals("wire vote first", chat.voteSnapshot.value.current?.question)
            assertTrue(chat.voteSnapshot.value.history.isEmpty()) // Late HTTP history88 never overwrites realtime.
            assertEquals(listOf(PANEL, HISTORY), f.requests.filter { it.url.encodedPath in setOf(PANEL, HISTORY) }.map { it.url.encodedPath })
            assertTrue(chat.state.value.connected)
        }
    }

    @Test fun originalWireVoteMergeDeduplicatesInactiveHistoryCapsTenAndEmitsAnnouncements(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val chat = f.startCanonical(); val peer = ready(f, chat)
            until { chat.voteSnapshot.value.current?.interactionId == 500L }
            peer.message(packet(900, 4, "active wire vote"))
            until { chat.voteSnapshot.value.current?.interactionId == 900L }
            assertEquals(listOf(88L), chat.voteSnapshot.value.history.map { it.interactionId })
            assertEquals(2, chat.voteSnapshot.value.current?.options?.size)
            assertEquals(listOf(1f, 0f), chat.voteSnapshot.value.current?.options?.map { it.percent })
            for (id in 1L..12L) {
                peer.message(packet(id, 5, "finished-$id"))
                until { chat.voteSnapshot.value.current?.interactionId == id }
            }
            assertEquals((12L downTo 3L).toList(), chat.voteSnapshot.value.history.map { it.interactionId })
            peer.message(packet(12, 5, "same-id refreshed result"))
            until { chat.voteSnapshot.value.current?.question == "same-id refreshed result" }
            val final = chat.voteSnapshot.value
            assertEquals(10, final.history.size); assertEquals(1, final.history.count { it.interactionId == 12L })
            assertEquals("same-id refreshed result", final.history.first().question)
            assertFalse(checkNotNull(final.current).isActive)
            until { chat.state.value.messages.count { it.uname == "投票" } == 14 }
            assertEquals(14, chat.state.value.messages.size)
            assertTrue(chat.state.value.messages.all { it.isSystem && it.uname == "投票" })
            assertTrue(chat.state.value.messages.first().text.contains("active wire vote"))
            assertTrue(chat.state.value.messages.last().text.contains("same-id refreshed result"))
            until { f.actions.count { it is LiveRealtimeAction.UpdateVote } == 14 }
            assertEquals(14, f.actions.count { it is LiveRealtimeAction.UpdateVote })
        }
    }

    @Test fun actualCloseAndPrimaryAccountAbaRejectBlockedHydrationWithoutLatePublicationOrReconnect(): Unit = runBlocking {
        for ((path, retireAccount) in listOf(PANEL to false, HISTORY to true)) {
            Fixture(path).useSuspending { f ->
                val chat = f.startCanonical(); await(f.gate.entered); val peer = ready(f, chat)
                val initial = chat.voteSnapshot.value; val epoch = f.repository.sessionEpoch
                assertNull(initial.current); assertTrue(initial.history.isEmpty())
                if (retireAccount) {
                    assertTrue(f.sessions.activateAccount(41)); assertTrue(f.sessions.activateAccount(42))
                    assertEquals(42L, f.sessions.activeAccountMid()); assertTrue(f.repository.sessionEpoch > epoch)
                    until { !f.sessionJob(chat).isActive }
                } else chat.close()
                f.gate.open(); withTimeout(5_000) { f.sessionJob(chat).join() }
                until { f.activeCalls.get() == 0 }; awaitClosed(peer)
                assertSame(initial, chat.voteSnapshot.value)
                assertNull(chat.voteSnapshot.value.current); assertTrue(chat.voteSnapshot.value.history.isEmpty())
                assertTrue(chat.state.value.messages.isEmpty()); assertFalse(chat.state.value.connected)
                assertEquals("弹幕连接已关闭", chat.state.value.status)
                if (path == PANEL) assertFalse(f.requests.any { it.url.encodedPath == HISTORY })
                else assertEquals(1, f.requests.count { it.url.encodedPath == HISTORY })
                val requests = f.requests.size
                chat.start() // A retired Session never captures a later account or restarts its socket.
                assertFalse(f.sessionJob(chat).isActive); assertEquals(requests, f.requests.size)
                assertEquals(1, f.loopback.connections.size)
            }
        }
    }

    @Test fun sameRoomActualSourceRefreshKeepsCompletedRequestPublicationAndVoteSessionAlive(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = MpvPlayer()
            val memory = DesktopLivePageMemory(parent, player)
            try {
                val epoch = f.repository.sessionEpoch
                val room = f.media.liveRoom(9, epoch)
                val chat = f.session(room.roomId)
                val ports = desktopLiveRecoveryPorts(f.repository, f.media, epoch, { false },
                    { _, _ -> error("No mini-window callback belongs to this CPU fixture") })
                memory.launchRequest {
                    val caller = currentCoroutineContext().job
                    val info = f.media.livePlaybackInfo(room, 10000, expectedEpoch = epoch,
                        stillOwned = { caller.isActive && memory.playJob === caller && memory.scope.isActive })
                    assertTrue(ports.admit {
                        memory.room = room
                        player.loadVersioned(memory.nativePlaybackSource(info, room.roomId, ports))
                        memory.installLivePlayback(info, checkNotNull(player.currentSourceSnapshot()), ports)
                        memory.loaded = true
                        memory.connectChat(chat, null) { }
                    })
                }
                val initialRequest = checkNotNull(memory.playJob); withTimeout(5_000) { initialRequest.join() }
                assertTrue(initialRequest.isCompleted); assertFalse(initialRequest.isActive); assertFalse(initialRequest.isCancelled)
                val peer = ready(f, chat)
                until { chat.voteSnapshot.value.current?.interactionId == 500L }
                val originalVote = chat.voteSnapshot.value
                val oldSource = checkNotNull(player.currentSourceSnapshot())
                val oldPublication = checkNotNull(oldSource.source.nativePublication)
                var admitted = 0
                assertTrue(oldPublication.admit { admitted++ })
                val binding = checkNotNull(memory.captureLiveSourceBinding())
                memory.launchRequest {
                    val caller = currentCoroutineContext().job
                    val next = f.media.livePlaybackInfo(room, memory.quality, expectedEpoch = epoch,
                        stillOwned = { caller.isActive && memory.playJob === caller && binding.current() })
                    assertTrue(binding.refresh(next, caller))
                }
                val refreshRequest = checkNotNull(memory.playJob); withTimeout(5_000) { refreshRequest.join() }
                assertTrue(refreshRequest.isCompleted); assertFalse(refreshRequest.isActive); assertFalse(refreshRequest.isCancelled)
                val childSource = checkNotNull(player.currentSourceSnapshot())
                assertEquals(oldSource.sourceVersion, childSource.sourceVersion)
                assertNotEquals(oldSource.source.videoUrl, childSource.source.videoUrl)
                assertFalse(player.ownsSourceSnapshot(oldSource)); assertTrue(player.ownsSourceSnapshot(childSource))
                assertFalse(oldPublication.admit { admitted++ })
                assertTrue(checkNotNull(childSource.source.nativePublication).admit { admitted++ })
                assertEquals(2, admitted)
                assertSame(chat, memory.chat); assertSame(originalVote, chat.voteSnapshot.value)
                assertTrue(chat.state.value.connected); assertEquals(1, f.loopback.connections.size)
                peer.message(packet(901, 4, "wire vote after actual same-room refresh"))
                until { chat.voteSnapshot.value.current?.interactionId == 901L }
                assertEquals(2, f.playReplies.get())
                assertEquals(1, f.requests.count { it.url.encodedPath == PANEL })
                assertEquals(1, f.requests.count { it.url.encodedPath == HISTORY })
            } finally {
                memory.close(); player.close(); parent.cancel()
                withTimeout(5_000) { parent.coroutineContext.job.join() }
            }
        }
    }

    private companion object {
        const val PANEL = "/xlive/app-room/v1/dm/interaction/votePanel"
        const val HISTORY = "/xlive/app-room/v1/dm/interaction/voteHistory"
        fun vote(id: Long, status: Int, question: String) = JSONObject()
            .put("interaction_id", id).put("status", status).put("question", question)
            .put("duration", 60_000).put("left_duration", if (status == 4) 30_000 else 0)
            .put("result_text", "fixture result").put("etime_str", "2026-10-11")
            .put("options", JSONArray()
                .put(JSONObject().put("idx", 1).put("desc", "Yes").put("percent", 1.5))
                .put(JSONObject().put("idx", 2).put("desc", "No").put("percent", -0.5))
                .put(JSONObject().put("idx", 3).put("desc", "").put("percent", 0.2)))
        fun packet(id: Long, status: Int, question: String) = JSONObject().put("cmd", "DM_INTERACTION")
            .put("data", JSONObject().put("type", 101).put("data", vote(id, status, question).toString())).toString()
    }
}
