package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.core.network.DynamicRepostContentItem
import com.android.purebilibili.data.repository.DesktopOriginalVideoDynamicShareRepository
import kotlinx.coroutines.runBlocking
import com.android.purebilibili.data.model.response.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlin.test.*

class WindowsVideoDynamicShareReplayTest {
    private val original = DynamicCreateFeedRequest(DynamicCreateFeedReq(
        content = DynamicCreateFeedContent(listOf(DynamicRepostContentItem(WindowsVideoDynamicShareReplay.DRAFT, 1, ""))),
        scene = 5, upload_id = "0_1700000000_1234"),
        DynamicVideoRepostSource(DynamicVideoRepostResource(170001L, 8)))
    private val exactUrl = "https://api.bilibili.com/x/dynamic/feed/create/dyn".toHttpUrl().newBuilder()
        .addQueryParameter("csrf", "LOCAL-COMPOSER-NOT-A-REAL-CSRF")
        .addQueryParameter("platform", "web")
        .addQueryParameter("x-bili-device-req-json", "{\"platform\":\"web\",\"device\":\"pc\"}")
        .addQueryParameter("x-bili-web-req-json", "{\"spm_id\":\"333.999\"}").build()
    private fun request(payload: DynamicCreateFeedRequest = original, url: String = exactUrl.toString()) =
        Request.Builder().url(url).post(Json.encodeToString(payload).toRequestBody("application/json".toMediaType())).build()

    @Test fun realOriginalModelsSerializeIntoOneFailureAndOneExplicitRetry() {
        val replay = WindowsVideoDynamicShareReplay(170001L)
        replay.respond(request()) { true }!!.use { assertTrue(it.body!!.string().contains(WindowsVideoDynamicShareReplay.RETRY_ERROR)) }
        replay.respond(request()) { true }!!.use { assertTrue(it.body!!.string().contains("990000027")) }
        val payloads = replay.receipt().getValue("payloads").jsonArray
        assertEquals(listOf(-1, 0), payloads.map { it.jsonObject.getValue("responseCode").jsonPrimitive.int })
        assertTrue(payloads.all { it.jsonObject.getValue("rid").jsonPrimitive.long == 170001L })
        assertFails { replay.respond(request()) { true } }
    }
    @Test fun wrongOriginPortQueryOrMethodDoesNotConsumeAttempt() {
        for (url in listOf(exactUrl.newBuilder().host("foreign.invalid").build(),
            exactUrl.newBuilder().port(444).build(),
            exactUrl.newBuilder().setQueryParameter("csrf", "foreign").build(),
            exactUrl.newBuilder().addQueryParameter("csrf", "LOCAL-COMPOSER-NOT-A-REAL-CSRF").build()).map { it.toString() }) {
            val replay = WindowsVideoDynamicShareReplay(170001L)
            assertFails { replay.respond(request(url = url)) { true } }; assertEquals(0, replay.count())
        }
        assertFails { WindowsVideoDynamicShareReplay(170001L).respond(request().newBuilder().get().build()) { true } }
    }
    @Test fun originalQueryDefaultsMustBePresentExactAndUnique() {
        for (key in exactUrl.queryParameterNames) {
            for (changed in listOf(exactUrl.newBuilder().removeAllQueryParameters(key).build(),
                exactUrl.newBuilder().setQueryParameter(key, "foreign").build(),
                exactUrl.newBuilder().addQueryParameter(key, exactUrl.queryParameter(key)).build())) {
                val replay = WindowsVideoDynamicShareReplay(170001L)
                assertFails { replay.respond(request(url = changed.toString())) { true } }
                assertEquals(0, replay.count())
            }
        }
        val replay = WindowsVideoDynamicShareReplay(170001L)
        assertFails { replay.respond(request(url = exactUrl.newBuilder().addQueryParameter("foreign", "1").build().toString())) { true } }
        assertEquals(0, replay.count())
    }

    @Test fun actualOriginalRepositoryAndRetrofitDefaultsFailThenRetryEntirelyInMemory() {
        val replay = WindowsVideoDynamicShareReplay(170001L)
        val observed = mutableListOf<Request>()
        val escaped = AtomicInteger()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                observed += request
                try {
                    if (request.url.encodedPath == "/x/web-interface/view") {
                        require(request.method == "GET" && request.url.scheme == "https" &&
                            request.url.host == "api.bilibili.com" && request.url.port == 443 &&
                            request.url.queryParameterValues("bvid") == listOf("BV1xx411c7mD"))
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("LOCAL video metadata")
                            .body("{\"code\":0,\"data\":{\"aid\":170001,\"bvid\":\"BV1xx411c7mD\"}}"
                                .toResponseBody("application/json".toMediaType())).build()
                    } else requireNotNull(replay.respond(request) { true }) { "Unmapped original share request" }
                } catch (failure: Exception) { throw IOException("LOCAL share request rejected", failure) }
            }.addInterceptor { escaped.incrementAndGet(); throw IOException("Memory-only share escaped") }.build()
        try {
            val retrofit = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build()
            val api = retrofit.create(BilibiliApi::class.java)
            val dynamicApi = retrofit.create(DynamicApi::class.java)
            runBlocking {
                suspend fun publish() = DesktopOriginalVideoDynamicShareRepository.share(api, dynamicApi,
                    { "LOCAL-COMPOSER-NOT-A-REAL-CSRF" }, "BV1xx411c7mD", WindowsVideoDynamicShareReplay.DRAFT,
                    { true }, { action -> action(); true })
                val failed = publish()
                assertEquals(WindowsVideoDynamicShareReplay.RETRY_ERROR, failed.exceptionOrNull()?.message)
                assertEquals(1, replay.count())
                assertEquals("990000027", publish().getOrThrow())
                assertEquals(2, replay.count())
            }
            assertEquals(listOf("/x/web-interface/view", WindowsVideoDynamicShareReplay.PATH,
                "/x/web-interface/view", WindowsVideoDynamicShareReplay.PATH), observed.map { it.url.encodedPath })
            val publishes = observed.filter { it.url.encodedPath == WindowsVideoDynamicShareReplay.PATH }
            assertTrue(publishes.all { it.url == exactUrl && it.method == "POST" })
            assertEquals(listOf(-1, 0), replay.receipt().getValue("payloads").jsonArray.map {
                it.jsonObject.getValue("responseCode").jsonPrimitive.int })
            assertEquals(0, escaped.get())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun wrongSceneAidTypeOrTextCannotPublishSyntheticSuccess() {
        for (payload in listOf(original.copy(dyn_req = original.dyn_req.copy(scene = 1)),
            original.copy(web_repost_src = DynamicVideoRepostSource(DynamicVideoRepostResource(99, 8))),
            original.copy(web_repost_src = DynamicVideoRepostSource(DynamicVideoRepostResource(170001, 1))),
            original.copy(dyn_req = original.dyn_req.copy(content = DynamicCreateFeedContent(listOf(DynamicRepostContentItem("foreign", 1, ""))))))) {
            val replay = WindowsVideoDynamicShareReplay(170001L)
            assertFails { replay.respond(request(payload)) { true } }; assertEquals(0, replay.count())
        }
    }
    @Test fun unknownSchemaAndRetiredOwnerDoNotAdvance() {
        val replay = WindowsVideoDynamicShareReplay(170001L)
        val unknown = Json.encodeToString(original).dropLast(1) + ",\"foreign\":1}"
        assertFails { replay.respond(request().newBuilder().post(unknown.toRequestBody("application/json".toMediaType())).build()) { true } }
        assertFails { replay.respond(request()) { false } }
        var calls = 0
        assertFails { replay.respond(request()) { ++calls == 1 } }
        assertEquals(0, replay.count())
    }
    @Test fun otherMutationsStillFallThroughToTheUnchangedComposerRejector() {
        val replay = WindowsVideoDynamicShareReplay(170001L)
        for (path in listOf("/x/relation/modify", "/x/web-interface/archive/like", "/x/v2/reply/add", "/x/dynamic/feed/create/dyn/submit")) {
            val candidate = request(url = "https://api.bilibili.com$path")
            assertNull(replay.respond(candidate) { true })
            assertFails { WindowsCommentComposerReplay.requireReadOnly(candidate.method, candidate.url.host, path) }
        }
        assertFails { replay.receipt() }
    }
}
