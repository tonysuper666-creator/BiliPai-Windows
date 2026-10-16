package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.data.repository.DesktopOriginalSearchRepository
import com.android.purebilibili.data.repository.SearchOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Actual original repository + actual generated Retrofit API, isolated from real accounts. */
class DesktopSearchAllTest {
    private class Fixture(val response: (Request) -> String) : AutoCloseable {
        val owned = AtomicBoolean(true)
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(requests) { requests += request }
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200)
                .message("OK").body(response(request).toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val api = Retrofit.Builder().baseUrl("https://search-fixture.invalid/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(SearchApi::class.java)
        private val nav = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, _ ->
            error("Captured WBI keys must be used; unexpected nav call: ${method.name}")
        } as BilibiliApi
        val repository = DesktopOriginalSearchRepository(api, nav,
            { Result.success("0123456789abcdef0123456789abcdef" to "fedcba9876543210fedcba9876543210") },
            { owned.get() }, { null to "android" })
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    private fun mixed(page: Int = 1, pages: Int = 2) = """
        {"code":0,"data":{"page":$page,"pagesize":20,"numPages":$pages,"numResults":22,"result":[
          {"result_type":"video","data":[{"bvid":"BVall1","id":101,"title":"<em>视频</em>","mid":7,"author":"up"}]},
          {"result_type":"bili_user","data":[{"mid":"1234567890123","uname":"<em>用户</em>","fans":"42","res":[{"bvid":"BVrelated","id":102,"mid":7}]}]},
          {"result_type":"media_bangumi","data":[{"season_id":99,"title":"<em>番剧</em>","cover":"//img.invalid/cover"}]},
          {"result_type":"unknown_future_category","data":[{"future":true}]}
        ]}}
    """.trimIndent()

    @Test fun actualAllEndpointDecodesMixedCategoriesAndRelatedVideos(): Unit = runBlocking {
        Fixture { mixed() }.use { fixture ->
            val result = fixture.repository.searchAllPage("测试").getOrThrow()
            assertEquals(listOf("BVall1"), result.videos.map { it.bvid })
            assertEquals(1234567890123L, result.ups.single().mid)
            assertEquals(42, result.ups.single().fans)
            assertEquals("BVrelated", result.ups.single().res!!.single().bvid)
            assertEquals(99L, result.bangumi.single().seasonId)
            assertFalse(result.bangumi.single().title.contains("<em>"))
            assertEquals("https://img.invalid/cover", result.bangumi.single().cover)
            assertTrue(result.pageInfo.hasMore)
        }
    }

    @Test fun allRequestKeepsOriginalHeadersWbiAndSingleEncoding(): Unit = runBlocking {
        Fixture { mixed() }.use { fixture ->
            fixture.repository.searchAllPage("中文 & + % 搜索", page = 2).getOrThrow()
            val request = fixture.requests.single()
            assertEquals("/x/web-interface/wbi/search/all/v2", request.url.encodedPath)
            assertEquals("中文 & + % 搜索", request.url.queryParameter("keyword"))
            assertEquals("all", request.url.queryParameter("search_type"))
            assertEquals("2", request.url.queryParameter("page"))
            assertEquals("20", request.url.queryParameter("page_size"))
            assertFalse(request.url.queryParameter("w_rid").isNullOrBlank())
            assertEquals("https://search.bilibili.com", request.header("Origin"))
            assertEquals("https://search.bilibili.com/", request.header("Referer"))
        }
    }

    @Test fun filteredAllUsesOriginalVideoTypeEndpointAndThirtyItemPage(): Unit = runBlocking {
        Fixture { """{"code":0,"data":{"page":2,"pagesize":30,"numPages":3,"numResults":80,"result":[{"bvid":"BVfiltered","id":3}]}}""" }.use { fixture ->
            val result = fixture.repository.searchAllPage("q", page = 2, order = SearchOrder.PUBDATE,
                tids = 234, pubBegin = 10, pubEnd = 20).getOrThrow()
            val request = fixture.requests.single()
            assertEquals("/x/web-interface/wbi/search/type", request.url.encodedPath)
            assertEquals("pubdate", request.url.queryParameter("order"))
            assertEquals("30", request.url.queryParameter("page_size"))
            assertEquals("234", request.url.queryParameter("tids"))
            assertEquals("10", request.url.queryParameter("pubtime_begin_s"))
            assertEquals(listOf("BVfiltered"), result.videos.map { it.bvid })
            assertTrue(result.ups.isEmpty()); assertTrue(result.bangumi.isEmpty())
        }
    }

    @Test fun allStopsAtServerLastPage(): Unit = runBlocking {
        Fixture { mixed(page = 2) }.use { fixture ->
            val info = fixture.repository.searchAllPage("q", page = 2).getOrThrow().pageInfo
            assertEquals(2, info.currentPage); assertFalse(info.hasMore)
        }
    }

    @Test fun missingPagingMetadataKeepsNonemptyPageProbe(): Unit = runBlocking {
        Fixture { """{"code":0,"data":{"result":[{"result_type":"video","data":[{"bvid":"BVprobe"}]}]}}""" }.use { fixture ->
            val info = fixture.repository.searchAllPage("q", page = 3).getOrThrow().pageInfo
            assertEquals(3, info.currentPage); assertTrue(info.hasMore)
        }
    }

    @Test fun malformedCategoryItemDoesNotDropValidSiblingOrOtherCategory(): Unit = runBlocking {
        Fixture { """{"code":0,"data":{"result":[{"result_type":"bili_user","data":[{"mid":"18","res":{}},{"mid":"19","uname":"valid"}]},{"result_type":"video","data":[{"bvid":"BVvalid"}]}]}}""" }.use { fixture ->
            val result = fixture.repository.searchAllPage("q").getOrThrow()
            assertEquals(listOf(19L), result.ups.map { it.mid })
            assertEquals(listOf("BVvalid"), result.videos.map { it.bvid })
        }
    }

    @Test fun serverFailureRemainsFailureInsteadOfEmptySuccessfulPage(): Unit = runBlocking {
        Fixture { """{"code":-352,"message":"fixture risk control"}""" }.use { fixture ->
            assertTrue(fixture.repository.searchAllPage("q").isFailure)
        }
    }

    @Test fun retiredRootCannotReturnSuccessfulAllResponse(): Unit = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture { fixture.owned.set(false); mixed() }
        fixture.use {
            assertFailsWith<CancellationException> { fixture.repository.searchAllPage("q") }
        }
    }
}
