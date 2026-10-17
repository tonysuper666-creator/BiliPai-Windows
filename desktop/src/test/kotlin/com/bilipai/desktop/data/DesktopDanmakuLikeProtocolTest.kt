package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.DesktopOriginalDanmakuProtocol
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real extracted protocol, Retrofit, existing primary Store and owned transport.
 * The terminal interceptor seals every request in memory; no socket, native
 * player, window, user account or actual Bilibili engagement can be reached.
 * This suite proves request fields/result ownership, not the full rendered menu.
 */
class DesktopDanmakuLikeProtocolTest {
    private class Fixture(private val csrfPresent: Boolean = true) : Closeable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val requests = CopyOnWriteArrayList<Request>()
        val cookieSnapshots = CopyOnWriteArrayList<List<Cookie>>()
        val gates = CopyOnWriteArrayList<CountDownLatch>()
        @Volatile var entry = true
        @Volatile var reply: (Request) -> String = { """{"code":0,"message":"success"}""" }
        val epoch: Long
        val api: BilibiliApi
        val protocol: DesktopOriginalDanmakuProtocol
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-secondary", "bili_jct" to "secondary-csrf"),
                AccountSummary(22, "Secondary fixture", ""))
            val cookies = if (csrfPresent) mapOf("SESSDATA" to "synthetic-primary", "bili_jct" to "primary-csrf")
                else mapOf("SESSDATA" to "synthetic-primary")
            sessions.saveAccount(cookies, AccountSummary(11, "Primary fixture", ""))
            assertTrue(sessions.setPlaybackAccountMid(22, sessions.generation) { true })
            epoch = repository.sessionEpoch
            val transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.url.isHttps && request.url.host == "api.bilibili.com" && request.url.port == 443)
                    check(request.method == "POST" && request.url.encodedPath in
                        setOf("/x/v2/dm/thumbup/add", "/x/dm/recall", "/x/dm/report/add"))
                    requests += request
                    // The terminal application interceptor deliberately stops before
                    // OkHttp's Bridge/network. Observe the same existing CookieJar
                    // under the real request-owner thread locals; do not forge a header.
                    cookieSnapshots += repository.httpClient.cookieJar.loadForRequest(request.url)
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("sealed memory response")
                        .body(reply(request).toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            api = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, ::owned)
            protocol = DesktopOriginalDanmakuProtocol(api,
                { repository.ownedHomeCookie("bili_jct", epoch, ::owned) }, ::assertOwned)
        }
        fun owned() = entry && repository.sessionEpoch == epoch
        fun assertOwned() { if (!owned()) throw CancellationException("Captured danmaku entry/account retired") }
        fun gate() = CountDownLatch(1).also { gates += it }
        fun reauthenticateSameMid() {
            sessions.saveAccount(mapOf("SESSDATA" to "replacement-primary", "bili_jct" to "replacement-csrf"),
                AccountSummary(11, "New credentials", ""))
        }
        override fun close() {
            gates.forEach { it.countDown() }
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
        }
    }
    private fun fields(request: Request): Map<String, String> {
        val buffer = Buffer(); assertNotNull(request.body).writeTo(buffer)
        val pairs = buffer.readUtf8().split('&').map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
        }
        assertEquals(pairs.size, pairs.map { it.first }.distinct().size)
        return pairs.toMap()
    }
    private suspend fun entered(gate: CountDownLatch) = withContext(Dispatchers.IO) {
        check(gate.await(8, TimeUnit.SECONDS)) { "Memory transport was not entered" }
    }
    @Test fun completeOriginalLikeAndUnlikeFieldsUsePrimaryCredentialsDespiteDedicatedPlaybackAccount(): Unit = runBlocking {
        Fixture().use { f ->
            assertTrue(f.protocol.likeDanmaku(77, 991, true).isSuccess)
            assertTrue(f.protocol.likeDanmaku(77, 991, false).isSuccess)
            assertEquals(2, f.requests.size)
            f.requests.forEachIndexed { index, request ->
                assertEquals(mapOf("oid" to "77", "dmid" to "991", "op" to if (index == 0) "1" else "2",
                    "platform" to "web_player", "polaris_app_id" to "100", "polaris_platform" to "5",
                    "spmid" to "333.788.0.0", "from_spmid" to "333.788.0.0",
                    "statistics" to """{"appId":100,"platform":5,"abtest":"","version":""}""",
                    "csrf" to "primary-csrf"), fields(request))
                val cookies = f.cookieSnapshots[index].associate { it.name to it.value }
                assertEquals("synthetic-primary", cookies["SESSDATA"])
                assertFalse(cookies.values.contains("synthetic-secondary"))
            }
        }
    }
    @Test fun missingCsrfFailsBeforeAnyMutationRequest(): Unit = runBlocking {
        Fixture(csrfPresent = false).use { f ->
            val result = f.protocol.likeDanmaku(77, 991, true)
            assertTrue(result.isFailure); assertEquals("请先登录", result.exceptionOrNull()?.message)
            assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun sameMidNewCredentialEpochRejectsTheEarlierProtocolBeforeDispatch(): Unit = runBlocking {
        Fixture().use { f ->
            f.reauthenticateSameMid()
            assertFailsWith<CancellationException> { f.protocol.likeDanmaku(77, 991, true) }
            assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun lateSuccessfulResponseCannotPublishAfterCapturedAccountRetirement(): Unit = runBlocking {
        Fixture().use { f ->
            val began = f.gate(); val release = f.gate()
            f.reply = { began.countDown(); check(release.await(8, TimeUnit.SECONDS)); """{"code":0}""" }
            val attempt = async { f.protocol.likeDanmaku(77, 991, true) }
            try {
                entered(began); f.reauthenticateSameMid(); release.countDown()
                assertFailsWith<CancellationException> { attempt.await() }
                assertEquals(1, f.requests.size)
                assertEquals("primary-csrf", fields(f.requests.single())["csrf"])
            } finally { release.countDown(); attempt.cancelAndJoin() }
        }
    }
    @Test fun originalServerCodeMessagesRemainFailuresAndManualRetryStillSendsOnce(): Unit = runBlocking {
        Fixture().use { f ->
            for ((code, message) in listOf(-101 to "请先登录", -111 to "鉴权失败，请重新登录",
                -400 to "请求参数错误", 65004 to "已经点过赞了", 65005 to "已经取消点赞了")) {
                f.reply = { """{"code":$code,"message":"server fixture"}""" }
                val before = f.requests.size
                val result = f.protocol.likeDanmaku(77, 991, true)
                assertTrue(result.isFailure); assertEquals(message, result.exceptionOrNull()?.message)
                assertEquals(before+1, f.requests.size)
            }
            f.reply = { """{"code":0}""" }
            val before = f.requests.size
            assertTrue(f.protocol.likeDanmaku(77, 991, true).isSuccess)
            assertEquals(before+1, f.requests.size)
        }
    }
    @Test fun recallAndReportKeepTheirOwnOriginalFieldsWithoutLikeSourceParameters(): Unit = runBlocking {
        Fixture().use { f ->
            assertTrue(f.protocol.recallDanmaku(77, 991).isSuccess)
            assertTrue(f.protocol.reportDanmaku(77, 992, 8, "fixture report").isSuccess)
            assertEquals(mapOf("cid" to "77", "dmid" to "991", "csrf" to "primary-csrf"), fields(f.requests[0]))
            assertEquals(mapOf("cid" to "77", "dmid" to "992", "reason" to "8",
                "content" to "fixture report", "csrf" to "primary-csrf"), fields(f.requests[1]))
        }
    }
    @Test fun callerCancellationCannotBecomeSuccessfulOrRepeatTheAcceptedMemoryRequest(): Unit = runBlocking {
        Fixture().use { f ->
            val began = f.gate(); val release = f.gate()
            f.reply = { began.countDown(); check(release.await(8, TimeUnit.SECONDS)); """{"code":0}""" }
            var published = false
            val attempt = launch { f.protocol.likeDanmaku(77, 991, true).getOrThrow(); published = true }
            try {
                entered(began); attempt.cancel(); release.countDown(); attempt.join()
                assertFalse(published); assertTrue(attempt.isCancelled); assertEquals(1, f.requests.size)
            } finally { release.countDown(); attempt.cancelAndJoin() }
        }
    }
    @Test fun retiredEntryAndAmbiguousMemoryTransportFailureCannotProduceSuccess(): Unit = runBlocking {
        Fixture().use { f ->
            f.entry = false
            assertFailsWith<CancellationException> { f.protocol.likeDanmaku(77, 991, true) }
            assertTrue(f.requests.isEmpty())
            f.entry = true
            f.reply = { throw IOException("Memory-only response lost after POST admission") }
            val result = f.protocol.likeDanmaku(77, 991, true)
            assertTrue(result.isFailure); assertIs<IOException>(result.exceptionOrNull())
            assertEquals(1, f.requests.size)
        }
    }
}
