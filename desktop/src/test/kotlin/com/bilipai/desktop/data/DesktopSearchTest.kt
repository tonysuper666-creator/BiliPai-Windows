package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.SearchHistory
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.search.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import kotlin.test.*

class DesktopSearchTest {
    @Test fun videoDefaultsOmitUnsetZoneAndPublishBoundsButRetainOriginalIdentity() {
        val params = desktopVideoSearchParams("视频", SearchOrder.TOTALRANK, SearchDuration.ALL, 0, 1, null, null)
        assertEquals("totalrank", params["order"]); assertEquals("0", params["duration"])
        assertEquals("20", params["page_size"]); assertEquals("pc", params["platform"]); assertEquals("1430654", params["web_location"])
        assertFalse("tids" in params); assertFalse("pubtime_begin_s" in params); assertFalse("pubtime_end_s" in params)
        val filtered = desktopVideoSearchParams("q", SearchOrder.DM, SearchDuration.OVER_60MIN, 234, 2, 10, 20)
        assertEquals("dm", filtered["order"]); assertEquals("4", filtered["duration"]); assertEquals("234", filtered["tids"])
        assertEquals("10", filtered["pubtime_begin_s"]); assertEquals("20", filtered["pubtime_end_s"])
    }

    @Test fun typedFiltersKeepOriginalUpLiveAndCategoryWireValuesSeparate() {
        val filters = DesktopSearchFilters(upOrder = SearchUpOrder.FANS, upSort = SearchOrderSort.ASC, userType = SearchUserType.VERIFIED,
            liveOrder = SearchLiveOrder.LIVE_TIME, articleOrder = SearchOrder.ATTENTION, articleCategory = SearchArticleCategory.LIGHT_NOVEL,
            photoOrder = SearchOrder.STOW, photoCategory = SearchPhotoCategory.PHOTOGRAPHY)
        assertEquals(mapOf("order" to "fans", "order_sort" to "1", "user_type" to "3"), filters.parameters(SearchType.UP))
        assertEquals(mapOf("order" to "live_time"), filters.parameters(SearchType.LIVE))
        assertEquals(mapOf("order" to "attention", "category_id" to "16"), filters.parameters(SearchType.ARTICLE))
        assertEquals(mapOf("order" to "stow", "category_id" to "2"), filters.parameters(SearchType.PHOTO))
        assertTrue(filters.parameters(SearchType.BANGUMI).isEmpty()); assertTrue(filters.parameters(SearchType.TOPIC).isEmpty())
        assertEquals(DesktopSearchFilters().requestKey(SearchType.UP), DesktopSearchFilters(videoTid = 1).requestKey(SearchType.UP))
    }

    @Test fun originalOptionsIncludeEveryVideoOrderDurationAndPartition() {
        assertEquals(listOf(SearchOrder.TOTALRANK, SearchOrder.CLICK, SearchOrder.PUBDATE, SearchOrder.DM, SearchOrder.STOW, SearchOrder.SCORES), resolveSearchVideoOrderOptions())
        assertEquals(SearchDuration.entries.toList(), resolveSearchVideoDurationOptions())
        assertEquals(22, resolveSearchVideoZoneOptions().size); assertTrue(resolveSearchVideoZoneOptions().any { it.tid == 167 })
        assertEquals(listOf(SearchLandingSection.TRENDING, SearchLandingSection.HISTORY, SearchLandingSection.DISCOVER), resolveSearchLandingSectionOrder())
    }

    @Test fun publishRangeResetAndCustomReversalUseOriginalPolicy() {
        val now = 1_750_000_000_000L
        val week = DesktopSearchFilters().withPubType(SearchVideoPubTimeType.WEEK, now)
        assertEquals(resolveSearchPubTimeRange(SearchVideoPubTimeType.WEEK, now).beginEpochSeconds, week.pubBegin)
        assertTrue(requireNotNull(week.pubEnd) > requireNotNull(week.pubBegin))
        val custom = week.withCustomRange(200, 100)
        assertEquals(100L, custom.pubBegin); assertEquals(200L, custom.pubEnd)
        val reset = custom.withPubType(SearchVideoPubTimeType.ALL, now)
        assertNull(reset.pubBegin); assertNull(reset.pubEnd)
    }

    @Test fun durationSelectionSupportsAllAndMultipleOriginalRequests() {
        var selected = toggleSearchDurationSelection(emptySet(), SearchDuration.UNDER_10MIN)
        selected = toggleSearchDurationSelection(selected, SearchDuration.OVER_60MIN)
        assertEquals(listOf(SearchDuration.UNDER_10MIN, SearchDuration.OVER_60MIN), resolveSearchDurationRequests(selected))
        assertTrue(toggleSearchDurationSelection(selected, SearchDuration.ALL).isEmpty())
        assertEquals(listOf(SearchDuration.ALL), resolveSearchDurationRequests(setOf(SearchDuration.ALL, SearchDuration.UNDER_10MIN)))
    }

    @Test fun mergedDurationsKeepFirstOriginalRecordAndContinueAfterShortNonemptyPage() {
        val first = SearchVideoItem(bvid = "BV1", title = "first", mid = 5, author = "up", pubdate = 123)
        val second = SearchVideoItem(bvid = "BV2", title = "second", mid = 6)
        val pages = listOf(SearchTypeData(page = 1, numPages = 1, result = listOf(first)),
            SearchTypeData(page = 1, numPages = 1, result = listOf(first.copy(title = "duplicate"), second)))
        val result = mergeDesktopSearchVideoPages(pages, 2)
        assertEquals(listOf(first, second), result.first.result); assertEquals(2, result.first.page); assertEquals(3, result.second)
        assertEquals(5L, result.first.result!!.first().mid); assertEquals(123L, result.first.result!!.first().pubdate)
        assertNull(mergeDesktopSearchVideoPages(listOf(SearchTypeData(page = 2, result = emptyList())), 2).second)
    }

    @Test fun originalTypeBuilderAndRetrofitEncodeSearchTermsOnce(): Unit = runBlocking {
        var captured: HttpUrl? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request().url
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"code\":0,\"data\":{\"result\":[]}}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build().create(SearchApi::class.java)
        val params = desktopSearchTypeParams("中文 & + % 搜索", "bili_user", 3, mapOf("order" to "fans"))
        api.searchUp(params)
        assertEquals("/x/web-interface/wbi/search/type", captured!!.encodedPath)
        assertEquals("中文 & + % 搜索", captured!!.queryParameter("keyword")); assertEquals("bili_user", captured!!.queryParameter("search_type"))
        assertEquals("3", captured!!.queryParameter("page")); assertEquals("fans", captured!!.queryParameter("order"))
    }

    @Test fun historyReplacesKeywordTimestampAndDisplaysLatestTwentyWithoutTrimmingStorage(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-search-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val dao = DesktopSearchHistoryDao(context)
        repeat(25) { dao.insert(SearchHistory("q$it", it.toLong())) }
        assertEquals(20, dao.getAll().value.size); assertEquals("q24", dao.getAll().value.first().keyword)
        dao.insert(SearchHistory("q10", 100))
        assertEquals("q10", dao.getAll().value.first().keyword); assertEquals(1, dao.getAll().value.count { it.keyword == "q10" })
        dao.delete(SearchHistory("q10", 0))
        assertFalse(dao.getAll().value.any { it.keyword == "q10" }); assertEquals("q4", dao.getAll().value.last().keyword)
        val restored = DesktopSearchHistoryDao(DesktopPluginContext(DesktopPluginStore(root)))
        assertEquals(dao.getAll().value, restored.getAll().value)
    }

    @Test fun historyRoundTripsUnicodeAndControlCharactersAndClearIsLocal(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-search-")
        val dao = DesktopSearchHistoryDao(DesktopPluginContext(DesktopPluginStore(root)))
        val entry = SearchHistory("中文 🎬 \"quoted\"\tline\nnext", 10)
        dao.insert(entry)
        val restored = DesktopSearchHistoryDao(DesktopPluginContext(DesktopPluginStore(root)))
        assertEquals(entry, restored.getAll().value.single())
        restored.clearAll(); assertTrue(restored.getAll().value.isEmpty())
        assertTrue(DesktopSearchHistoryDao(DesktopPluginContext(DesktopPluginStore(root))).getAll().value.isEmpty())
    }

    @Test fun searchPreferencesIsolateGuestAndAccountsAndPersistSourceDefaults(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-search-")
        val prefs = DesktopSearchPreferences(root)
        assertFalse(prefs.privacyMode.value); assertTrue(prefs.suggestionsEnabled.value)
        prefs.record(null, "guest"); prefs.record(1, "account one"); prefs.record(2, "account two")
        assertEquals("guest", prefs.history(null).value.single().keyword)
        assertEquals("account one", prefs.history(1).value.single().keyword); assertEquals("account two", prefs.history(2).value.single().keyword)
        prefs.setSuggestionsEnabled(false)
        val restored = DesktopSearchPreferences(root)
        assertFalse(restored.suggestionsEnabled.value); assertEquals("account one", restored.history(1).value.single().keyword)
        restored.clear(1); assertTrue(restored.history(1).value.isEmpty()); assertEquals("account two", restored.history(2).value.single().keyword)
    }

    @Test fun incognitoSkipsHistoryWritesAndKeepsExistingHistoryAccessible(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-search-")
        val prefs = DesktopSearchPreferences(root)
        prefs.record(1, "saved"); prefs.setPrivacyMode(true)
        prefs.record(1, "private"); prefs.record(null, "private guest")
        assertEquals(listOf("saved"), prefs.history(1).value.map { it.keyword }); assertTrue(prefs.history(null).value.isEmpty())
        val restored = DesktopSearchPreferences(root); assertTrue(restored.privacyMode.value)
        restored.setPrivacyMode(false); restored.record(1, "  recorded  ")
        assertEquals("recorded", restored.history(1).value.first().keyword)
    }

    @Test fun officialDiscoveryParametersUseCurrentCredentialPlatform() {
        val guest = buildSearchRecommendParams(null, "", 123)
        assertEquals("android", guest["mobi_app"])
        assertEquals("123", guest["ts"])
        assertFalse("access_key" in guest)
        val signed = buildSearchRecommendParams("fixture-token", "tv", 124)
        assertEquals("fixture-token", signed["access_key"])
        assertNotNull(signed["sign"])
    }
}
