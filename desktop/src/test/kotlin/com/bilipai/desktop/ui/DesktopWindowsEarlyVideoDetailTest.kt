package com.bilipai.desktop.ui

import com.android.purebilibili.core.cooldown.PlaybackCooldownManager
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.audio.AudioFallbackReason
import com.android.purebilibili.feature.video.playback.loader.*
import com.android.purebilibili.feature.video.usecase.VideoLoadResult
import com.android.purebilibili.feature.video.usecase.VideoPlaybackUseCase
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual generated UseCase + original Loader. Barriers are real coroutine Jobs;
 * media/action/progress ports fail on use. No native player, HTTP or user profile. */
class DesktopWindowsEarlyVideoDetailTest {
    private class Fixture : AutoCloseable {
        val folder = Files.createTempDirectory("early-detail-usecase-")
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        val info = ViewInfo(bvid = "BVfixture", aid = 17L, cid = 70L,
            title = "Readonly fixture", desc = "Metadata may precede a playback error")
        var owned = true
        var detailCalls = 0
        var cachedHint: ViewInfo? = null
        val urlCids = mutableListOf<Long>()
        val forbiddenCalls = AtomicInteger()
        val infoEntered = CompletableDeferred<Unit>()
        val urlJob = CompletableDeferred<Job>()
        var loadInfo: suspend () -> Result<ViewInfo> = { Result.success(info) }
        var loadUrl: suspend (Long) -> PlayUrlData? = { cid -> playData(cid) }

        private inline fun <reified T> forbidden(): T = Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java)
        ) { _, method, _ ->
            forbiddenCalls.incrementAndGet()
            error("Unexpected ${T::class.java.simpleName}.${method.name}")
        } as T

        fun playData(cid: Long, premium: Boolean = false) = PlayUrlData(
            quality = 80, acceptQuality = listOf(80), dash = Dash(
                video = listOf(DashVideo(id = 80, baseUrl = "https://fixture.invalid/video-$cid", codecs = "avc1.640028")),
                audio = listOf(DashAudio(id = 30280, baseUrl = "https://fixture.invalid/aac-$cid", codecs = "mp4a", bandwidth = 128000)),
                dolby = if (premium) Dolby(type = 1,
                    audio = listOf(DashAudio(id = 30250, baseUrl = "https://fixture.invalid/dolby-$cid", codecs = "ec-3", bandwidth = 256000))) else null))

        val repository = object : DesktopOriginalVideoLoadRepository {
            override suspend fun getVideoInfoOnly(bvid: String, aid: Long, requestedCid: Long): Result<ViewInfo> {
                detailCalls++
                infoEntered.complete(Unit)
                return loadInfo().also { result -> result.onSuccess { cachedHint = it } }
            }
            override suspend fun getInitialPlayUrlData(bvid: String, cid: Long, targetQuality: Int, audioLang: String?): PlayUrlData? {
                urlCids += cid
                urlJob.complete(requireNotNull(currentCoroutineContext()[Job]))
                return loadUrl(cid)
            }
            override fun cachedVideoCid(bvid: String, aid: Long): Long = cachedHint
                ?.takeIf { it.bvid == bvid || (aid > 0L && it.aid == aid) }?.cid ?: 0L
            override suspend fun getCachedRelatedVideosResult(bvid: String): Result<List<RelatedVideo>>? = null
            override fun invalidateVideoLoadCaches(bvid: String, aid: Long, cid: Long) {
                if (cachedHint?.let { it.bvid == bvid || (aid > 0L && it.aid == aid) } == true) cachedHint = null
            }
            override suspend fun getVideoDetails(bvid: String, aid: Long, requestedCid: Long,
                targetQuality: Int?, audioLang: String?): Result<Pair<ViewInfo, PlayUrlData>> = error("No merged detail request")
            override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> = error("No related request")
            override suspend fun getRelatedVideosResult(bvid: String): Result<List<RelatedVideo>> = error("No related request")
            override suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int, audioLang: String?) = error("No secondary playback lookup")
            override suspend fun getPlaybackNavInfo(): Result<NavData> = error("No account lookup")
            override fun isPlaybackLoggedIn() = false
            override fun isPlaybackVip() = false
            override fun isUsingDedicatedPlaybackAccount() = false
            override fun isAppApiCoolingDown() = false
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = true
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        val useCase = VideoPlaybackUseCase(DesktopOriginalVideoPlaybackUseCaseEnvironment(
            context, repository, forbidden(), forbidden(), capabilities, forbidden(),
            { error("No native volume") }, { emptyMap() }, { error("No account mutation") },
            { false }, { _, _, _, _ -> }, { owned }))
        val loader = PlaybackLoader.from(useCase)
        fun config(onInfo: (ViewInfo) -> Unit = {}, provider: (suspend () -> Result<ViewInfo>)? = null,
            speed: Float? = null, audio: Int = 30280) = PlaybackLoadConfig(
            80, audio, "H264", "H264", true, false, false, false,
            onVideoInfo = onInfo, videoInfoLoader = provider, playbackSpeed = speed)
        init { PlaybackCooldownManager.clearAll() }
        override fun close() {
            PlaybackCooldownManager.clearAll()
            val absolute = folder.toAbsolutePath().normalize()
            check(absolute.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            check(absolute.fileName.toString().startsWith("early-detail-usecase-"))
            absolute.toFile().deleteRecursively()
        }
    }

    @Test fun fastUrlFailureWaitsForDelayedDetailCallbackBeforeTypedError() = runBlocking<Unit> {
        Fixture().use { f ->
            val releaseInfo = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var readonlyInfo: ViewInfo? = null
            f.loadInfo = { releaseInfo.await(); Result.success(f.info) }
            f.loadUrl = { throw IOException("Fixture URL failed first") }
            val loading = async {
                f.loader.load(PlaybackRequest.create("BVfixture", 17L, 70L), 0L,
                    f.config(onInfo = { readonlyInfo = it; events += "detail" }))
                    .also { events += "result" }
            }
            try {
                withTimeout(2000L) {
                    f.infoEntered.await()
                    f.urlJob.await().join()
                }
                assertTrue(loading.isActive, "Ordinary URL failure must not cancel the metadata waiter")
                assertTrue(requireNotNull(coroutineContext[Job]).isActive)
                assertNull(readonlyInfo)
                assertEquals(0, f.forbiddenCalls.get())
                releaseInfo.complete(Unit)
                val error = assertIs<PlaybackLoadResult.Error>(withTimeout(2000L) { loading.await() })
                assertSame(VideoLoadError.NetworkError, error.error)
                assertTrue(error.canRetry)
                assertSame(f.info, readonlyInfo)
                assertEquals(listOf("detail", "result"), events)
                assertEquals(1, f.detailCalls)
                assertEquals(0, f.forbiddenCalls.get(), "Readonly detail cannot call media/action ports")
            } finally {
                releaseInfo.complete(Unit)
                loading.cancelAndJoin()
            }
        }
    }

    @Test fun cachedCidMismatchCancelsActualWrongPrefetchAndUsesResolvedCid() = runBlocking<Unit> {
        Fixture().use { f ->
            val resolved = f.info.copy(cid = 202L)
            f.cachedHint = f.info.copy(cid = 101L)
            val wrongReleased = CompletableDeferred<Unit>()
            val wrongCancelled = CompletableDeferred<Unit>()
            var readonlyInfo: ViewInfo? = null
            f.loadInfo = { f.urlJob.await(); Result.success(resolved) }
            f.loadUrl = { cid ->
                if (cid == 101L) {
                    try { wrongReleased.await(); error("Wrong CID prefetch must be cancelled") }
                    catch (cancelled: CancellationException) { wrongCancelled.complete(Unit); throw cancelled }
                } else {
                    assertEquals(202L, cid)
                    assertSame(resolved, readonlyInfo, "Resolved metadata callback must precede refetch")
                    f.playData(cid)
                }
            }
            val result = assertIs<PlaybackLoadResult.Success>(withTimeout(2000L) {
                f.loader.load(PlaybackRequest.create("BVfixture", 17L, 0L), 0L,
                    f.config(onInfo = { readonlyInfo = it }))
            })
            withTimeout(2000L) { wrongCancelled.await(); f.urlJob.await().join() }
            assertTrue(f.urlJob.await().isCancelled)
            assertEquals(listOf(101L, 202L), f.urlCids)
            assertSame(resolved, result.payload.info)
            assertEquals("https://fixture.invalid/video-202", result.payload.playUrl)
            assertEquals(0, f.forbiddenCalls.get())
        }
    }

    @Test fun retiredOwnerEscapesCancellationWithoutPublishingLateDetail() = runBlocking<Unit> {
        Fixture().use { f ->
            val releaseInfo = CompletableDeferred<Unit>()
            var callbacks = 0
            f.loadInfo = { releaseInfo.await(); Result.success(f.info) }
            val loading = async {
                f.loader.load(PlaybackRequest.create("BVfixture", 17L, 70L), 0L,
                    f.config(onInfo = { callbacks++ }))
            }
            try {
                withTimeout(2000L) { f.infoEntered.await(); f.urlJob.await().join() }
                f.owned = false
                releaseInfo.complete(Unit)
                assertFailsWith<CancellationException> { withTimeout(2000L) { loading.await() } }
                assertEquals(0, callbacks)
                assertEquals(0, f.forbiddenCalls.get())
                assertEquals(0, PlaybackCooldownManager.getConsecutiveFailures())
                assertTrue(requireNotNull(coroutineContext[Job]).isActive)
            } finally {
                releaseInfo.complete(Unit)
                loading.cancelAndJoin()
            }
        }
    }

    @Test fun actualLoaderForwardsProviderCallbackAndSpeedToAudioSelection() = runBlocking<Unit> {
        Fixture().use { f ->
            var providers = 0
            var callbacks = 0
            f.loadUrl = { cid -> f.playData(cid, premium = true) }
            val selected = mutableListOf<VideoLoadResult.Success>()
            for (speed in listOf(1.0f, 2.0f)) {
                val result = assertIs<PlaybackLoadResult.Success>(withTimeout(2000L) {
                    f.loader.load(PlaybackRequest.create("BVfixture", 17L, 70L), 0L,
                        f.config(onInfo = { assertSame(f.info, it); callbacks++ },
                            provider = { providers++; Result.success(f.info) }, speed = speed, audio = 30250))
                })
                selected += result.payload
            }
            assertEquals(2, providers)
            assertEquals(2, callbacks)
            assertEquals(0, f.detailCalls, "Supplied metadata provider must replace another raw detail request")
            assertEquals(30250, selected[0].selectedAudioQuality)
            assertEquals("https://fixture.invalid/dolby-70", selected[0].audioUrl)
            assertNull(selected[0].audioFallbackReason)
            assertEquals(30280, selected[1].selectedAudioQuality)
            assertEquals(30250, selected[1].requestedAudioQuality)
            assertEquals("https://fixture.invalid/aac-70", selected[1].audioUrl)
            assertEquals(AudioFallbackReason.SPEED_INCOMPATIBLE, selected[1].audioFallbackReason)
            assertEquals(listOf(70L, 70L), f.urlCids)
            assertEquals(0, f.forbiddenCalls.get())
        }
    }
}
