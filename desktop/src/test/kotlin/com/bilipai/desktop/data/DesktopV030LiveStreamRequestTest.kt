package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.requestOriginalDesktopLiveStream
import com.android.purebilibili.feature.live.resolveLivePlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.test.*

/** Runs the generated original request through the actual generated Retrofit interface. */
class DesktopV030LiveStreamRequestTest {
    private val room = LiveRoomDetails(123, "直播标题", "", "主播", "", 1, "生活", 1)

    private class Transport(val response: (HttpUrl) -> String) {
        val requests = mutableListOf<HttpUrl>()
        val signed = mutableListOf<Map<String, String>>()
        val api: BilibiliApi = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
            .client(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request.url
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response(request.url).toResponseBody("application/json".toMediaType())).build()
            }.build())
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)

        suspend fun sign(params: Map<String, String>): Map<String, String> {
            signed += params.toMap()
            return params + mapOf("wts" to "synthetic-time", "w_rid" to "synthetic-signature")
        }
    }

    private fun primary(codec: String = "avc", current: Int = 150, accept: String = "[10000,150]",
                        host: String = "https://cdn.example", global: String =
                        """[{"qn":10000,"desc":"原画"},{"qn":400,"desc":"蓝光"},{"qn":150,"desc":"高清"}]""",
                        metadata: String = ""): String =
        """{"code":0,"data":{$metadata"playurl_info":{"playurl":{"g_qn_desc":$global,"stream":[{"protocol_name":"http_hls","format":[{"format_name":"fmp4","codec":[{"codec_name":"$codec","current_qn":$current,"accept_qn":$accept,"base_url":"/index.m3u8","url_info":[{"host":"$host","extra":"?token=first"},{"host":"https://backup.example","extra":"?token=backup"}]}]}]}]}}}}"""

    @Test fun defaultOriginalRequestSignsAndSendsEveryParameterOnce(): Unit = runBlocking {
        val transport = Transport { primary() }
        val data = requestOriginalDesktopLiveStream(transport.api, transport::sign, room.roomId).getOrThrow()
        val expected = mapOf("room_id" to "123", "protocol" to "0,1", "format" to "0,1,2",
            "codec" to "0,1,2", "qn" to "10000", "platform" to "web", "ptype" to "8",
            "dolby" to "5", "panorama" to "1", "web_location" to "444.8")
        assertEquals(expected, transport.signed.single())
        val url = transport.requests.single()
        assertEquals(expected.keys + setOf("wts", "w_rid"), url.queryParameterNames)
        for ((key, value) in expected) assertEquals(listOf(value), url.queryParameterValues(key))
        assertEquals("synthetic-signature", url.queryParameter("w_rid"))
        assertNull(url.queryParameter("only_audio"))
        val selected = requireNotNull(DesktopMediaRepository.selectLive(data, room, 10000))
        assertEquals(150, selected.source.quality)
        assertEquals(listOf(10000, 150), selected.qualities.map { it.id })
    }

    @Test fun manualIntentAndDefaultAreNeverOverwrittenByServerDowngrade(): Unit = runBlocking {
        val transport = Transport { primary(accept = "[400,150]") }
        val manual = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123, qn = 400).getOrThrow()
        val resolved = requireNotNull(resolveLivePlayback(manual, 400))
        assertEquals(400, resolved.requestedQuality)
        assertEquals(150, DesktopMediaRepository.selectLive(manual, room, 400)?.source?.quality)
        assertEquals(listOf(400, 150), DesktopMediaRepository.selectLive(manual, room, 400)?.qualities?.map { it.id })
        requestOriginalDesktopLiveStream(transport.api, transport::sign, 456).getOrThrow()
        requestOriginalDesktopLiveStream(transport.api, transport::sign, 789, qn = 20000).getOrThrow()
        assertEquals(listOf("400", "10000", "20000"), transport.requests.map { it.queryParameter("qn") })
        assertEquals(listOf("123", "456", "789"), transport.requests.map { it.queryParameter("room_id") })
    }

    @Test fun failedAudioOnlyRequestDoesNotFallBackToVideo(): Unit = runBlocking {
        val transport = Transport { """{"code":-1,"message":"audio unavailable"}""" }
        val result = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123, onlyAudio = true)
        assertTrue(result.isFailure)
        assertEquals("audio unavailable", result.exceptionOrNull()?.message)
        assertEquals("1", transport.signed.single()["only_audio"])
        assertEquals(listOf("1"), transport.requests.single().queryParameterValues("only_audio"))
        assertTrue(transport.requests.single().encodedPath.endsWith("getRoomPlayInfo"))
    }

    @Test fun successfulAudioOnlyDoesNotMakeOptionalVideoQualityRequest(): Unit = runBlocking {
        val transport = Transport { primary(codec = "audio", global = "[]", accept = "[150]") }
        val data = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123, onlyAudio = true).getOrThrow()
        assertEquals(1, transport.requests.size)
        assertEquals("audio", requireNotNull(resolveLivePlayback(data, 10000)).candidates.single().codecName)
    }

    @Test fun permittedVideoFallbackKeepsManualRequestedQuality(): Unit = runBlocking {
        val transport = Transport { url ->
            if (url.encodedPath.endsWith("getRoomPlayInfo")) """{"code":-1,"message":"retry legacy"}"""
            else """{"code":0,"data":{"current_quality":250,"quality_description":[{"qn":250,"desc":"超清"}],"durl":[{"url":"https://legacy.example/live.flv"}]}}"""
        }
        val data = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123, qn = 400).getOrThrow()
        assertEquals(2, transport.requests.size)
        assertEquals("123", transport.requests.last().queryParameter("cid"))
        assertEquals("400", transport.requests.last().queryParameter("qn"))
        val selected = requireNotNull(DesktopMediaRepository.selectLive(data, room, 400))
        assertEquals("https://legacy.example/live.flv", selected.source.videoUrl)
        assertEquals(250, selected.source.quality)
    }

    @Test fun optionalLegacyDescriptionsDoNotReplaceCurrentCodecQuality(): Unit = runBlocking {
        val transport = Transport { url ->
            if (url.encodedPath.endsWith("getRoomPlayInfo")) primary(global = "[]", accept = "[150]")
            else """{"code":0,"data":{"current_quality":400,"quality_description":[{"qn":400,"desc":"蓝光"},{"qn":150,"desc":"高清"}]}}"""
        }
        val data = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123).getOrThrow()
        val selected = requireNotNull(DesktopMediaRepository.selectLive(data, room, 10000))
        assertEquals(2, transport.requests.size)
        assertEquals(150, selected.source.quality)
        assertEquals(listOf(MediaQuality(150, "高清")), selected.qualities)
        assertEquals(listOf("https://backup.example/index.m3u8?token=backup"), selected.backupUrls)
    }

    @Test fun liveStatusStopsPlaybackWithoutLegacyFallback(): Unit = runBlocking {
        val transport = Transport { primary(metadata = "\"room_id\":123,\"uid\":456,\"live_status\":0,\"live_time\":789,\"is_portrait\":true,") }
        val result = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123)
        assertTrue(result.isFailure)
        assertEquals("当前直播间未开播", result.exceptionOrNull()?.message)
        assertEquals(1, transport.requests.size)
    }

    @Test fun cancellationEscapesOriginalResultFallback(): Unit = runBlocking {
        val transport = Transport { error("Cancelled signing must not make an API request") }
        assertFailsWith<CancellationException> {
            requestOriginalDesktopLiveStream(transport.api, { throw CancellationException("retired") }, 123)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun permissionDenialsKeepExistingWindowsGuard(): Unit = runBlocking {
        for (code in listOf(-101, -352, -412, -403)) {
            val transport = Transport { """{"code":$code,"message":"denied"}""" }
            val error = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123).exceptionOrNull()
            assertIs<BiliApiException>(error)
            assertEquals(code, error.apiCode)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun actualSelectionUsesEachCandidateMetadataAndSafeUrls(): Unit = runBlocking {
        val payload = """{"code":0,"data":{"playurl_info":{"playurl":{"g_qn_desc":[{"qn":10000,"desc":"原画"},{"qn":150,"desc":"高清"}],"stream":[{"protocol_name":"http_hls","format":[{"format_name":"fmp4","codec":[{"codec_name":"avc","current_qn":150,"accept_qn":[150],"base_url":"/low.m3u8","url_info":[{"host":"file://unsafe"}]},{"codec_name":"hevc","current_qn":10000,"accept_qn":[10000],"base_url":"/high.m3u8","url_info":[{"host":"https://high.example"}]}]}]}]}}}}"""
        val transport = Transport { payload }
        val data = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123).getOrThrow()
        val selected = requireNotNull(DesktopMediaRepository.selectLive(data, room, 10000))
        assertEquals("https://high.example/high.m3u8", selected.source.videoUrl)
        assertEquals(10000, selected.source.quality)
        assertEquals(listOf(MediaQuality(10000, "原画")), selected.qualities)
    }

    @Test fun actualSelectionPreservesOriginalProtocolBeforeCodecOrdering(): Unit = runBlocking {
        val payload = """{"code":0,"data":{"playurl_info":{"playurl":{"g_qn_desc":[{"qn":10000,"desc":"原画"},{"qn":150,"desc":"高清"}],"stream":[{"protocol_name":"http_stream","format":[{"format_name":"flv","codec":[{"codec_name":"avc","current_qn":150,"accept_qn":[150],"base_url":"/low.flv","url_info":[{"host":"https://low.example"}]}]}]},{"protocol_name":"http_hls","format":[{"format_name":"fmp4","codec":[{"codec_name":"hevc","current_qn":10000,"accept_qn":[10000],"base_url":"/high.m3u8","url_info":[{"host":"https://high.example"}]}]}]}]}}}}"""
        val transport = Transport { payload }
        val data = requestOriginalDesktopLiveStream(transport.api, transport::sign, 123).getOrThrow()
        val selected = requireNotNull(DesktopMediaRepository.selectLive(data, room, 10000))
        assertEquals("https://high.example/high.m3u8", selected.source.videoUrl)
        assertEquals(10000, selected.source.quality)
        assertEquals(listOf(MediaQuality(10000, "原画")), selected.qualities)
    }
}
