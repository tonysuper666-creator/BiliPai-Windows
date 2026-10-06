package com.bilipai.desktop.data

import com.android.purebilibili.core.network.socket.DanmakuProtocol
import com.android.purebilibili.core.network.socket.LiveDanmakuClient
import com.android.purebilibili.data.repository.LiveDanmakuSendRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual media token/signing, primary Store and original socket over a fixture-owned
 * loopback HTTP upgrade. All Bilibili API replies are memory-only; every other destination
 * is rejected before network IO. No test invokes WebSocketListener callbacks by hand. */
class DesktopLiveDanmakuOwnedSessionTest {
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
                check(bytes.size < 126)
                output.write(0x80 or opcode); output.write(bytes.size); output.write(bytes); output.flush()
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

    private class Fixture : Closeable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val media = DesktopMediaRepository(repository)
        val loopback = Loopback()
        val requests = CopyOnWriteArrayList<Request>()
        val scopes = CopyOnWriteArrayList<CoroutineScope>()
        val chats = CopyOnWriteArrayList<DesktopLiveSession>()
        val releases = CopyOnWriteArrayList<CountDownLatch>()
        var tokenReplies = 0
        var beforeReply: (Request) -> Unit = {}
        var tokenReply: () -> String = { """{"code":0,"data":{"token":"fixture-token","host_list":[{"host":"fixture.invalid","wss_port":443}]}}""" }
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-live-account", "bili_jct" to "synthetic-csrf"), AccountSummary(42, "Fixture", ""))
            val transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    if (request.header("Upgrade").equals("websocket", true)) {
                        if (!request.url.isHttps || request.url.port != 443 || request.method != "GET" ||
                            request.url.host != "fixture.invalid" || request.url.encodedPath != "/sub") throw IOException("Unowned fixture socket")
                        // Only this socket's exact private loopback endpoint can proceed.
                        val local = "http://127.0.0.1:${loopback.server.localPort}/sub".toHttpUrl()
                        chain.proceed(request.newBuilder().url(local).build())
                    } else {
                        val path = request.url.encodedPath
                        val expectedHost = if (path == "/x/web-interface/nav") "api.bilibili.com" else "api.live.bilibili.com"
                        val expectedMethod = if (path == "/msg/send") "POST" else "GET"
                        val paths = setOf("/room/v1/Room/room_init", "/xlive/web-room/v1/index/getInfoByRoom",
                            "/x/web-interface/nav", "/xlive/web-room/v1/index/getDanmuInfo",
                            "/xlive/web-room/v1/dM/GetDMConfigByGroup", "/xlive/web-room/v1/dM/gethistory", "/msg/send")
                        if (!request.url.isHttps || request.url.port != 443 || request.url.host != expectedHost ||
                            request.method != expectedMethod || path !in paths) throw IOException("External transport forbidden: ${request.method} ${request.url}")
                        beforeReply(request)
                        val payload = when (request.url.encodedPath) {
                            "/room/v1/Room/room_init" -> """{"code":0,"data":{"room_id":123,"live_status":1}}"""
                            "/xlive/web-room/v1/index/getInfoByRoom" -> """{"code":0,"data":{"room_info":{"room_id":123,"title":"Fixture","live_status":1},"anchor_info":{"base_info":{"uname":"Synthetic"}}}}"""
                            "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                            "/xlive/web-room/v1/index/getDanmuInfo" -> { tokenReplies++; tokenReply() }
                            "/xlive/web-room/v1/dM/GetDMConfigByGroup" -> """{"code":0,"data":{"group":[{"color":[{"color":"16777215","name":"白","status":1}]}],"mode":[{"mode":1,"name":"滚动","status":1}]}}"""
                            "/xlive/web-room/v1/dM/gethistory" -> """{"code":0,"data":{"room":[]}}"""
                            "/msg/send" -> """{"code":0}"""
                            else -> throw IOException("External transport forbidden: ${request.method} ${request.url}")
                        }
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory fixture")
                            .body(payload.toResponseBody("application/json".toMediaType())).build()
                    }
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            DesktopRepository::class.java.getDeclaredField("visitorInitialized").apply { isAccessible = true }.set(repository, true)
            DesktopRepository::class.java.getDeclaredField("visitorGeneration").apply { isAccessible = true }.set(repository, repository.sessionEpoch)
        }
        fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        fun chat() = DesktopLiveSession(repository, media, 123).also { chats += it }
        fun release() = CountDownLatch(1).also { releases += it }
        override fun close() {
            releases.forEach { it.countDown() }; chats.forEach { it.close() }; scopes.forEach { it.cancel() }
            loopback.close(); repository.httpClient.dispatcher.executorService.shutdownNow(); repository.httpClient.connectionPool.evictAll()
        }
    }
    private suspend fun until(predicate: () -> Boolean) { withTimeout(5_000) { while (!predicate()) delay(5) } }
    private suspend fun await(latch: CountDownLatch) { withContext(Dispatchers.IO) { check(latch.await(5, TimeUnit.SECONDS)) } }
    private suspend fun awaitClosed(peer: Loopback.Peer) {
        await(peer.ended)
        assertFalse(peer.error is SocketTimeoutException, "A server timeout is not socket cleanup")
        assertTrue(peer.peerCloseObserved || peer.eofObserved || peer.error is SocketException,
            "Expected actual peer close/EOF/reset, received ${peer.error}")
    }

    @Test fun actualTokenRetryUsesOwnedSignerAndResolvedRoom(): Unit = runBlocking {
        Fixture().use { f ->
            f.tokenReply = { if (f.tokenReplies == 1) """{"code":-352,"message":"refresh"}""" else """{"code":0,"data":{"token":"fixture-token","host_list":[{"host":"fixture.invalid","wss_port":443}]}}""" }
            val socket = f.media.liveDanmakuClient(f.scope(), 9)
            try {
                until { f.loopback.connections.singleOrNull()?.authenticated?.count == 0L }
                val token = f.requests.filter { it.url.encodedPath.endsWith("getDanmuInfo") }
                assertEquals(2, token.size); assertEquals(2, f.requests.count { it.url.encodedPath.endsWith("/nav") })
                token.forEach { assertEquals("123", it.url.queryParameter("id")); assertEquals("0", it.url.queryParameter("type")); assertFalse(it.url.queryParameter("w_rid").isNullOrBlank()) }
                val auth = f.loopback.connections.single().packets.first { it.operation == DanmakuProtocol.OP_AUTH }
                val json = org.json.JSONObject(String(auth.body)); assertEquals(123L, json.getLong("roomid")); assertEquals(42L, json.getLong("uid"))
            } finally { socket.disconnect() }
        }
    }

    @Test fun retiredFirstTokenReplyCannotRefreshOrOpenSocket(): Unit = runBlocking {
        Fixture().use { f ->
            f.beforeReply = { if (it.url.encodedPath.endsWith("getDanmuInfo")) f.sessions.logout() }
            assertTrue(runCatching { f.media.liveDanmakuClient(f.scope(), 123) }.isFailure)
            assertEquals(1, f.tokenReplies); assertTrue(f.loopback.connections.isEmpty())
            assertEquals(1, f.requests.count { it.url.encodedPath.endsWith("/nav") })
        }
    }

    @Test fun retiredForceRefreshNavCannotRequestSecondToken(): Unit = runBlocking {
        Fixture().use { f ->
            f.tokenReply = { """{"code":-352,"message":"refresh"}""" }
            var nav = 0
            f.beforeReply = { if (it.url.encodedPath.endsWith("/nav") && ++nav == 2) f.sessions.logout() }
            assertTrue(runCatching { f.media.liveDanmakuClient(f.scope(), 123) }.isFailure)
            assertEquals(1, f.tokenReplies); assertEquals(2, nav); assertTrue(f.loopback.connections.isEmpty())
        }
    }

    @Test fun cancelledTokenCallerCannotOpenSocket(): Unit = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = f.release()
            f.beforeReply = { if (it.url.encodedPath.endsWith("getDanmuInfo")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val job = launch { f.media.liveDanmakuClient(f.scope(), 123) }
            await(entered); job.cancel(); release.countDown(); job.join()
            assertTrue(job.isCancelled); assertTrue(f.loopback.connections.isEmpty())
        }
    }

    @Test fun realNormalCloseReconnectsAfterLookupCallerHasCompleted(): Unit = runBlocking {
        Fixture().use { f ->
            val lookup = async { f.media.liveDanmakuClient(f.scope(), 123) }
            val socket = lookup.await()
            try {
                assertTrue(lookup.isCompleted)
                until { f.loopback.connections.singleOrNull()?.packets?.any { it.operation == DanmakuProtocol.OP_HEARTBEAT } == true && socket.isConnected }
                val old = f.loopback.connections.single(); old.normalClose()
                until { f.loopback.connections.size == 2 && f.loopback.connections.last().authenticated.count == 0L && socket.isConnected }
                assertEquals(1, f.tokenReplies) // fixed original reconnect deliberately reuses its connect token
                assertEquals(2, f.requests.count { it.header("Upgrade").equals("websocket", true) })
            } finally { socket.disconnect() }
        }
    }

    private class ReturnDispatcher : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.put(block) }
    }
    @Test fun promptCancellationOnActualIoReturnClosesUnpublishedSocket(): Unit = runBlocking {
        Fixture().use { f ->
            val dispatcher = ReturnDispatcher(); val liveScope = f.scope()
            var delivered = false
            val caller = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                f.media.liveDanmakuClient(liveScope, 123); delivered = true
            }
            val resume = withContext(Dispatchers.IO) { assertNotNull(dispatcher.tasks.poll(5, TimeUnit.SECONDS)) }
            until { f.loopback.connections.singleOrNull()?.authenticated?.count == 0L }
            caller.cancel(); resume.run(); caller.join()
            assertFalse(delivered); assertTrue(caller.isCancelled); assertTrue(liveScope.isActive)
            awaitClosed(f.loopback.connections.single())
        }
    }

    @Test fun actualSessionEpochRetirementClosesSocketAndCannotResurrect(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat(); chat.start()
            until { chat.state.value.connected && f.loopback.connections.singleOrNull()?.authenticated?.count == 0L }
            f.sessions.logout()
            withTimeout(5_000) { chat.state.first { it.status == "弹幕连接已关闭" } }
            awaitClosed(f.loopback.connections.single())
            chat.start() // stale UI callback is inert, never adopts the successor account
            assertFalse(chat.state.value.connected); assertFalse(chat.state.value.sending)
            assertEquals(1, f.tokenReplies); assertEquals(1, f.loopback.connections.size)
        }
    }

    @Test fun visitorCookieSpiAndVipRefreshDoNotRetirePrimarySession(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat(); val epoch = f.repository.sessionEpoch
            f.sessions.saveFromResponse("https://www.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder().name("sid").value("fixture-cookie").domain("bilibili.com").path("/").build()))
            f.sessions.saveSpiCookies(mapOf("buvid3" to "fixture-spi")); f.sessions.saveProfileVipStatus(true)
            assertEquals(epoch, f.repository.sessionEpoch)
            chat.start()
            until { chat.state.value.connected }
            assertEquals(1, f.tokenReplies)
            f.sessions.saveAccount(mapOf("SESSDATA" to "replacement", "bili_jct" to "synthetic-csrf"), AccountSummary(42, "Fixture", "", true))
            withTimeout(5_000) { chat.state.first { it.status == "弹幕连接已关闭" } }
            awaitClosed(f.loopback.connections.single())
        }
    }

    @Test fun closedSessionOptionalHistoryCannotReachTokenOrPublishLateItems(): Unit = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = f.release()
            f.beforeReply = { if (it.url.encodedPath.endsWith("gethistory")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val chat = f.chat(); chat.start(); await(entered); chat.close(); release.countDown()
            val scope = DesktopLiveSession::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(chat) as CoroutineScope
            scope.coroutineContext[Job]!!.join()
            assertTrue(chat.state.value.messages.isEmpty()); assertEquals("弹幕连接已关闭", chat.state.value.status)
            assertEquals(0, f.tokenReplies); assertTrue(f.loopback.connections.isEmpty())
        }
    }

    @Test fun actualRetiredSendPermissionAndSignedFallbackCannotMutateSuccessor(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat()
            f.beforeReply = { if (it.url.encodedPath.endsWith("GetDMConfigByGroup")) f.sessions.logout() }
            assertTrue(runCatching { chat.send("synthetic") }.isFailure)
            assertFalse(f.requests.any { it.method == "POST" }); assertTrue(chat.state.value.messages.isEmpty())
            withTimeout(5_000) { chat.state.first { it.status == "弹幕连接已关闭" } }
            assertFails { chat.send("stale") }
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun lateActualSendResponseCannotPublishSelfMessageOrFallback(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat()
            f.beforeReply = { if (it.url.encodedPath == "/msg/send") f.sessions.logout() }
            assertTrue(runCatching { chat.send("synthetic") }.isFailure)
            assertEquals(1, f.requests.count { it.method == "POST" }); assertTrue(chat.state.value.messages.isEmpty())
            withTimeout(5_000) { chat.state.first { it.status == "弹幕连接已关闭" } }
            assertFalse(chat.state.value.sending); assertNull(chat.state.value.error)
        }
    }
}
