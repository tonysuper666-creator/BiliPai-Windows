package com.bilipai.desktop.data

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopSpaceContributionsTest {
    private val clients = mutableListOf<OkHttpClient>()
    @AfterTest fun closeFixtures() { clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }; clients.clear() }
    private inner class Fixture(val respond: (Request) -> String, ensure: suspend () -> Unit = {}, epoch: () -> Long = { 1 },
        credentials: () -> Pair<String?,String> = { null to DesktopTokenPlatform.ACCESS_TOKEN_PLATFORM_ANDROID }) {
        val requests: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); require(request.url.host in setOf("api.bilibili.com", "app.bilibili.com")); requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("isolated-fixture")
                .body(respond(request).toResponseBody("application/json".toMediaType())).build()
        }.build().also { clients += it }
        val repository = DesktopSpaceContributionsRepository(client, ensure, { it + ("w_rid" to "fixture") }, credentials, epoch)
    }

    @Test fun oldestPostsProbeLastPageReverseRowsAndMoveBackwards(): Unit = runBlocking {
        val fixture = Fixture({ request -> """{"code":0,"data":{"page":{"pn":${request.url.queryParameter("pn")},"count":65,"ps":30},
            "list":{"vlist":[{"aid":1,"bvid":"BV-newer"},{"aid":2,"bvid":"BV-older"}]}}}""" })
        val first = fixture.repository.videos(22, order = VideoSortOrder.OLDEST_PUBDATE)
        assertEquals(listOf("1", "3"), fixture.requests.map { it.url.queryParameter("pn") })
        assertEquals(3, first.page); assertEquals(2, first.nextPage); assertEquals(listOf(2L, 1L), first.items.map { it.aid })
        assertEquals(1, fixture.repository.videos(22, first.nextPage, VideoSortOrder.OLDEST_PUBDATE).nextPage)
        assertNull(fixture.repository.videos(22, 1, VideoSortOrder.OLDEST_PUBDATE).nextPage)
        assertTrue(fixture.requests.all { it.url.queryParameter("order") == "pubdate" })
    }

    @Test fun videoSortCategoryAndKeywordUseOriginalWbiParameters(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"page":{"count":61},"list":{"vlist":[{"aid":1}]}}}""" })
        assertEquals(2, fixture.repository.videos(22, order = VideoSortOrder.CLICK, categoryId = 17, keyword = "中文 & keyword").nextPage)
        val request = fixture.requests.single()
        assertEquals("/x/space/wbi/arc/search", request.url.encodedPath)
        assertEquals(mapOf("mid" to "22", "pn" to "1", "ps" to "30", "order" to "click", "tid" to "17", "keyword" to "中文 & keyword", "w_rid" to "fixture"),
            request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
        val empty = Fixture({ """{"code":0,"data":{"page":{"count":100},"list":{"vlist":[]}}}""" })
        assertNull(empty.repository.videos(22).nextPage)
    }

    @Test fun zeroVideoTotalsDoNotRequestAnImaginaryLastPage(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{}}""" })
        val result = fixture.repository.videos(22, order = VideoSortOrder.OLDEST_PUBDATE)
        assertEquals(1, fixture.requests.size); assertEquals(1, result.page); assertNull(result.nextPage)
    }

    @Test fun aggregateMetadataUsesOriginalAppSignatureAndServerDrivenContributionPolicies(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"default_tab":"contribute","card":{"name":"UP"},"tab2":[
            {"param":"home","title":"首页"},{"param":"cheese","title":"课程"},
            {"param":"contribute","title":"作品","items":[{"param":"audio","title":"音乐"},{"param":"opus","title":"图文"}]}]}}""" })
        val result = fixture.repository.metadata(22)
        val request = fixture.requests.single()
        assertEquals("/x/v2/space", request.url.encodedPath); assertEquals("22", request.url.queryParameter("vmid"))
        assertEquals(AppSignUtils.ANDROID_HD_APP_KEY, request.url.queryParameter("appkey"))
        assertEquals("contribute", result.data.defaultTab); assertEquals("UP", result.data.card?.name)
        assertEquals(listOf("首页", "动态", "作品", "课程"), result.mainTabs.map { it.title })
        assertEquals(listOf("音乐", "图文"), result.contributionTabs.map { it.title })
        assertEquals(listOf(com.android.purebilibili.feature.space.SpaceSubTab.AUDIO, com.android.purebilibili.feature.space.SpaceSubTab.OPUS),
            result.contributionTabs.map { it.subTab })
    }

    @Test fun interactionsUseOriginalSignedAppContractAndDetailedOrAggregateMapping(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"count":9,"item":[{"param":"170001","first_cid":18,"title":"App项目","author":"UP","play":23,"duration":36}]}}""" },
            credentials = { "fixture-token-never-networked" to DesktopTokenPlatform.ACCESS_TOKEN_PLATFORM_TV })
        val result = fixture.repository.interactions(22, coins = true)
        val request = fixture.requests.single()
        assertEquals("/x/v2/space/coinarc", request.url.encodedPath); assertEquals("app.bilibili.com", request.url.host)
        assertEquals("22", request.url.queryParameter("vmid")); assertEquals("20", request.url.queryParameter("ps"))
        assertEquals("fixture-token-never-networked", request.url.queryParameter("access_key"))
        assertEquals(AppSignUtils.TV_APP_KEY, request.url.queryParameter("appkey")); assertNotNull(request.url.queryParameter("sign"))
        assertEquals("cronet", request.header("bili-http-engine")); assertEquals("android64", request.header("app-key"))
        assertEquals(170001, result.items.single().aid); assertEquals("BV17x411w7KC", result.items.single().bvid)
        assertEquals(18, result.items.single().cid); assertEquals("UP", result.items.single().owner.name)
    }

    @Test fun likesCanFallbackToWebButCoinErrorsCannot(): Unit = runBlocking {
        val likes = Fixture({ request -> if (request.url.host == "app.bilibili.com") """{"code":0,"data":{}}"""
            else """{"code":0,"data":{"list":[{"aid":3,"bvid":"BV-real","title":"Web项目"}]}}""" })
        assertEquals("Web项目", likes.repository.interactions(22).items.single().title)
        assertEquals(listOf("/x/v2/space/likearc", "/x/space/like/video"), likes.requests.map { it.url.encodedPath })
        val coins = Fixture({ """{"code":-403,"message":"permission"}""" })
        assertEquals(-403, assertFailsWith<BiliApiException> { coins.repository.interactions(22, coins = true) }.apiCode)
        assertEquals(1, coins.requests.size)
    }

    @Test fun interactionFullPageContinuesWithMissingTotalsAndDuplicatePageStops(): Unit = runBlocking {
        val items = (1..20).joinToString(",") { """{"aid":$it,"bvid":"BV-$it"}""" }
        val fixture = Fixture({ """{"code":0,"data":{"list":[$items]}}""" })
        val first = fixture.repository.interactions(22, page = 2)
        assertEquals(3, first.nextPage); assertEquals(20, first.total)
        assertNull(fixture.repository.interactions(22, page = 3, previous = first.items).nextPage)
    }

    @Test fun followedBangumiReadsRequestedPublicMidAndStopsNonAdvancingPages(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"pn":1,"ps":30,"total":60,"list":[{"season_id":9,"media_id":10,"title":"番剧","progress":"看到第5话"}]}}""" })
        val first = fixture.repository.followedBangumi(22)
        assertEquals(2, first.nextPage); assertEquals("看到第5话", first.items.single().progress)
        val request = fixture.requests.first()
        assertEquals("/x/space/bangumi/follow/list", request.url.encodedPath)
        assertEquals(mapOf("vmid" to "22", "type" to "1", "pn" to "1", "ps" to "30"), request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
        assertNull(fixture.repository.followedBangumi(22, 2, first.items).nextPage)
    }

    @Test fun coursesKeepRawMarksStatusAndOriginalNextFlag(): Unit = runBlocking {
        val fixture = Fixture({ """{"code":0,"data":{"page":{"next":true},"items":[{"season_id":8,"title":"课程","marks":["限时免费"],"status":"更新中","ctime":"昨天"}]}}""" })
        val first = fixture.repository.courses(22)
        assertEquals(2, first.nextPage); assertEquals(listOf("限时免费"), first.items.single().marks); assertEquals("昨天", first.items.single().ctime)
        val request = fixture.requests.single()
        assertEquals("/pugv/app/web/season/page", request.url.encodedPath)
        assertEquals(mapOf("mid" to "22", "pn" to "1", "ps" to "30", "web_location" to "333.1387"), request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
        val empty = Fixture({ """{"code":0,"data":{"page":{"next":true},"items":[]}}""" })
        assertNull(empty.repository.courses(22).nextPage)
    }

    @Test fun audioFullPageWithoutTotalsContinuesAndDuplicatePageTerminates(): Unit = runBlocking {
        val items = (1..30).joinToString(",") { """{"id":$it,"title":"音频$it","statistic":{"play":42,"collect":3}}""" }
        val fixture = Fixture({ """{"code":0,"data":{"data":[$items]}}""" })
        val first = fixture.repository.audios(22)
        assertEquals(2, first.nextPage); assertEquals(42, first.items.first().statistic?.play)
        val request = fixture.requests.first()
        assertEquals("/audio/music-service/web/song/upper", request.url.encodedPath)
        assertEquals(mapOf("uid" to "22", "pn" to "1", "ps" to "30", "order" to "1", "jsonp" to "jsonp"), request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
        val repeated = fixture.repository.audios(22, 2, first.items)
        assertTrue(repeated.items.isEmpty()); assertNull(repeated.nextPage)
    }

    @Test fun pinnedAbsenceAndNoticeAreIndependentOriginalHomeSections(): Unit = runBlocking {
        val fixture = Fixture({ request -> if (request.url.encodedPath.endsWith("top/arc")) """{"code":-404}"""
            else """{"code":0,"data":"UP公告"}""" })
        val home = fixture.repository.home(22)
        assertNull(home.topVideo); assertEquals("UP公告", home.notice)
        assertEquals(setOf("/x/space/top/arc", "/x/space/notice"), fixture.requests.map { it.url.encodedPath }.toSet())
    }

    @Test fun oldOwnerCannotPublishAndCancellationIsNotConvertedToOptionalSuccess(): Unit = runBlocking {
        var epoch = 1L
        val fixture = Fixture({ epoch++; """{"code":0,"data":{}}""" }, epoch = { epoch })
        assertEquals(-101, assertFailsWith<BiliApiException> { fixture.repository.courses(22) }.apiCode)
        assertFailsWith<CancellationException> { optionalSpaceHome<String> { throw CancellationException("fixture") } }
        assertNull(optionalSpaceHome<String> { throw java.io.IOException("fixture") })
    }

    @Test fun contributionInputsAreValidatedBeforeSessionOrRequest(): Unit = runBlocking {
        val fixture = Fixture({ fail("Must not request") }, ensure = { fail("Must not initialize visitor") })
        assertFailsWith<IllegalArgumentException> { fixture.repository.videos(0) }
        assertFailsWith<IllegalArgumentException> { fixture.repository.followedBangumi(22, 0) }
        assertFailsWith<IllegalArgumentException> { fixture.repository.audios(22, -1) }
        assertTrue(fixture.requests.isEmpty())
    }
}
