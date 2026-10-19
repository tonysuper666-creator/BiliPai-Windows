package com.bilipai.desktop.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBilibiliRequestHeaderPolicyTest {
    @Test
    fun `Bilibili services and media retain default headers`() {
        listOf("bilibili.com", "api.bilibili.com", "i0.hdslb.com", "upos.bilivideo.com", "upos.bilivideo.cn")
            .forEach { assertTrue(usesDefaultBilibiliHeaders(it), it) }
    }

    @Test
    fun `RSS image hosts and lookalike domains do not receive Bilibili headers`() {
        listOf("image.gcores.com", "www.jintiankansha.me", "mmbiz.qpic.cn", "evilbilibili.com", "bilibili.com.example.com")
            .forEach { assertFalse(usesDefaultBilibiliHeaders(it), it) }
    }

    @Test
    fun `external host omits default referer and keeps an explicit caller header`() {
        val external = "https://image.gcores.com/rss.jpg?bvid=BV1xx411c7mD".toHttpUrl()
        assertNull(DesktopRepository.resolveReferer(external, null))
        assertNull(DesktopRepository.resolveReferer(external, ""))
        assertEquals("https://example.invalid/article", DesktopRepository.resolveReferer(external, "https://example.invalid/article"))
        listOf("www.jintiankansha.me", "mmbiz.qpic.cn", "evilbilibili.com", "bilibili.com.example.com").forEach { host ->
            assertNull(DesktopRepository.resolveReferer("https://$host/cover.jpg".toHttpUrl(), null), host)
        }
    }

    @Test
    fun `Bilibili host referer matches the existing policy`() {
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", DesktopRepository.resolveReferer(
            "https://api.bilibili.com/x/web-interface/view?bvid=BV1xx411c7mD".toHttpUrl(), null))
        assertEquals("https://www.bilibili.com", DesktopRepository.resolveReferer(
            "https://api.bilibili.com/x/web-interface/popular?pn=1&ps=20".toHttpUrl(), null))
        val wbi = "https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1xx411c7mD".toHttpUrl()
        assertNull(DesktopRepository.resolveReferer(wbi, null))
        assertNull(DesktopRepository.resolveReferer(wbi, ""))
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", DesktopRepository.resolveReferer(wbi, "https://www.bilibili.com/video/BV1xx411c7mD"))
        assertNull(DesktopRepository.resolveReferer("https://api.bilibili.com/x/web-interface/wbi/search/type".toHttpUrl(), null))
        val passport = "https://passport.bilibili.com/x/safecenter/common/sms/send".toHttpUrl()
        assertNull(DesktopRepository.resolveReferer(passport, null))
        assertEquals("https://passport.bilibili.com/risk", DesktopRepository.resolveReferer(passport, "https://passport.bilibili.com/risk"))
        assertNull(DesktopRepository.resolveReferer("https://app.bilibili.com/x/v2/space".toHttpUrl(), null))
        assertEquals("https://www.bilibili.com", DesktopRepository.resolveReferer("https://i0.hdslb.com/bfs/archive/cover.jpg".toHttpUrl(), null))
        assertEquals("https://www.bilibili.com", DesktopRepository.resolveReferer("https://upos.bilivideo.com/video.m4s".toHttpUrl(), null))
        assertEquals("https://www.bilibili.com", DesktopRepository.resolveReferer("https://upos.bilivideo.cn/video.m4s".toHttpUrl(), null))
    }

    @Test
    fun `interceptor adds Referer and Origin only for Bilibili hosts`() {
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val seen = mutableListOf<Request>()
        val client = repository.httpClient.newBuilder().addInterceptor { chain ->
            val current = chain.request()
            synchronized(seen) { seen += current }
            Response.Builder().request(current).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody(null)).build()
        }.build()
        fun call(builder: Request.Builder): Request {
            client.newCall(builder.build()).execute().close()
            return synchronized(seen) { seen.last() }
        }
        try {
            val external = call(Request.Builder().url("https://image.gcores.com/rss.jpg"))
            assertNull(external.header("Referer"))
            assertNull(external.header("Origin"))
            assertEquals(DesktopRepository.USER_AGENT, external.header("User-Agent"))

            val explicit = call(Request.Builder().url("https://mmbiz.qpic.cn/rss.jpg")
                .header("Referer", "https://example.invalid/article"))
            assertEquals("https://example.invalid/article", explicit.header("Referer"))
            assertNull(explicit.header("Origin"))

            val cdn = call(Request.Builder().url("https://i0.hdslb.com/bfs/archive/cover.jpg"))
            assertEquals("https://www.bilibili.com", cdn.header("Referer"))
            assertEquals("https://www.bilibili.com", cdn.header("Origin"))

            val api = call(Request.Builder().url("https://api.bilibili.com/x/web-interface/view?bvid=BV1xx411c7mD"))
            assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", api.header("Referer"))
            assertEquals("https://www.bilibili.com", api.header("Origin"))

            val wbi = call(Request.Builder().url("https://api.bilibili.com/x/web-interface/wbi/search/type"))
            assertNull(wbi.header("Referer"))
            assertEquals("https://www.bilibili.com", wbi.header("Origin"))

            val kept = call(Request.Builder().url("https://api.bilibili.com/x/web-interface/popular")
                .header("Referer", "https://www.bilibili.com/video/BV1xx411c7mD"))
            assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", kept.header("Referer"))
            assertEquals("https://www.bilibili.com", kept.header("Origin"))
        } finally {
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
        }
    }
}
