package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.SearchApi
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
                    "Only the exact three startup reads may be mapped"
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
            }
            assertEquals(listOf(
                "api.bilibili.com" to "/x/web-interface/wbi/search/default",
                "s.search.bilibili.com" to "/main/hotword",
                "app.bilibili.com" to "/x/v2/search/recommend",
            ), observed.map { it.url.host to it.url.encodedPath })
            assertTrue(observed.all { it.method == "GET" && it.url.scheme == "https" && it.url.port == 443 })
            assertEquals(0, escaped.get())
        }
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
