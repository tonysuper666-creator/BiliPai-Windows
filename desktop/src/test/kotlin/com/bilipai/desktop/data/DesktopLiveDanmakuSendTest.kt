package com.bilipai.desktop.data

import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.bilipai.desktop.ui.DesktopLiveChatDraft
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real repository/session/API with memory-only responses. No request can reach a socket. */
class DesktopLiveDanmakuSendTest {
    private class Fixture : Closeable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val media = DesktopMediaRepository(repository)
        val requests = CopyOnWriteArrayList<Request>()
        val chats = CopyOnWriteArrayList<DesktopLiveSession>()
        val gates = CopyOnWriteArrayList<CountDownLatch>()
        var sendReply: () -> String = { """{"code":0}""" }
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-live-account", "bili_jct" to "synthetic-csrf"), AccountSummary(42, "Fixture", ""))
            // Fixture retains default retry/redirect settings: production must supply protection.
            val transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    val path = request.url.encodedPath
                    val nav = path == "/x/web-interface/nav"
                    val send = path == "/msg/send"
                    check(request.url.isHttps && request.url.port == 443 &&
                        request.url.host == if (nav) "api.bilibili.com" else "api.live.bilibili.com")
                    check(request.method == if (send) "POST" else "GET")
                    if (send) check(request.body?.isOneShot() == true) { "Actual live POST must be one-shot" }
                    val body = when (path) {
                        "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "/xlive/web-room/v1/dM/GetDMConfigByGroup" -> """{"code":0,"data":{"group":[{"color":[{"color":"16777215","name":"白","status":1}]}],"mode":[{"mode":1,"name":"滚动","status":1}]}}"""
                        "/msg/send" -> sendReply()
                        else -> error("Unowned request: ${request.method} ${request.url}")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory fixture")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            check(transport.retryOnConnectionFailure && transport.followRedirects && transport.followSslRedirects)
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            DesktopRepository::class.java.getDeclaredField("visitorInitialized").apply { isAccessible = true }.set(repository, true)
            DesktopRepository::class.java.getDeclaredField("visitorGeneration").apply { isAccessible = true }.set(repository, repository.sessionEpoch)
        }
        fun chat() = DesktopLiveSession(repository, media, 123).also { chats += it }
        fun gate() = CountDownLatch(1).also { gates += it }
        fun sends() = requests.filter { it.url.encodedPath == "/msg/send" }
        override fun close() {
            gates.forEach { it.countDown() }; chats.forEach { it.close() }
            repository.httpClient.dispatcher.executorService.shutdownNow(); repository.httpClient.connectionPool.evictAll()
        }
    }
    private fun draft() = DesktopLiveChatDraft().also {
        it.editMessage("synthetic text")
        it.selectReply(LiveDanmakuItem("reply", uid = 7L, uname = "Target", idStr = "reply-id"))
    }

    @Test fun oneSuccessfulSignedSendKeepsOriginalReplyFieldsAndPublishesOneEcho(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat(); val draft = draft(); val submitted = draft.capture()
            chat.send(submitted.message, reply = submitted.reply); draft.complete(submitted)
            val request = f.sends().single(); val body = assertNotNull(request.body)
            assertTrue(body.isOneShot())
            val buffer = Buffer(); body.writeTo(buffer)
            val fields = buffer.readUtf8().split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                java.net.URLDecoder.decode(pair[0], Charsets.UTF_8) to
                    java.net.URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
            }
            assertFalse(request.url.queryParameter("w_rid").isNullOrBlank())
            assertEquals("123", fields["roomid"]); assertEquals("synthetic text", fields["msg"])
            assertEquals("7", fields["reply_mid"]); assertEquals("1", fields["reply_attr"])
            assertEquals("Target", fields["reply_uname"]); assertEquals("reply-id", fields["replay_dmid"])
            assertEquals("synthetic-csrf", fields["csrf"]); assertEquals(fields["csrf"], fields["csrf_token"])
            assertEquals("synthetic text", chat.state.value.messages.single().text)
            assertFalse(chat.state.value.sending); assertNull(chat.state.value.error)
            assertEquals("", draft.message); assertNull(draft.reply)
        }
    }

    @Test fun ambiguousTransportFailureDoesNotSendAgainOrClearDraft(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat(); val draft = draft(); val submitted = draft.capture()
            f.sendReply = { throw IOException("synthetic response lost after accepted POST") }
            val failure = runCatching {
                chat.send(submitted.message, reply = submitted.reply); draft.complete(submitted)
            }.exceptionOrNull()
            assertIs<IOException>(failure); assertEquals(1, f.sends().size)
            assertEquals(submitted.message, draft.message); assertSame(submitted.reply, draft.reply)
            assertTrue(chat.state.value.messages.isEmpty()); assertFalse(chat.state.value.sending)
            assertTrue(chat.state.value.error.orEmpty().contains("synthetic response lost"))
        }
    }

    @Test fun rejectedSendKeepsDraftAndOnlyExplicitRetryCanSendAgain(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat(); val draft = draft(); val submitted = draft.capture()
            f.sendReply = { """{"code":-400,"message":"synthetic rejection"}""" }
            val failure = runCatching {
                chat.send(submitted.message, reply = submitted.reply); draft.complete(submitted)
            }.exceptionOrNull()
            assertIs<BiliApiException>(failure); assertEquals(1, f.sends().size)
            assertEquals(submitted.message, draft.message); assertSame(submitted.reply, draft.reply)
            assertTrue(chat.state.value.messages.isEmpty()); assertFalse(chat.state.value.sending)
            f.sendReply = { """{"code":0}""" }
            chat.send(submitted.message, reply = submitted.reply); draft.complete(submitted)
            assertEquals(2, f.sends().size); assertEquals(1, chat.state.value.messages.size)
            assertEquals("", draft.message); assertNull(draft.reply)
        }
    }

    @Test fun cancelledCallerKeepsNewDraftAndNeverPublishesLateSuccess(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.chat(); val draft = draft(); val submitted = draft.capture()
            val entered = f.gate(); val release = f.gate()
            f.sendReply = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); """{"code":0}""" }
            val caller = launch {
                chat.send(submitted.message, reply = submitted.reply); draft.complete(submitted)
            }
            try {
                withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
                draft.editMessage("typed while sending"); caller.cancel()
            } finally { release.countDown() }
            caller.join()
            assertTrue(caller.isCancelled); assertEquals(1, f.sends().size)
            assertEquals("typed while sending", draft.message); assertSame(submitted.reply, draft.reply)
            assertTrue(chat.state.value.messages.isEmpty()); assertFalse(chat.state.value.sending)
            assertNull(chat.state.value.error)
        }
    }

    @Test fun closedOrReplacedAccountCannotPublishLateSuccessOrClearDraft(): Unit = runBlocking {
        for (replaceAccount in listOf(false, true)) Fixture().use { f ->
            val chat = f.chat(); val draft = draft(); val submitted = draft.capture()
            f.sendReply = {
                if (replaceAccount) f.sessions.saveAccount(
                    mapOf("SESSDATA" to "synthetic-replacement", "bili_jct" to "synthetic-new-csrf"), AccountSummary(42, "Fixture", ""))
                else chat.close()
                """{"code":0}"""
            }
            val failure = runCatching {
                chat.send(submitted.message, reply = submitted.reply); draft.complete(submitted)
            }.exceptionOrNull()
            assertIs<CancellationException>(failure); assertEquals(1, f.sends().size)
            assertEquals(submitted.message, draft.message); assertSame(submitted.reply, draft.reply)
            assertTrue(chat.state.value.messages.isEmpty())
            withTimeout(5_000) { while (chat.state.value.sending) delay(5) }
            assertNull(chat.state.value.error)
        }
    }
}
