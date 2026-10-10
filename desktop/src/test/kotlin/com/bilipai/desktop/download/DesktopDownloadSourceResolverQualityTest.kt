package com.bilipai.desktop.download

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Calls the production default resolver and real repository DASH selection,
 * owned Retrofit requests and temporary SessionStore. Every HTTP response is
 * intercepted at exact synthetic origins; there is no external transport. */
class DesktopDownloadSourceResolverQualityTest {
    private class Fixture : AutoCloseable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val requests = CopyOnWriteArrayList<Request>()
        var data = PlayUrlData(quality = 120, acceptQuality = listOf(120, 80, 64),
            acceptDescription = listOf("advertised 4K", "1080P", "720P"), dash = Dash(
                video = listOf(DashVideo(id = 80, baseUrl = "https://media.bilibili.com/v80", codecs = "avc1.640028")),
                audio = listOf(DashAudio(id = 30280, baseUrl = "https://media.bilibili.com/a30280", codecs = "mp4a.40.2"))))
        var beforePlayResponse: () -> Unit = {}
        val transport: OkHttpClient
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"), AccountSummary(41, "Fixture", ""))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor", "buvid4" to "synthetic-visitor4"), sessions.generation)
            transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    check(request.method == "GET" && request.url.isHttps && request.url.port == 443)
                    val body = when (request.url.host to request.url.encodedPath) {
                        "www.bilibili.com" to "/" -> "fixture bootstrap"
                        "api.bilibili.com" to "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "api.bilibili.com" to "/x/web-interface/view" -> """{"code":0,"data":{"bvid":"BV1GJ411x7h7","aid":7,"cid":21,"title":"two parts","pages":[{"cid":21,"page":1,"part":"one"},{"cid":42,"page":2,"part":"two"}]}}"""
                        "api.bilibili.com" to "/x/web-interface/nav" -> """{"code":0,"data":{"wbi_img":{"img_url":"https://fixture.invalid/${"a".repeat(32)}.png","sub_url":"https://fixture.invalid/${"b".repeat(32)}.png"}}}"""
                        "api.bilibili.com" to "/x/player/wbi/playurl" -> {
                            beforePlayResponse(); Json.encodeToString(PlayUrlResponse(data = data))
                        }
                        else -> throw java.io.IOException("No external fixture transport")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        }
        fun task(cid: Long = 42, audioOnly: Boolean = false): DownloadTask {
            val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }.receipt
            return DownloadTask(OriginalTask(bvid = "BV1GJ411x7h7", cid = cid, title = "two parts", cover = "",
                ownerName = "", ownerFace = "", duration = 1, quality = 120, qualityDesc = "requested 4K",
                videoUrl = "https://media.bilibili.com/old-v", audioUrl = "https://media.bilibili.com/old-a", isAudioOnly = audioOnly),
                Files.createTempDirectory("bilipai-resolver-quality-").toString(), userAgent = "fixture original agent", authorizationReceipt = receipt)
        }
        override fun close() { transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
    }

    @Test fun defaultResolverCarriesSelectedDashIdAndCapturedTransportForExactCid() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { fixture ->
            val task = fixture.task()
            val resolution = assertNotNull(DesktopDownloadManager.defaultSourceResolver(fixture.repository)(task))
            assertEquals(80, resolution.actualVideoQuality)
            assertEquals("https://media.bilibili.com/v80", resolution.source.videoUrl)
            assertEquals("https://media.bilibili.com/a30280", resolution.source.audioUrl)
            assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", resolution.source.referer)
            assertEquals(task.userAgent, resolution.source.userAgent)
            assertEquals(task.authorizationReceipt, resolution.source.authorizationReceipt)
            val authorization = fixture.repository.capturePlaybackAuthorization(fixture.repository.sessionEpoch) { true }
            assertEquals(fixture.repository.capturePlaybackMediaCookieHeader(authorization, resolution.source.videoUrl) { true }, resolution.source.cookieHeader)
            val play = fixture.requests.single { it.url.encodedPath == "/x/player/wbi/playurl" }
            assertEquals("42", play.url.queryParameter("cid")); assertEquals("120", play.url.queryParameter("qn"))
            assertFalse(resolution.actualVideoQuality == fixture.data.acceptQuality.max())
        } }
    }

    @Test fun defaultResolverDoesNotTurnProgressiveResponseQualityIntoDashMetadata() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { fixture ->
            fixture.data = PlayUrlData(quality = 120, durl = listOf(
                Durl(order = 1, length = 1000, url = "https://media.bilibili.com/first.flv"),
                Durl(order = 2, length = 2000, url = "https://media.bilibili.com/second.flv")))
            val resolution = assertNotNull(DesktopDownloadManager.defaultSourceResolver(fixture.repository)(fixture.task()))
            assertNull(resolution.actualVideoQuality); assertNull(resolution.source.audioUrl)
            assertEquals(listOf("https://media.bilibili.com/first.flv", "https://media.bilibili.com/second.flv"), resolution.source.progressiveSegments.map { it.url })
            assertEquals(listOf(1.0, 2.0), resolution.source.progressiveSegments.map { it.durationSeconds })
        } }
    }

    @Test fun audioOnlyAndMissingCidKeepOriginalResolverSemantics() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { fixture ->
            val task = fixture.task(audioOnly = true)
            val resolution = assertNotNull(DesktopDownloadManager.defaultSourceResolver(fixture.repository)(task))
            assertNull(resolution.actualVideoQuality); assertEquals("https://media.bilibili.com/a30280", resolution.source.audioUrl)
            val before = fixture.requests.count { it.url.encodedPath == "/x/player/wbi/playurl" }
            assertFailsWith<java.io.IOException> { DesktopDownloadManager.defaultSourceResolver(fixture.repository)(fixture.task(cid = 999)) }
            assertEquals(before, fixture.requests.count { it.url.encodedPath == "/x/player/wbi/playurl" })
        } }
    }

    @Test fun accountRetiredDuringRealPlayurlCannotReturnMetadataOrRecapture() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { fixture ->
            val task = fixture.task()
            fixture.beforePlayResponse = {
                fixture.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-replacement"), AccountSummary(42, "replacement", ""))
            }
            assertFailsWith<CancellationException> { DesktopDownloadManager.defaultSourceResolver(fixture.repository)(task) }
            assertFalse(fixture.repository.isPlaybackReceiptCurrent(assertNotNull(task.authorizationReceipt)))
            assertEquals(1, fixture.requests.count { it.url.encodedPath == "/x/player/wbi/playurl" })
            assertFalse(fixture.requests.any { it.header("Cookie").orEmpty().contains("synthetic-replacement") })
        } }
    }
}
