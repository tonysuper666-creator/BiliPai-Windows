package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BuvidApi
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.repository.ContentRequestException
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real Store/Binding -> generated original protocol -> admitted Retrofit/OkHttp.
 * All HTTP origins are intercepted before a socket; no native/player success is
 * manufactured. Exception provenance is asserted through the real fallback.
 * 跟随上游有意改动：v0.6.7 起登录态回退只走 DASH，不是放宽测试。
 * The four affected cases require zero guest calls; a successful guest fixture
 * must never mask a logged-in DASH failure. Cancellation/visitor/IO cases stay intact. */
class DesktopOriginalVideoFallbackCauseProtocolTest {
    private enum class Mode { HTTP412, HTTP503, BODY503, GUEST_SUCCESS, EMPTY }
    private class Fixture : AutoCloseable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val entry = SupervisorJob()
        var current = true
        var mode = Mode.HTTP412
        var bootstrapStatus = 200
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val requests = CopyOnWriteArrayList<Request>()
        val ownedPlay = AtomicInteger()
        val guestPlay = AtomicInteger()
        val navCalls = AtomicInteger()
        val transport: OkHttpClient
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"),
                AccountSummary(41, "Fixture", ""))
            val dispatcher = Dispatcher(Executors.newCachedThreadPool { task ->
                Thread(task, "fallback56-fixture-http").apply {
                    isDaemon = true
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
                }
            })
            transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
                .retryOnConnectionFailure(false).dispatcher(dispatcher).addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    check(request.method == "GET" && request.url.isHttps && request.url.port == 443)
                    var status = 200
                    val body = when (request.url.host to request.url.encodedPath) {
                        "www.bilibili.com" to "/" -> { status = bootstrapStatus; "memory bootstrap" }
                        "api.bilibili.com" to "/x/frontend/finger/spi" ->
                            """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "api.bilibili.com" to "/x/web-interface/nav" -> {
                            navCalls.incrementAndGet()
                            """{"code":0,"data":{"isLogin":true,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        }
                        "api.bilibili.com" to "/x/player/wbi/playurl", "api.bilibili.com" to "/x/player/playurl" -> {
                            val owned = sessions.requestPlaybackAuthorization.get() != null
                            val cookies = transport.cookieJar.loadForRequest(request.url)
                            if (owned) { ownedPlay.incrementAndGet(); check(sessions.requestGuestBuvid3.get() == null) }
                            else {
                                guestPlay.incrementAndGet(); checkNotNull(sessions.requestGuestBuvid3.get())
                                check(cookies.none { it.name == "SESSDATA" || it.name == "bili_jct" })
                            }
                            when {
                                mode == Mode.EMPTY -> """{"code":0,"data":null}"""
                                mode == Mode.GUEST_SUCCESS && !owned -> {
                                    val qn = request.url.queryParameter("qn")?.toIntOrNull() ?: 80
                                    """{"code":0,"data":{"quality":$qn,"accept_quality":[$qn],"durl":[{"url":"https://fixture.invalid/guest-video","length":20000,"size":1000}]}}"""
                                }
                                request.url.encodedPath == "/x/player/wbi/playurl" -> when (mode) {
                                    Mode.BODY503 -> """{"code":503,"message":"body rejection"}"""
                                    Mode.HTTP503 -> { status = 503; """{"code":-403,"message":"unused HTTP body"}""" }
                                    else -> { status = 412; """{"code":-403,"message":"unused HTTP body"}""" }
                                }
                                else -> """{"code":-403,"message":"secondary rejected"}"""
                            }
                        }
                        else -> throw IOException("No fixture transport for " + request.url.encodedPath)
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        }
        suspend fun warm() {
            repository.ensureOwnedHomeSession(repository.sessionEpoch, { current },
                repository.ownedHomeService(BuvidApi::class.java, "https://api.bilibili.com/", repository.sessionEpoch, { current }))
        }
        suspend fun binding(decorate: ((Call.Factory) -> Call.Factory)? = null) =
            DesktopOriginalVideoRepositoryBinding.capture(repository, repository.sessionEpoch, entry, { current },
                { action -> if (!current) false else { action(); true } }, PlayerPreferences(), null,
                emptySet(), false, { false }, { false }, { false }, { false }, { _, _ -> false },
                onPlaybackAuthorizationRetired = { error("Prewarmed visitor must not retire this request") }, decorateCalls = decorate)
        override fun close() {
            current = false; entry.cancel(); transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            assertTrue(uncaught.isEmpty(), "Unexpected HTTP background failures: $uncaught")
        }
    }

    @Test fun actualPrimaryHttp412SurvivesEveryRejectedFallback() = runBlocking<Unit> {
        withTimeout(12_000) { Fixture().use { f ->
            f.warm(); val binding = f.binding()
            val failure = assertFailsWith<BiliApiException> { binding.protocol.getInitialPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            assertEquals(412, failure.apiCode)
            assertEquals(412, assertIs<VideoLoadError.ApiError>(desktopOriginalVideoLoadError(failure)).code)
            assertTrue(desktopOriginalVideoLoadCanRetry(failure))
            assertTrue(f.ownedPlay.get() > 0); assertEquals(0, f.guestPlay.get())
            assertFalse(f.requests.any { it.url.encodedPath == "/x/player/playurl" })
            binding.assertCurrent(); assertTrue(f.repository.isPlaybackReceiptCurrent(binding.receipt))
        } }
    }

    @Test fun actualPrimaryHttp503KeepsRetrofitCauseAfterFallbackExhaustion() = runBlocking<Unit> {
        withTimeout(12_000) { Fixture().use { f ->
            f.mode = Mode.HTTP503; f.warm(); val binding = f.binding()
            val failure = assertFailsWith<retrofit2.HttpException> { binding.protocol.getInitialPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            assertEquals(503, failure.code())
            assertEquals("/x/player/wbi/playurl", failure.response()?.raw()?.request?.url?.encodedPath)
            assertIs<VideoLoadError.UnknownError>(desktopOriginalVideoLoadError(failure))
            assertTrue(f.ownedPlay.get() > 0); assertEquals(0, f.guestPlay.get()); binding.assertCurrent()
        } }
    }

    @Test fun actualVisitorHttp503RemainsNetworkAndRetryable() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { f ->
            f.bootstrapStatus = 503; val binding = f.binding()
            val failure = assertFailsWith<BiliApiException> { binding.protocol.getInitialPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            assertEquals(503, failure.apiCode)
            assertSame(VideoLoadError.NetworkError, desktopOriginalVideoLoadError(failure))
            assertTrue(desktopOriginalVideoLoadCanRetry(failure))
            assertTrue(desktopOriginalApiFailure(desktopOriginalVideoLoadError(failure)).automaticRecovery)
            assertEquals(listOf("/"), f.requests.map { it.url.encodedPath })
            assertEquals(0, f.ownedPlay.get()); assertEquals(0, f.guestPlay.get()); binding.assertCurrent()
        } }
    }

    @Test fun actualResponseBody503HasTypedApiDomainInsteadOfHttpDomain() = runBlocking<Unit> {
        withTimeout(12_000) { Fixture().use { f ->
            f.mode = Mode.BODY503; f.warm(); val binding = f.binding()
            val failure = assertFailsWith<ContentRequestException> { binding.protocol.getInitialPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            assertEquals(503, failure.code)
            val error = assertIs<VideoLoadError.ApiError>(desktopOriginalVideoLoadError(failure))
            assertEquals(503, error.code); assertFalse(desktopOriginalVideoLoadCanRetry(failure))
            assertFalse(desktopOriginalApiFailure(error).automaticRecovery)
            assertTrue(f.ownedPlay.get() > 0); assertEquals(0, f.guestPlay.get()); binding.assertCurrent()
        } }
    }

    @Test fun possibleGuestSuccessDoesNotMaskLoggedInDashHttp412() = runBlocking<Unit> {
        withTimeout(12_000) { Fixture().use { f ->
            f.mode = Mode.GUEST_SUCCESS; f.warm(); val binding = f.binding()
            val failure = assertFailsWith<BiliApiException> { binding.protocol.getInitialPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            assertEquals(412, failure.apiCode)
            assertTrue(f.ownedPlay.get() > 0); assertEquals(0, f.guestPlay.get())
            binding.assertCurrent(); assertTrue(f.repository.isPlaybackReceiptCurrent(binding.receipt))
        } }
    }

    @Test fun cancellationFromActualCapturedCallFactoryImmediatelySkipsFallback() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { f ->
            f.warm(); val before = f.requests.size; val calls = AtomicInteger()
            val cancelled = CancellationException("controlled actual caller cancellation")
            val binding = f.binding { Call.Factory { calls.incrementAndGet(); throw cancelled } }
            val failure = assertFailsWith<CancellationException> { binding.protocol.getInitialPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            assertTrue(generateSequence(failure as Throwable) { it.cause }.any { it === cancelled }); assertEquals(1, calls.get())
            assertEquals(before, f.requests.size); assertEquals(0, f.ownedPlay.get()); assertEquals(0, f.guestPlay.get())
        } }
    }

    @Test fun actualWbiRetryRethrowsUnderlyingLastIoCause() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { f ->
            f.warm(); val before = f.requests.size; val calls = AtomicInteger()
            val failures = List(3) { IOException("actual signing transport attempt $it") }
            val binding = f.binding { Call.Factory { throw failures[calls.getAndIncrement()] } }
            val failure = assertFailsWith<IOException> { binding.protocol.getWbiKeys() }
            // Coroutine debug recovery may copy an IOException while preserving
            // the original object as cause. Check bounded identity provenance,
            // exact message and type, rather than requiring the top-level copy.
            val causes = generateSequence(failure as Throwable) { it.cause }.take(8).toList()
            assertTrue(causes.any { it === failures.last() }, "Actual third IO cause is absent: $causes")
            assertEquals(failures.last().message, failure.message)
            assertFalse(causes.any { it.message?.startsWith("Wbi Keys Error after") == true })
            assertEquals(3, calls.get()); assertEquals(before, f.requests.size)
            assertSame(VideoLoadError.NetworkError, desktopOriginalVideoLoadError(failure))
            assertTrue(desktopOriginalVideoLoadCanRetry(failure)); binding.assertCurrent()
        } }
    }

    @Test fun exhaustedFailureWitnessCannotLeakIntoNextSameBindingEmptyResponse() = runBlocking<Unit> {
        withTimeout(12_000) { Fixture().use { f ->
            f.warm(); val binding = f.binding()
            assertFailsWith<BiliApiException> { binding.protocol.getPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            val before = f.ownedPlay.get(); f.mode = Mode.EMPTY
            assertNull(binding.protocol.getPlayUrlData("BV1GJ411x7h7", 22, 64, null))
            assertTrue(f.ownedPlay.get() > before); binding.assertCurrent()
            assertTrue(f.repository.isPlaybackReceiptCurrent(binding.receipt))
        } }
    }
}
