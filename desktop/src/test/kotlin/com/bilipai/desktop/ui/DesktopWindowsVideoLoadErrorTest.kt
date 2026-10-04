package com.bilipai.desktop.ui

import com.android.purebilibili.core.cooldown.PlaybackCooldownManager
import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.usecase.VideoLoadResult
import com.android.purebilibili.feature.video.usecase.VideoPlaybackUseCase
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopWindowsVideoLoadErrorTest {
    @Test
    fun `HTTP status survives IOException classification without relying on message`() {
        for (code in listOf(412, 429)) {
            val failure = BiliApiException(code, "raw detail mentions a different HTTP 500")
            assertSame(VideoLoadError.NetworkError, VideoLoadError.fromException(failure))
            val error = assertIs<VideoLoadError.ApiError>(desktopWindowsVideoLoadError(failure))
            assertEquals(code, error.code)
            assertTrue(error.toUserMessage().contains("HTTP $code"))
            assertTrue(error.toUserMessage().contains("稍后重试"))
            assertEquals(code == 412, error.toUserMessage().contains("登录"))
            assertFalse(error.toUserMessage().contains("raw detail"))
            assertTrue(desktopWindowsVideoLoadCanRetry(failure))
        }
    }

    @Test
    fun `ordinary failures preserve the original type and retry rules`() {
        val failures = listOf(UnknownHostException("dns"), SocketTimeoutException("timeout"),
            IOException("contains 412 but has no reliable status"), IllegalStateException("Wbi failed"),
            IllegalStateException("unknown"))
        for (failure in failures) {
            assertEquals(VideoLoadError.fromException(failure), desktopWindowsVideoLoadError(failure))
            assertEquals(VideoLoadError.fromException(failure).isRetryable(), desktopWindowsVideoLoadCanRetry(failure))
        }
    }

    @Test
    fun `other API codes retain their original classification`() {
        for (code in listOf(-101, -412, -403, 403, 404, 500)) {
            val failure = BiliApiException(code, "request failed")
            assertEquals(VideoLoadError.fromException(failure), desktopWindowsVideoLoadError(failure))
            assertEquals(VideoLoadError.fromException(failure).isRetryable(), desktopWindowsVideoLoadCanRetry(failure))
        }
    }

    @Test
    fun `actual OkHttp async failure reaches Windows classification without sending HTTP`() {
        for (code in listOf(412, 429)) {
            val rejection = BiliApiException(code, "no status in message")
            val client = OkHttpClient.Builder().addInterceptor { throw rejection }.build()
            val future = CompletableFuture<IOException>()
            try {
                client.newCall(Request.Builder().url("https://never-contacted.invalid/").build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { future.complete(e) }
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                        future.completeExceptionally(AssertionError("Interceptor must reject before HTTP"))
                    }
                })
                val received = assertIs<BiliApiException>(future.get(5, TimeUnit.SECONDS))
                assertSame(rejection, received)
                assertEquals(code, assertIs<VideoLoadError.ApiError>(desktopWindowsVideoLoadError(received)).code)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `whole generated UseCase result and catch paths retain restriction and retry`() = runBlocking {
        for (throwFailure in listOf(false, true)) {
            for (code in listOf(412, 429)) {
                Fixture(BiliApiException(code, "no status in message"), throwFailure).use { fixture ->
                    val result = assertIs<VideoLoadResult.Error>(fixture.useCase.loadVideo("BV1GJ411x7h7"))
                    assertEquals(code, assertIs<VideoLoadError.ApiError>(result.error).code)
                    assertTrue(result.canRetry)
                    assertEquals(1, fixture.detailCalls.get())
                    assertEquals(0, fixture.mediaCalls.get())
                }
            }
        }
    }

    @Test
    fun `whole generated UseCase owner retirement and cancellation never become API errors`() = runBlocking {
        Fixture(BiliApiException(412, "limited"), false).use { fixture ->
            fixture.owned.set(false)
            assertFailsWith<CancellationException> { fixture.useCase.loadVideo("BV1GJ411x7h7") }
            assertEquals(0, fixture.detailCalls.get())
        }
        val cancellation = CancellationException("request canceled")
        Fixture(cancellation, true).use { fixture ->
            val received = assertFailsWith<CancellationException> { fixture.useCase.loadVideo("BV1GJ411x7h7") }
            assertEquals(cancellation.message, received.message)
            // Coroutine stack recovery may copy the exception while retaining its cause.
            assertTrue(generateSequence<Throwable>(received) { it.cause }.any { it === cancellation })
            assertEquals(1, fixture.detailCalls.get())
            assertEquals(0, fixture.mediaCalls.get())
        }
    }

    /** Test only: actual generated UseCase, private empty Store, no player/native/API.
     * Required platform ports fail if the error path attempts to publish any source.
     */
    private class Fixture(private val failure: Throwable, private val throwFailure: Boolean) : AutoCloseable {
        private val root = Files.createTempDirectory("bilipai-http-classification-")
        val owned = AtomicBoolean(true)
        val detailCalls = AtomicInteger()
        val mediaCalls = AtomicInteger()
        private val repository = object : DesktopOriginalVideoLoadRepository {
            override suspend fun getVideoInfoOnly(bvid: String, aid: Long, requestedCid: Long): Result<ViewInfo> =
                error("DETAIL_ONLY must not request a parallel info source")
            override suspend fun getInitialPlayUrlData(bvid: String, cid: Long, targetQuality: Int, audioLang: String?): PlayUrlData? =
                error("DETAIL_ONLY must not request a parallel play source")
            override suspend fun getVideoDetails(bvid: String, aid: Long, requestedCid: Long, targetQuality: Int?, audioLang: String?): Result<Pair<ViewInfo, PlayUrlData>> {
                detailCalls.incrementAndGet()
                if (throwFailure) throw failure
                return Result.failure(failure)
            }
            override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> = emptyList()
            override suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int, audioLang: String?): PlayUrlData? = error("No playable metadata")
            override suspend fun getPlaybackNavInfo(): Result<NavData> = error("No account data")
            override fun isPlaybackLoggedIn() = false
            override fun isPlaybackVip() = false
            override fun isUsingDedicatedPlaybackAccount() = false
            override fun isAppApiCoolingDown() = false
        }
        private val actions = object : DesktopOriginalVideoInitialActions {
            override suspend fun checkFollowStatus(mid: Long): Boolean = error("No account actions")
            override suspend fun checkFavoriteStatus(aid: Long): Boolean = error("No account actions")
            override suspend fun checkLikeStatus(aid: Long): Boolean = error("No account actions")
            override suspend fun checkCoinStatus(aid: Long): Int = error("No account actions")
        }
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long): Long = 0
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) = error("No successful source")
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        private fun unexpectedMedia(): Nothing {
            mediaCalls.incrementAndGet()
            error("An HTTP failure must not publish native media")
        }
        private val media = object : DesktopOriginalVideoMediaPort {
            override fun withPlaybackIntent(startPositionMs: Long, playWhenReady: Boolean, action: () -> Unit) = unexpectedMedia()
            override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource = unexpectedMedia()
            override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? = unexpectedMedia()
            override fun prepareProgressive(url: String): PlaybackSource = unexpectedMedia()
            override fun accept(source: PlaybackSource) = unexpectedMedia()
        }
        val useCase: DesktopOriginalVideoLoadPort = VideoPlaybackUseCase(DesktopOriginalVideoPlaybackUseCaseEnvironment(
            context = DesktopPluginContext(DesktopPluginStore(root)), repository = repository, actions = actions,
            progress = progress, capabilities = capabilities, media = media,
            applyPreferredVolume = { error("No player") }, emoteMap = { emptyMap() },
            updatePrimaryVip = { error("No account update") }, dashSegmentRequestsEnabled = { false },
            logSeekCallback = { _, _, _, _ -> error("No player") }, isCurrent = owned::get))
        init { PlaybackCooldownManager.clearAll() }
        override fun close() {
            owned.set(false)
            PlaybackCooldownManager.clearAll()
            Files.walk(root).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
