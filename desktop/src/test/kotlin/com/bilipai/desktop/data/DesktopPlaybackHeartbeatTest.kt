package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import kotlin.test.*

class DesktopPlaybackHeartbeatTest {
    @Test fun privacyReturnsOriginalSuccessfulNoOpWithoutTransport(): Unit = runBlocking {
        assertTrue(reportDesktopPlaybackHeartbeat({ true }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, 5, 4, 123) {
            fail("Privacy mode must not send a heartbeat")
        })
    }
    @Test fun guestMissingCsrfAndStaleSessionNeverWrite(): Unit = runBlocking {
        val forbidden: suspend (Map<String, String>) -> Int = { fail("Not authorized to send") }
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 2 }, { 10 }, { "csrf" }, "BV1", 3, 5, 4, 123, send = forbidden))
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { null }, { "csrf" }, "BV1", 3, 5, 4, 123, send = forbidden))
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "" }, "BV1", 3, 5, 4, 123, send = forbidden))
    }
    @Test fun originalHeartbeatFormUsesActualRetrofitAndWallClockTime(): Unit = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"code\":0,\"message\":\"ok\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
        assertTrue(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "test&csrf" }, "BV1", 3, 30, 18, 123,
            aid = 7, epid = 11, sid = 12, videoType = 4, subType = 2) { api.reportHeartbeat(it).code })
        val request = assertNotNull(captured)
        assertEquals("POST", request.method); assertEquals("/x/click-interface/web/heartbeat", request.url.encodedPath)
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("30", fields["played_time"]); assertEquals("18", fields["real_played_time"]); assertEquals("18", fields["realtime"])
        assertEquals("test&csrf", fields["csrf"]); assertEquals("123", fields["start_ts"])
        assertEquals("7", fields["aid"]); assertEquals("11", fields["epid"]); assertEquals("12", fields["sid"])
        assertEquals("4", fields["type"]); assertEquals("2", fields["sub_type"]); assertEquals("10", fields["mid"])
    }
    @Test fun startHeartbeatUsesOriginalPlayTypeAndClampsTimes(): Unit = runBlocking {
        var fields: Map<String, String>? = null
        assertTrue(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, -1, -2, -3) {
            fields = it; 0
        })
        assertEquals("1", fields!!["play_type"]); assertEquals("0", fields!!["played_time"])
        assertEquals("0", fields!!["real_played_time"]); assertEquals("0", fields!!["start_ts"])
    }
    @Test fun switchingAccountDuringFieldCaptureNeverSends(): Unit = runBlocking {
        var epoch = 1L
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { epoch }, { 10 }, { epoch = 2; "csrf" }, "BV1", 3, 5, 4, 123) {
            fail("Old source must not report under new account")
        })
    }
    @Test fun cancellationPropagatesAndApiFailuresRemainFalse(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, 5, 4, 123) { throw CancellationException() }
        }
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, 5, 4, 123) { -101 })
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, 5, 4, 123) { throw java.io.IOException() })
    }
    @Test fun privacySynchronousReadSeesAnotherInstancesLatestOriginalKey(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-privacy-")
        val old = DesktopSearchPreferences(root); val writer = DesktopSearchPreferences(root)
        assertFalse(old.isPrivacyModeEnabledSync())
        writer.setPrivacyMode(true)
        assertFalse(old.privacyMode.value) // Independent in-memory flow is intentionally not the authority.
        assertTrue(old.isPrivacyModeEnabledSync())
        writer.setPrivacyMode(false)
        assertFalse(old.isPrivacyModeEnabledSync())
    }
}
