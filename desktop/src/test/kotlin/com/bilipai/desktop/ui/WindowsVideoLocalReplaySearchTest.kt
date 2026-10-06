package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.BilibiliApi
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Response
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Same memory-only startup handler as actual Main replay; no Main/window/profile/media or real HTTP. */
class WindowsVideoLocalReplaySearchTest {
    private fun withMemoryTransport(
        owns: () -> Boolean = { true },
        block: (OkHttpClient, MutableList<Request>, AtomicInteger) -> Unit,
    ) {
        val observed = mutableListOf<Request>()
        val escaped = AtomicInteger()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                observed += chain.request()
                requireNotNull(WindowsVideoLocalReplay.searchStartupResponse(chain.request(), owns)) {
                    "Only the exact four startup reads may be mapped"
                }
            }.addInterceptor { escaped.incrementAndGet(); error("Memory-only search read reached terminal transport") }.build()
        try { block(client, observed, escaped) }
        finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun originalSearchApiConsumesExactDefaultTrendingAndRecommendDtosWithoutNetwork() {
        withMemoryTransport { client, observed, escaped ->
            val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build().create(SearchApi::class.java)
            runBlocking {
                val default = api.getDefaultSearch(mapOf("wts" to "1", "w_rid" to "synthetic-signature"))
                assertEquals(0, default.code)
                assertEquals("本地搜索回放", requireNotNull(default.data).showName)
                assertEquals("", default.data!!.url)
                val hotword = api.getTrendingList(30)
                assertEquals(0, hotword.code)
                assertEquals(emptyList(), hotword.topList)
                assertEquals(emptyList(), hotword.list)
                val recommend = api.getSearchRecommend(mapOf("build" to "1"))
                assertEquals(0, recommend.code)
                assertEquals(emptyList(), requireNotNull(recommend.data).list)
                val suggestion = api.getSearchSuggest("LOCAL")
                assertEquals(0, suggestion.code)
                assertEquals(emptyList(), requireNotNull(suggestion.result).tag)
            }
            assertEquals(listOf(
                "api.bilibili.com" to "/x/web-interface/wbi/search/default",
                "s.search.bilibili.com" to "/main/hotword",
                "app.bilibili.com" to "/x/v2/search/recommend",
                "s.search.bilibili.com" to "/main/suggest",
            ), observed.map { it.url.host to it.url.encodedPath })
            assertTrue(observed.all { it.method == "GET" && it.url.scheme == "https" && it.url.port == 443 })
            assertEquals(0, escaped.get())
        }
    }


    @Test fun originalLiveApiConsumesOnlyFiveExactEmptyBootstrapDtosWithoutNetwork() {
        val escaped = AtomicInteger()
        val observed = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed += chain.request()
            WindowsVideoLocalReplay.backgroundBootstrapResponse(chain.request()) { true }
                ?: throw IOException("Unmapped background read")
        }.addInterceptor { escaped.incrementAndGet(); error("Background replay escaped") }.build()
        try {
            val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build().create(BilibiliApi::class.java)
            runBlocking {
                val feed = api.getLiveFeedIndex(mapOf("page" to "1"))
                assertEquals(0, feed.code); assertEquals(emptyList(), requireNotNull(feed.data).cardList)
                val recommendation = api.getLiveRecommendList()
                assertEquals(0, recommendation.code)
                assertEquals(emptyList(), requireNotNull(recommendation.data).recommendRoomList)
                val rooms = api.getLiveList()
                assertEquals(0, rooms.code); assertEquals(emptyList(), requireNotNull(rooms.data).getAllRooms())
                val followed = api.getFollowedLive()
                assertEquals(0, followed.code); assertEquals(emptyList(), requireNotNull(followed.data).list)
                val areas = api.getLiveAreaList()
                assertEquals(0, areas.code); assertEquals(emptyList(), areas.data)
            }
            assertEquals(listOf("/xlive/app-interface/v2/index/feed", "/xlive/web-interface/v1/webMain/getMoreRecList",
                "/room/v3/area/getRoomList", "/xlive/web-ucenter/user/following", "/room/v1/Area/getList"),
                observed.map { it.url.encodedPath })
            assertTrue(observed.all { it.url.host == "api.live.bilibili.com" && it.method == "GET" })
            assertEquals(0, escaped.get())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun backgroundMappingsRejectWrongOriginMethodAndRetiredOwner() {
        for (url in listOf("http://api.live.bilibili.com/room/v1/Area/getList",
            "https://api.live.bilibili.com:444/room/v1/Area/getList",
            "https://user:secret@api.live.bilibili.com/room/v1/Area/getList",
            "https://api.live.bilibili.com/room/v1/Area/getList#fragment")) {
            assertFailsWith<IOException> {
                WindowsVideoLocalReplay.backgroundBootstrapResponse(Request.Builder().url(url).build()) { true }
            }
        }
        val request = Request.Builder().url("https://api.live.bilibili.com/room/v1/Area/getList").build()
        for (method in listOf("POST", "HEAD", "DELETE")) assertFailsWith<IOException> {
            WindowsVideoLocalReplay.backgroundBootstrapResponse(request.newBuilder()
                .method(method, if (method == "POST") ByteArray(0).toRequestBody() else null).build()) { true }
        }
        assertFailsWith<IOException> { WindowsVideoLocalReplay.backgroundBootstrapResponse(request) { false } }
        val checks = AtomicInteger()
        assertFailsWith<IOException> {
            WindowsVideoLocalReplay.backgroundBootstrapResponse(request) { checks.incrementAndGet() == 1 }
        }
        assertEquals(2, checks.get())
        for (url in listOf("https://api.live.bilibili.com/room/v1/Area/getList/",
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo",
            "https://api.live.bilibili.com.evil.invalid/room/v1/Area/getList",
            "http://127.0.0.1:12345/video.avi"))
            assertNull(WindowsVideoLocalReplay.backgroundBootstrapResponse(Request.Builder().url(url).build()) { true })
    }

    @Test fun unknownOriginIsAnAsyncIoFailureWithoutUncaughtWorkerThrowableOrNetwork() {
        assertAsyncIoRejection("https://unmapped.invalid/bootstrap", { true },
            "LOCAL replay forbids requests outside mapped API or exact owned loopback media")
    }

    @Test fun retiredOwnerBeforeAndAfterMemoryResponseUsesAsyncIoWithoutWorkerEscape() {
        assertAsyncIoRejection("https://api.live.bilibili.com/room/v1/Area/getList", { false },
            "LOCAL replay exact Root/session owner retired")
        val checks = AtomicInteger()
        assertAsyncIoRejection("https://api.live.bilibili.com/room/v1/Area/getList", { checks.incrementAndGet() <= 2 },
            "Owned background bootstrap replay retired")
        assertEquals(3, checks.get())
    }

    private fun assertAsyncIoRejection(url: String, owns: () -> Boolean, expectedMessage: String) {
        val uncaught = AtomicInteger(); val escaped = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor { action ->
            Thread(action, "Owned replay rejection test").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> uncaught.incrementAndGet() }
            }
        }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).addInterceptor { chain ->
            WindowsVideoLocalReplay.requireReplayOwner(owns)
            WindowsVideoLocalReplay.backgroundBootstrapResponse(chain.request(), owns)?.let { return@addInterceptor it }
            WindowsVideoLocalReplay.requireMappedApiHost(chain.request())
            throw IOException("Unexpected mapped request in unknown-origin test")
        }.addInterceptor { escaped.incrementAndGet(); error("Rejected replay escaped") }.build()
        val done = CountDownLatch(1); val actual = AtomicReference<IOException?>()
        val returned = AtomicInteger(); val failed = AtomicInteger()
        try {
            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) { failed.incrementAndGet(); actual.set(failure); done.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); returned.incrementAndGet(); done.countDown() }
            })
            assertTrue(done.await(3, TimeUnit.SECONDS), "Actual OkHttp callback did not finish")
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(expectedMessage, actual.get()?.message)
            assertNull(actual.get()?.cause, "Rejection must not be canceled-due-to unchecked throwable")
            assertEquals(1, failed.get())
            assertEquals(0, returned.get()); assertEquals(0, uncaught.get()); assertEquals(0, escaped.get())
        } finally { executor.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun exactReadOriginRejectsOtherSchemePortCredentialsAndFragments() {
        withMemoryTransport { client, _, escaped ->
            for (url in listOf(
                "http://s.search.bilibili.com/main/hotword",
                "https://s.search.bilibili.com:444/main/hotword",
                "https://user:secret@s.search.bilibili.com/main/hotword",
                "https://s.search.bilibili.com/main/hotword#unexpected",
                "http://api.bilibili.com/x/web-interface/wbi/search/default",
                "https://app.bilibili.com:444/x/v2/search/recommend",
            )) {
                assertFailsWith<IllegalArgumentException>(url) {
                    client.newCall(Request.Builder().url(url).build()).execute().close()
                }
            }
            assertEquals(0, escaped.get())
        }
    }

    @Test fun unknownHostPathDynamicUpdateAndMediaAreNotNewlyMapped() {
        withMemoryTransport { client, _, escaped ->
            for (url in listOf(
                "https://s.search.bilibili.com.evil.invalid/main/hotword",
                "https://api.bilibili.com/main/hotword",
                "https://app.bilibili.com/x/web-interface/wbi/search/default",
                "https://s.search.bilibili.com/main/hotword/",
                "https://api.bilibili.com/x/web-interface/wbi/search/default/extra",
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all/update",
                "http://127.0.0.1:12345/video.avi",
            )) {
                assertFailsWith<IllegalArgumentException>(url) {
                    client.newCall(Request.Builder().url(url).build()).execute().close()
                }
            }
            assertEquals(0, escaped.get())
        }
    }

    @Test fun nonGetAndMutationRequestsAreRejectedBeforeTerminalTransport() {
        withMemoryTransport { client, _, escaped ->
            for (url in listOf("https://s.search.bilibili.com/main/hotword",
                "https://api.bilibili.com/x/web-interface/wbi/search/default",
                "https://app.bilibili.com/x/v2/search/recommend")) {
                for (method in listOf("POST", "HEAD", "DELETE")) {
                    assertFailsWith<IllegalArgumentException>(method) {
                        client.newCall(Request.Builder().url(url)
                            .method(method, if (method == "POST") ByteArray(0).toRequestBody() else null).build()).execute().close()
                    }
                }
            }
            assertFailsWith<IllegalArgumentException> {
                client.newCall(Request.Builder().url("https://api.bilibili.com/x/v2/reply/add")
                    .post(ByteArray(0).toRequestBody()).build()).execute().close()
            }
            assertEquals(0, escaped.get())
        }
    }

    @Test fun retiredOwnerBeforeOrAfterResponseCannotReturnStartupData() {
        withMemoryTransport(owns = { false }) { client, _, escaped ->
            assertFailsWith<IllegalStateException> {
                client.newCall(Request.Builder().url("https://s.search.bilibili.com/main/hotword").build()).execute().close()
            }
            assertEquals(0, escaped.get())
        }
        val checks = AtomicInteger()
        withMemoryTransport(owns = { checks.incrementAndGet() == 1 }) { client, _, escaped ->
            assertFailsWith<IllegalStateException> {
                client.newCall(Request.Builder().url("https://api.bilibili.com/x/web-interface/wbi/search/default").build()).execute().close()
            }
            assertEquals(2, checks.get())
            assertEquals(0, escaped.get())
        }
    }
}
