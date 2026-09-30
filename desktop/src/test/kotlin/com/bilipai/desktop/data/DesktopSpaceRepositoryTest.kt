package com.bilipai.desktop.data

import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopSpaceRepositoryTest {
    private val clients = mutableListOf<OkHttpClient>()
    @AfterTest fun closeFixtures() { clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }; clients.clear() }

    private inner class Fixture(val respond: (Request) -> String, ensure: suspend () -> Unit = {}, epoch: () -> Long = { 1 },
        sign: suspend (Map<String, String>) -> Map<String, String> = { it + mapOf("w_rid" to "fixture-signature", "wts" to "123") }) {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            require(request.url.host in setOf("api.bilibili.com", "api.live.bilibili.com", "app.bilibili.com"))
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("isolated-fixture")
                .body(respond(request).toResponseBody("application/json".toMediaType())).build()
        }.build().also { clients += it }
        val repository = DesktopSpaceRepository(client, ensure, sign, epoch)
    }

    @Test fun createdFoldersUseOriginalPublicApiSourceFilterAndDeduplication(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"count":5,"list":[
            {"id":1,"title":"保留私密拥有者结果","media_count":3,"attr":1},
            {"id":1,"title":"重复","media_count":2},{"id":2,"title":"","media_count":1},
            {"id":3,"title":"空夹","media_count":0},{"id":4,"title":"公开","media_count":1}]}}""" })
        val rows = fixture.repository.createdFavorites(22)
        assertEquals(listOf(1L, 4L), rows.map { it.id }); assertTrue(rows.all { it.source == FavFolderSource.OWNED })
        val request = fixture.requests.single()
        assertEquals("GET", request.method); assertEquals("/x/v3/fav/folder/created/list-all", request.url.encodedPath)
        assertEquals("22", request.url.queryParameter("up_mid")); assertEquals("333.1387", request.url.queryParameter("web_location"))
        assertNull(request.url.queryParameter("rid")); assertNull(request.url.queryParameter("type"))
    }

    @Test fun collectedPaginationUsesRawServerCountEvenWhenDisplayFilterHidesPage(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"count":40,"list":[{"id":7,"title":"空合集","media_count":0}]}}""" })
        val first = fixture.repository.collectedFavorites(22)
        assertTrue(first.items.isEmpty()); assertEquals(2, first.nextPage)
        val last = fixture.repository.collectedFavorites(22, 2); assertNull(last.nextPage)
        val request = fixture.requests.last()
        assertEquals("/x/v3/fav/folder/collected/list", request.url.encodedPath)
        assertEquals("22", request.url.queryParameter("up_mid")); assertEquals("2", request.url.queryParameter("pn"))
        assertEquals("20", request.url.queryParameter("ps")); assertEquals("web", request.url.queryParameter("platform"))
    }

    @Test fun collectedFoldersRemainSubscribedAndFavoriteContentsKeepEveryOriginalBusinessType(): Unit = runBlocking {
        val fixture = Fixture({ request -> if (request.url.encodedPath.endsWith("collected/list"))
            """{"code":0,"data":{"count":1,"list":[{"id":8,"title":"订阅夹","media_count":3}]}}"""
            else """{"code":0,"data":{"has_more":true,"medias":[
                {"id":10,"type":2,"bvid":"BVfixture","title":"视频"},
                {"id":11,"type":12,"title":"专栏"},{"id":12,"type":21,"title":"合集","season_id":33}]}}""" })
        assertEquals(FavFolderSource.SUBSCRIBED, fixture.repository.collectedFavorites(22).items.single().source)
        val result = fixture.repository.favoriteResources(8)
        assertEquals(listOf(2, 12, 21), result.items.map { (it as PersonalResource.Favorite).item.type })
        assertEquals(33, (result.items.last() as PersonalResource.Favorite).item.season_id)
        assertEquals(2, result.nextCursor)
        val request = fixture.requests.last()
        assertEquals("/x/v3/fav/resource/list", request.url.encodedPath)
        assertEquals("8", request.url.queryParameter("media_id")); assertEquals("20", request.url.queryParameter("ps"))
        assertEquals("mtime", request.url.queryParameter("order")); assertEquals("web", request.url.queryParameter("platform"))
    }

    @Test fun articlesUseOriginalWbiFieldsAndRawOpusAliases(): Unit = runBlocking {
        var unsigned: Map<String, String>? = null
        val fixture = Fixture({ """{"code":0,"data":{"pn":2,"has_more":true,"offset":"raw-cursor","lists":[
            {"opus_id":"1000000000000001","content":"完整图文","jump_url":"https://www.bilibili.com/opus/1000000000000001",
             "image_urls":["https://fixture/a.jpg","https://fixture/b.jpg"],"stat":{"view":"42","like":"3"}}]}}""" },
            sign = { unsigned = it; it + mapOf("w_rid" to "fixture", "wts" to "321") })
        val result = fixture.repository.articles(22, 2)
        assertEquals(mapOf("mid" to "22", "pn" to "2", "ps" to "30", "sort" to "publish_time"), unsigned)
        assertEquals("/x/space/wbi/article", fixture.requests.single().url.encodedPath)
        assertEquals("fixture", fixture.requests.single().url.queryParameter("w_rid"))
        assertEquals(3, result.nextPage); assertEquals("raw-cursor", result.data?.offset)
        assertEquals(1000000000000001, result.items.single().id); assertEquals(2, result.items.single().displayImageUrls().size)
        assertEquals("图文 · 42阅读 · 3点赞", buildSpaceArticleStatsText(result.items.single()))
    }

    @Test fun articlesDoNotGuessPaginationFromTotalWhenOriginalHasMoreIsFalse(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"total":300,"lists":[{"id":7,"title":"结束"}],"has_more":false}}""" })
        assertNull(fixture.repository.articles(22).nextPage)
        val empty = Fixture({ """{"code":0,"data":{"has_more":true,"lists":[]}}""" })
        assertNull(empty.repository.articles(22).nextPage) // Avoid endlessly requesting an empty cursor.
    }

    @Test fun chargeRankUsesActualWebParametersAndOnlyOffersServerLevels(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"rank_info":[{"mid":3,"nickname":"fixture","day":8}],
            "level_info":[{"privilege_type":1,"name":"包月","member_total":9},{"privilege_type":2,"name":"年度","member_total":5}]}}""" })
        val result = fixture.repository.chargeRank(22, 2)
        assertEquals(14, result.totalCount); assertFalse(result.usedElecFallback); assertEquals(8, result.items.single().day)
        val request = fixture.requests.single()
        assertEquals("/x/upower/up/member/rank/v2", request.url.encodedPath)
        assertEquals(mapOf("up_mid" to "22", "pn" to "1", "ps" to "100", "privilege_type" to "2", "mobi_app" to "web", "web_location" to "333.1196"),
            request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
    }

    @Test fun emptyWebRankUsesOriginalSignedAppElecFallbackAndAvatarFallback(): Unit = runBlocking {
        val fixture = Fixture({ request -> if (request.url.host == "app.bilibili.com")
            """{"code":0,"data":{"elec":{"total":13,"list":[{"mid":9,"uname":"充电者","avatar":"","face":"https://fixture/face"}]}}}"""
            else """{"code":0,"data":{"rank_info":[],"level_info":[]}}""" })
        val result = fixture.repository.chargeRank(22)
        assertEquals(2, fixture.requests.size); assertTrue(result.usedElecFallback); assertEquals(13, result.totalCount)
        assertEquals("https://fixture/face", result.items.single().avatar); assertEquals(0, result.items.single().day)
        val request = fixture.requests.last()
        assertEquals("/x/v2/space", request.url.encodedPath); assertEquals("22", request.url.queryParameter("vmid"))
        assertEquals("8430300", request.url.queryParameter("build")); assertEquals("android", request.url.queryParameter("mobi_app"))
        assertEquals(AppSignUtils.ANDROID_APP_KEY, request.url.queryParameter("appkey")); assertNull(request.url.queryParameter("access_key"))
        val params = request.url.queryParameterNames.filter { it != "sign" }.associateWith { request.url.queryParameter(it)!! }
        assertEquals(AppSignUtils.signForAndroidApi(params)["sign"], request.url.queryParameter("sign"))
        assertNull(fixture.requests.first().url.queryParameter("privilege_type"))
    }

    @Test fun webRankErrorsNeverTriggerAppFallbackAndOptionalFallbackFailureKeepsEmptyRank(): Unit = runBlocking {
        val denied = Fixture({ """{"code":-403,"message":"没有权限"}""" })
        assertEquals(-403, assertFailsWith<BiliApiException> { denied.repository.chargeRank(22) }.apiCode)
        assertEquals(1, denied.requests.size)
        val empty = Fixture({ request -> if (request.url.host == "app.bilibili.com") """{"code":-101,"message":"expired"}"""
            else """{"code":0,"data":{}}""" })
        val result = empty.repository.chargeRank(22)
        assertTrue(result.items.isEmpty()); assertFalse(result.usedElecFallback)
    }

    @Test fun supportersReuseOriginalGuardDescriptionAndBothAvatarVariants(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"elec":{"total":4,"list":[{"face":"face-elec"}]},
            "guard":{"desc":"320人加入了大航海","count":"invalid","item":[{"avatar":"avatar-guard"}]}}}""" })
        val result = fixture.repository.supporters(22)
        assertEquals(4, result.charge?.count); assertEquals(listOf("face-elec"), result.charge?.avatarUrls)
        assertEquals(320, result.guard?.count); assertEquals(listOf("avatar-guard"), result.guard?.avatarUrls)
    }

    @Test fun guardsUseOriginalLiveApiAndOnlyFirstPageExtractsThreePodiumMembers(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"has_more":1,"guard_top_list":[
            {"uid":1,"username":"总督","guard_level":1},{"uid":2,"guard_level":2},{"uid":3,"guard_level":3},{"uid":4,"guard_level":3}]}}""" })
        val first = fixture.repository.guards(22)
        assertEquals(listOf(1L, 2L, 3L), first.tops.map { it.uid }); assertEquals(listOf(4L), first.items.map { it.uid }); assertEquals(2, first.nextPage)
        val second = fixture.repository.guards(22, 2)
        assertTrue(second.tops.isEmpty()); assertEquals(4, second.items.size)
        val request = fixture.requests.last()
        assertEquals("api.live.bilibili.com", request.url.host); assertEquals("/xlive/app-ucenter/v1/guard/MainGuardCardAll", request.url.encodedPath)
        assertEquals(mapOf("ruid" to "22", "page" to "2", "page_size" to "20"), request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
        assertEquals(listOf("总督", "提督", "舰长", "舰长"), listOf(1, 2, 3, 8).map(::resolveSpaceGuardLevelLabel))
    }

    @Test fun emptyGuardPageAndIntegerLimitStopPagination(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"has_more":1,"guard_top_list":[]}}""" })
        assertNull(fixture.repository.guards(22).nextPage)
        assertNull(nextSpacePage(Int.MAX_VALUE, true))
        assertNull(nextSpacePage(1, 40, 0, 20))
    }

    @Test fun accountSwitchDuringVisitorSetupMakesNoPublicRequest(): Unit = runBlocking {
        var epoch = 1L
        val fixture = Fixture({ fail("Stale owner cannot request") }, ensure = { epoch++ }, epoch = { epoch })
        assertEquals(-101, assertFailsWith<BiliApiException> { fixture.repository.createdFavorites(22) }.apiCode)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun accountSwitchDuringResponseNeverPublishesOldOwnerRows(): Unit = runBlocking {
        var epoch = 1L
        val fixture = Fixture({ epoch++; """{"code":0,"data":{"count":1,"list":[{"id":7,"title":"旧帐号","media_count":1}]}}""" }, epoch = { epoch })
        assertEquals(-101, assertFailsWith<BiliApiException> { fixture.repository.createdFavorites(22) }.apiCode)
    }

    @Test fun signAndSessionCancellationPropagateWithoutTransport(): Unit = runBlocking {
        val duringSign = Fixture({ fail("Cancelled signature must not request") }, sign = { throw CancellationException("fixture") })
        assertFailsWith<CancellationException> { duringSign.repository.articles(22) }
        val duringSetup = Fixture({ fail("Cancelled setup must not request") }, ensure = { throw CancellationException("fixture") })
        assertFailsWith<CancellationException> { duringSetup.repository.guards(22) }
    }

    @Test fun optionalElecFallbackPreservesCancellationAndHandlesOrdinaryFailures(): Unit = runBlocking {
        assertFailsWith<CancellationException> { desktopSpaceElecFallback { throw CancellationException("fixture") } }
        assertNull(desktopSpaceElecFallback { throw java.io.IOException("fixture") })
    }

    @Test fun invalidInputsAndMissingSuccessfulDataRemainExplicitErrors(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0}""" })
        assertFailsWith<IllegalArgumentException> { fixture.repository.guards(0) }
        assertFailsWith<IllegalArgumentException> { fixture.repository.favoriteResources(7, 0) }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(-1, assertFailsWith<BiliApiException> { fixture.repository.guards(22) }.apiCode)
        val expired = Fixture({ """{"code":-111,"message":"original-login-message"}""" })
        assertEquals(-111, assertFailsWith<BiliApiException> { expired.repository.articles(22) }.apiCode)
    }
}
