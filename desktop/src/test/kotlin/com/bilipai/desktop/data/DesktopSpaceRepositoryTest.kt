package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopSpaceRepositoryTest {
    private val clients = mutableListOf<OkHttpClient>()
    @AfterTest fun closeFixtures() { clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }; clients.clear() }

    private inner class Fixture(val respond: (Request) -> String, ensure: suspend () -> Unit = {}, epoch: () -> Long = { 1 }) {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            require(request.url.host in setOf("api.bilibili.com", "api.live.bilibili.com", "app.bilibili.com"))
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("isolated-fixture")
                .body(respond(request).toResponseBody("application/json".toMediaType())).build()
        }.build().also { clients += it }
        val repository = DesktopSpaceRepository(client, ensure, epoch)
    }

    @Test fun favoriteContentsKeepEveryOriginalBusinessType(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"has_more":true,"medias":[
                {"id":10,"type":2,"bvid":"BVfixture","title":"视频"},
                {"id":11,"type":12,"title":"专栏"},{"id":12,"type":21,"title":"合集","season_id":33}]}}""" })
        val result = fixture.repository.favoriteResources(8)
        assertEquals(listOf(2, 12, 21), result.items.map { (it as PersonalResource.Favorite).item.type })
        assertEquals(33, (result.items.last() as PersonalResource.Favorite).item.season_id)
        assertEquals(2, result.nextCursor)
        val request = fixture.requests.last()
        assertEquals("/x/v3/fav/resource/list", request.url.encodedPath)
        assertEquals("8", request.url.queryParameter("media_id")); assertEquals("20", request.url.queryParameter("ps"))
        assertEquals("mtime", request.url.queryParameter("order")); assertEquals("web", request.url.queryParameter("platform"))
    }

    @Test fun integerLimitAndAbsentMoreFlagStopPagination() {
        assertNull(nextSpacePage(Int.MAX_VALUE, true))
        assertNull(nextSpacePage(1, false))
        assertEquals(2, nextSpacePage(1, true))
    }

    @Test fun accountSwitchDuringVisitorSetupMakesNoPublicRequest(): Unit = runBlocking {
        var epoch = 1L
        val fixture = Fixture({ fail("Stale owner cannot request") }, ensure = { epoch++ }, epoch = { epoch })
        assertEquals(-101, assertFailsWith<BiliApiException> { fixture.repository.favoriteResources(22) }.apiCode)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun accountSwitchDuringResponseNeverPublishesOldOwnerRows(): Unit = runBlocking {
        var epoch = 1L
        val fixture = Fixture({ epoch++; """{"code":0,"data":{"has_more":false,"medias":[{"id":10,"type":2,"bvid":"BVold","title":"旧帐号"}]}}""" }, epoch = { epoch })
        assertEquals(-101, assertFailsWith<BiliApiException> { fixture.repository.favoriteResources(22) }.apiCode)
    }

    @Test fun sessionCancellationPropagatesWithoutTransport(): Unit = runBlocking {
        val duringSetup = Fixture({ fail("Cancelled setup must not request") }, ensure = { throw CancellationException("fixture") })
        assertFailsWith<CancellationException> { duringSetup.repository.favoriteResources(22) }
    }

    @Test fun invalidInputsAndMissingSuccessfulDataRemainExplicitErrors(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0}""" })
        assertFailsWith<IllegalArgumentException> { fixture.repository.favoriteResources(0) }
        assertFailsWith<IllegalArgumentException> { fixture.repository.favoriteResources(7, 0) }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(-1, assertFailsWith<BiliApiException> { fixture.repository.favoriteResources(22) }.apiCode)
        val expired = Fixture({ """{"code":-111,"message":"original-login-message"}""" })
        assertEquals(-111, assertFailsWith<BiliApiException> { expired.repository.favoriteResources(22) }.apiCode)
    }
}
