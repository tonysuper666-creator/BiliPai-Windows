package com.bilipai.desktop.audio

import com.android.purebilibili.data.repository.AudioRepository
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.data.DesktopUpstreamCalls
import com.bilipai.desktop.data.DesktopUpstreamPurpose
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.ui.DesktopOriginalVideoRepositoryBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real Store, request owner and captured video binding; all responses are offline synthetic fixtures. */
class DesktopUpstreamAudioRepositoryTest {
    private class Fixture : AutoCloseable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val audio = DesktopAudioRepository(repository, DesktopCommunityRepository(repository))
        val entry = SupervisorJob()
        var entryOpen = true
        var afterRequest: (Request) -> Unit = {}
        val requests = CopyOnWriteArrayList<Request>()
        val client: OkHttpClient

        init {
            sessions.saveAccount(mapOf("SESSDATA" to "fixture-session", "bili_jct" to "fixture-csrf"), AccountSummary(41, "Fixture", ""))
            sessions.saveSpiCookies(mapOf("buvid3" to "fixture-visitor"), sessions.generation)
            fun set(name: String, value: Any) {
                DesktopRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repository, value)
            }
            set("visitorInitialized", true)
            set("visitorGeneration", repository.sessionEpoch)
            client = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath
                    // B1: three AU endpoints keep the upstream www base; view/nav/playurl stay on api.
                    val expectedHost = when (path) {
                        "/audio/music-service-c/web/song/info", "/audio/music-service-c/web/url", "/audio/music-service-c/web/song/lyric" -> "www.bilibili.com"
                        "/x/web-interface/nav", "/x/player/wbi/playurl", "/x/player/playurl" -> "api.bilibili.com"
                        else -> throw IOException("Unexpected offline fixture path: $path")
                    }
                    check(request.url.host == expectedHost) { "Unexpected offline fixture host ${request.url.host} for $path, expected $expectedHost" }
                    requests += request
                    val body = when (path) {
                        "/audio/music-service-c/web/song/info" -> """{"code":0,"data":{"id":81,"title":"fixture song","author":"fixture artist"}}"""
                        "/audio/music-service-c/web/url" -> """{"code":0,"data":{"sid":81,"cdns":["https://api.bilibili.com/fixture-audio"]}}"""
                        "/audio/music-service-c/web/song/lyric" -> """{"code":0,"data":"fixture lyric"}"""
                        "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "/x/player/wbi/playurl", "/x/player/playurl" -> """{"code":0,"data":{"quality":64,"accept_quality":[64],"dash":{"duration":20,"video":[{"id":64,"base_url":"https://fixture.invalid/video","codecs":"avc1.640028","mime_type":"video/mp4","bandwidth":1000000}],"audio":[{"id":30216,"base_url":"https://fixture.invalid/low","codecs":"mp4a.40.2","mime_type":"audio/mp4","bandwidth":64000},{"id":30280,"base_url":"https://fixture.invalid/audio","codecs":"mp4a.40.2","mime_type":"audio/mp4","bandwidth":128000}]}}}"""
                        else -> throw IOException("Unexpected offline fixture path: ${request.url.encodedPath}")
                    }
                    afterRequest(request)
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, client)
        }

        suspend fun binding(): DesktopOriginalVideoRepositoryBinding = DesktopOriginalVideoRepositoryBinding.capture(
            repository, repository.sessionEpoch, entry, { entryOpen },
            { action -> if (!entryOpen) false else { action(); true } }, PlayerPreferences(), null,
            emptySet(), false, { false }, { false }, { false }, { false }, { _, _ -> false },
        )

        override fun close() {
            entryOpen = false
            entry.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun auPreparationUsesOwnedTransportAndReturnsSameAccountCookies() = runBlocking<Unit> {
        Fixture().use { f ->
            val prepared = f.audio.prepare(musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(81)))
            assertEquals("fixture song", prepared.item.title)
            assertEquals("https://api.bilibili.com/fixture-audio", prepared.source.videoUrl)
            assertTrue(prepared.source.cookieHeader.contains("SESSDATA=fixture-session"))
            assertEquals("fixture lyric", prepared.songLyrics)
            assertEquals(listOf("/audio/music-service-c/web/song/info", "/audio/music-service-c/web/url", "/audio/music-service-c/web/song/lyric"),
                f.requests.map { it.url.encodedPath })
            assertTrue(f.requests.all { it.url.host == "www.bilibili.com" })
            assertTrue(f.requests.all { it.url.queryParameter("sid") == "81" })
            assertEquals(f.repository.sessionEpoch, prepared.source.primaryAccountEpoch)
        }
    }

    @Test fun missingOwnerAndMissingVideoProviderFailLoudly() = runBlocking<Unit> {
        assertFailsWith<UnsupportedDesktopCapability> { AudioRepository.getSongInfo(81) }
        assertFailsWith<UnsupportedDesktopCapability> { AudioRepository.getSongStream(81) }
        assertFailsWith<UnsupportedDesktopCapability> { AudioRepository.getSongLyric(81) }
        assertFailsWith<UnsupportedDesktopCapability> { AudioRepository.getAudioStreamFromVideo("BV1GJ411x7h7", 22) }
    }

    @Test fun switchingAccountAfterResponseNeverPublishesOldAudio() = runBlocking<Unit> {
        Fixture().use { f ->
            f.afterRequest = { if (it.url.encodedPath.endsWith("/song/info")) f.sessions.logout() }
            // B2: the desktop prepare path restores the original account-switch failure instead of
            // leaking the upstream owner-retired cancellation.
            val failure = assertFailsWith<IllegalStateException> {
                f.audio.prepare(musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(81)))
            }
            assertEquals("账号已切换，请重新加载音频。", failure.message)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun closedWindowRetiresOwnerAndCancelledRequestStaysCancelled() = runBlocking<Unit> {
        Fixture().use { f ->
            val requestJob = requireNotNull(currentCoroutineContext()[Job])
            val owner = f.repository.upstreamOwner(DesktopUpstreamPurpose.Main, { f.entryOpen && requestJob.isActive }, f.repository.sessionEpoch)
            f.entryOpen = false
            assertFailsWith<CancellationException> { DesktopUpstreamCalls.withOwnerScope(owner) { AudioRepository.getSongInfo(81) } }
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun cancellationDuringRealAuRequestDoesNotPublishOrContinue() = runBlocking<Unit> {
        Fixture().use { f ->
            val request = async(start = CoroutineStart.LAZY) {
                f.audio.prepare(musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(81)))
            }
            f.afterRequest = { if (it.url.encodedPath.endsWith("/song/info")) request.cancel() }
            request.start()
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun videoBridgeUsesCapturedRawBindingAndRejectsRetiredReceipt() = runBlocking<Unit> {
        Fixture().use { f ->
            val binding = f.binding()
            val result = withDesktopAudioVideoRequest(binding) {
                AudioRepository.getAudioStreamFromVideo("BV1GJ411x7h7", 22)
            }
            assertEquals("https://fixture.invalid/audio", result)
            val play = f.requests.first { it.url.encodedPath.contains("playurl") }
            assertEquals("api.bilibili.com", play.url.host)
            assertEquals("64", play.url.queryParameter("qn"))
            assertEquals(null, play.url.queryParameter("audio_lang"))
            val firstCallRequests = f.requests.size
            assertEquals(result, withDesktopAudioVideoRequest(binding) {
                AudioRepository.getAudioStreamFromVideo("BV1GJ411x7h7", 22)
            })
            // The owned transport keeps no play-url cache: the repeat re-asks exactly one
            // playurl on the api host with the already known wbi keys, and never fetches a
            // second nav or leaks onto the AU www base.
            val repeated = f.requests.drop(firstCallRequests)
            assertEquals(listOf(play.url.encodedPath), repeated.map { it.url.encodedPath })
            assertEquals("api.bilibili.com", repeated.single().url.host)
            f.sessions.logout()
            assertFailsWith<CancellationException> {
                withDesktopAudioVideoRequest(binding) { AudioRepository.getAudioStreamFromVideo("BV1GJ411x7h7", 22) }
            }
        }
    }
}
