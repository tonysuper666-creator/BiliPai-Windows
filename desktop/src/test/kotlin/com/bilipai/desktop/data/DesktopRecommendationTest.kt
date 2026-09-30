package com.bilipai.desktop.data

import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.store.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.home.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DesktopRecommendationTest {
    @Test fun homeAnonymizerUsesOriginalExactWebEndpointAndPreservesOtherCookies() {
        val feed = Request.Builder().url("https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd?ps=20").header("Cookie", "fixture=only").build()
        assertSame(feed, stripDesktopAnonymousHomeFeedCookie(feed, enabled = false))
        assertNull(stripDesktopAnonymousHomeFeedCookie(feed, enabled = true).header("Cookie"))
        listOf("https://api.bilibili.com/x/web-interface/wbi/view?bvid=fixture",
            "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd/extra",
            "https://app.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd",
            "https://api.bilibili.com/x/web-interface/popular").forEach { url ->
            val request = Request.Builder().url(url).header("Cookie", "fixture=only").build()
            assertSame(request, stripDesktopAnonymousHomeFeedCookie(request, enabled = true))
            assertEquals("fixture=only", request.header("Cookie"))
        }
    }
    private val webVideo = VideoItem(aid = 1, bvid = "BV-web", title = "web", owner = Owner(mid = 10))
    private val appVideo = VideoItem(aid = 2, bvid = "BV-app", title = "app", owner = Owner(mid = 20))

    @Test fun originalModeDefaultsAndRefreshNormalizationArePreserved() {
        assertEquals(DesktopRecommendationMode.WEB, DesktopRecommendationMode.fromValue(99))
        assertEquals(20, DEFAULT_HOME_REFRESH_COUNT)
        assertEquals(10, normalizeHomeRefreshCount(0)); assertEquals(30, normalizeHomeRefreshCount(999))
        assertEquals("20", discoveryRecommendParams(1)["ps"])
        assertEquals("0", discoveryRecommendParams(1)["fresh_idx"])
    }

    @Test fun mobileModeUsesTvIdentityAndOriginalRefreshPullSemantics() {
        val params = buildDesktopMobileRecommendParams(0, 20, "fixture-token")
        assertEquals("1", params["pull"]); assertEquals("0", buildDesktopMobileRecommendParams(1, 20, "fixture-token")["pull"])
        assertEquals(AppSignUtils.TV_APP_KEY, params["appkey"])
        assertEquals("android", params["mobi_app"]); assertEquals("8130300", params["build"])
        assertEquals("fixture-token", params["access_key"])
        assertNotNull(AppSignUtils.signForTvLogin(params)["sign"])
    }

    @Test fun mergedRequestsRetainDistinctWebAndHdAppParameters() {
        val web = buildDesktopMergedWebRecommendParams(4, 20)
        assertEquals(mapOf("version" to "1", "feed_version" to "V8", "homepage_ver" to "1", "ps" to "20",
            "fresh_idx" to "4", "brush" to "4", "fresh_type" to "4"), web)
        val app = buildDesktopMergedMobileRecommendParams(0)
        assertEquals("android_hd", app["mobi_app"]); assertEquals("pad", app["device"])
        assertEquals("2001100", app["build"]); assertEquals("true", app["pull"])
        assertEquals("false", buildDesktopMergedMobileRecommendParams(1)["pull"])
        assertFalse(app.containsKey("access_key")); assertFalse(app.containsKey("ps"))
        assertEquals("{\"appId\":5,\"platform\":3,\"version\":\"2.0.1\",\"abtest\":\"\"}", app["statistics"])
    }

    @Test fun originalRetrofitMergedEndpointDoesNotDoubleEncodeSignedValues(): Unit = runBlocking {
        val request = AtomicReference<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request.set(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":{"items":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        val params = buildDesktopMergedMobileRecommendParams(0)
        val signed = AppSignUtils.signForAndroidHdLogin(params)
        api.getMobileFeedEncoded(signed.mapValues { (_, value) -> AppSignUtils.percentEncode(value) })
        val url = request.get().url
        assertEquals("app.bilibili.com", url.host); assertEquals("/x/v2/feed/index", url.encodedPath)
        assertEquals(params["statistics"], url.queryParameter("statistics"))
        assertEquals(signed["sign"], url.queryParameter("sign"))
        assertTrue(url.encodedQuery!!.contains("statistics=%7B"), "Original encoded request must contain one JSON escaping layer")
    }

    @Test fun hdHeadersAndCookieStrippingApplyOnlyToExactMergedAppRequest() {
        val hd = "https://app.bilibili.com/x/v2/feed/index?mobi_app=android_hd".toHttpUrl()
        val original = Request.Builder().url(hd).header("Cookie", "fixture=only").header("X-BiliPai-Login-Buvid", "placeholder")
        val decorated = applyDesktopMergedRecommendationHeaders(original, hd, "real-visitor-fixture").build()
        assertEquals("android_hd", decorated.header("app-key")); assertEquals("real-visitor-fixture", decorated.header("buvid"))
        assertNull(decorated.header("X-BiliPai-Login-Buvid")); assertEquals(decorated.header("fp_local"), decorated.header("fp_remote"))
        assertEquals("11111111", decorated.header("session_id")); assertNull(stripDesktopMergedFeedCookie(decorated).header("Cookie"))
        assertTrue(DesktopRepository.resolvePlatformUserAgent(hd, null).contains("BiliDroid/2.0.1"))
        assertEquals("explicit-fixture", DesktopRepository.resolvePlatformUserAgent(hd, "explicit-fixture"))
        assertTrue(DesktopRepository.resolvePlatformUserAgent("https://app.bilibili.com/x/v2/feed/index?mobi_app=android".toHttpUrl(), null).contains("BiliDroid/8.43.0"))
        for (value in listOf("https://app.bilibili.com/x/v2/feed/index?mobi_app=android", "https://app.bilibili.com/x/v2/space?mobi_app=android_hd",
            "https://api.bilibili.com/x/v2/feed/index?mobi_app=android_hd", "https://app.bilibili.com/x/v2/feed/index/extra?mobi_app=android_hd")) {
            val url = value.toHttpUrl(); val normal = applyDesktopMergedRecommendationHeaders(Request.Builder().url(url).header("Cookie", "fixture=only"), url, "visitor").build()
            assertNull(normal.header("app-key")); assertEquals("fixture=only", stripDesktopMergedFeedCookie(normal).header("Cookie"))
        }
    }

    @Test fun mobileFailuresAndEmptyResultsFallbackToOrdinaryWebWithHonestProvenance(): Unit = runBlocking {
        for (fail in listOf(false, true)) {
            val result = resolveDiscoveryRecommendation(DesktopRecommendationMode.MOBILE, web = { merged ->
                assertFalse(merged); listOf(webVideo)
            }, app = { merged -> assertFalse(merged); if (fail) throw BiliApiException(-101, "fixture") else emptyList() })
            assertEquals(listOf(webVideo), result.items); assertEquals(DesktopRecommendationMode.MOBILE, result.requestedMode)
            assertEquals(setOf(DesktopRecommendationMode.WEB), result.actualSources); assertTrue(result.sourceNotice.isNotBlank())
        }
    }

    @Test fun successfulMobileModeDoesNotRequestWeb(): Unit = runBlocking {
        val result = resolveDiscoveryRecommendation(DesktopRecommendationMode.MOBILE, web = { error("Unexpected web request") }, app = { listOf(appVideo) })
        assertEquals(listOf(appVideo), result.items); assertEquals(setOf(DesktopRecommendationMode.MOBILE), result.actualSources)
    }

    @Test fun mergedModeInterleavesAppFirstAndKeepsOneResourceWithOriginalMetadata(): Unit = runBlocking {
        val reason = RecommendationFeedbackReason(id = 1, name = "减少推荐")
        val appDuplicate = webVideo.copy(title = "app precedence", recommendationFeedback = RecommendationFeedbackMetadata(reasons = listOf(reason)))
        val result = resolveDiscoveryRecommendation(DesktopRecommendationMode.MERGED, web = { assertTrue(it); listOf(webVideo) }, app = { assertTrue(it); listOf(appDuplicate, appVideo) })
        assertEquals(listOf(webVideo.bvid, appVideo.bvid), result.items.map { it.bvid })
        assertEquals("app precedence", result.items.first().title)
        assertEquals(reason, result.items.first().recommendationFeedback?.reasons?.single())
        assertEquals(setOf(DesktopRecommendationMode.WEB, DesktopRecommendationMode.MOBILE), result.actualSources)
    }

    @Test fun mergedSingleSourceFallbackIsReportedAndBothFailuresPreserveWebError(): Unit = runBlocking {
        val failure = BiliApiException(412, "fixture")
        val result = resolveDiscoveryRecommendation(DesktopRecommendationMode.MERGED, web = { throw failure }, app = { listOf(appVideo) })
        assertEquals(setOf(DesktopRecommendationMode.MOBILE), result.actualSources); assertTrue(result.sourceNotice.isNotBlank())
        assertSame(failure, assertFailsWith<BiliApiException> { resolveDiscoveryRecommendation(DesktopRecommendationMode.MERGED,
            web = { throw failure }, app = { throw BiliApiException(-403, "fixture") }) })
    }

    @Test fun cancellationNeverTriggersFallbackRequests(): Unit = runBlocking {
        assertFailsWith<CancellationException> { resolveDiscoveryRecommendation(DesktopRecommendationMode.MOBILE,
            web = { error("Unexpected cancellation fallback") }, app = { throw CancellationException("fixture") }) }
    }

    @Test fun originalFeedbackReasonsAndCreatorActionsRetainExactSemantics() {
        val creatorReason = resolveHomeNotInterestedReasons(appVideo).first { it.localAction == RecommendationFeedbackLocalAction.CREATOR }
        val creator = resolveHomeNotInterestedAction(appVideo, creatorReason)
        assertTrue(creator.shouldBlockCreator); assertTrue(creator.shouldSyncCreatorToBilibiliBlockedList); assertEquals(20L, creator.creatorMid)
        val videoOnly = resolveHomeNotInterestedAction(appVideo, RecommendationFeedbackReason(name = "这个内容"))
        assertFalse(videoOnly.shouldBlockCreator)
        val category = resolveHomeNotInterestedAction(appVideo.copy(tname = "游戏"), RecommendationFeedbackReason(name = "分区：游戏"))
        assertTrue("游戏" in category.keywords)
        assertEquals(5 to 11, desktopBlockedRelationArguments(true, BlockedUpRelationSource.PROFILE))
        assertEquals(6 to 15, desktopBlockedRelationArguments(false, BlockedUpRelationSource.COMMENT))
    }

    @Test fun serverFeedbackUsesExactlyOneOriginalReasonFieldAndNeverInventsUnsupportedRequest() {
        val metadata = RecommendationFeedbackMetadata(goto = "av", param = "170001", supportsServerSync = true)
        assertNull(buildRecommendationFeedbackRequest(metadata, RecommendationFeedbackReason(name = "local")))
        for (type in RecommendationFeedbackType.entries) {
            val request = assertNotNull(buildRecommendationFeedbackRequest(metadata, RecommendationFeedbackReason(id = 9, name = "real", type = type)))
            val params = buildRecommendationFeedbackParams(request, "fixture-token", 123)
            assertEquals(1, listOf("reason_id", "feedback_id").count { it in params })
            assertEquals("9", params[if (type == RecommendationFeedbackType.DISLIKE) "reason_id" else "feedback_id"])
            assertEquals(AppSignUtils.TV_APP_KEY, params["appkey"])
        }
    }

    @Test fun localNegativeFeedbackIsPersistedPerAccountAndCreatorBlocksSurviveFeedbackClear(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-feed-")
        val preferences = DesktopDiscoveryPreferences(root)
        preferences.setFeedMode(DesktopRecommendationMode.MERGED); preferences.setRefreshCount(99)
        preferences.record(101, TodayWatchDislikedVideoSnapshot(" BV-app ", "中文 😀", "UP", 20, 123), setOf(" 游戏 "), true)
        assertTrue(preferences.feedback(102).value.dislikedBvids.isEmpty()); assertTrue(preferences.feedback(null).value.dislikedBvids.isEmpty())
        val restored = DesktopDiscoveryPreferences(root)
        assertEquals(DesktopRecommendationMode.MERGED, restored.feedMode.value); assertEquals(30, restored.refreshCount.value)
        assertEquals("中文 😀", restored.feedback(101).value.recentDislikedVideos.single().title)
        assertEquals(setOf("游戏"), restored.feedback(101).value.dislikedKeywords); assertEquals(setOf(20L), restored.blockedCreators(101).value)
        restored.clearFeedback(101)
        assertTrue(restored.feedback(101).value.dislikedBvids.isEmpty()); assertEquals(setOf(20L), restored.blockedCreators(101).value)
        restored.changeBlocked(101, 20, false); assertTrue(restored.blockedCreators(101).value.isEmpty())
    }

    @Test fun originalNegativeFilterSeparatesShortPartitionTermsFromTitleFalsePositives() {
        val title = webVideo.copy(title = "日常记录", tname = "知识")
        val partition = appVideo.copy(title = "无关标题", tname = "日常")
        assertEquals(listOf(title), filterHomeVideosByNotInterestedFeedback(listOf(title, partition), dislikedKeywords = setOf("日常")))
        var snapshot = TodayWatchFeedbackSnapshot()
        repeat(205) { i -> snapshot = snapshot.withDislikedVideoFeedback(TodayWatchDislikedVideoSnapshot("BV-$i", "v", "up", i + 1L, i.toLong()), emptySet(), true) }
        assertEquals(200, snapshot.dislikedBvids.size); assertEquals(120, snapshot.dislikedCreatorMids.size); assertEquals(24, snapshot.recentDislikedVideos.size)
    }

    @Test fun pluginFeedbackClearUpdatesTheSameObservableAccountSnapshot() {
        val preferences = DesktopDiscoveryPreferences(Files.createTempDirectory("bp-feed-"))
        val flow = preferences.feedback(101)
        preferences.record(101, TodayWatchDislikedVideoSnapshot("BV-old", "v", "up", 1, 1), emptySet(), false)
        assertEquals(setOf("BV-old"), flow.value.dislikedBvids)
        TodayWatchFeedbackStore.clear(preferences.recommendationContext(101))
        assertTrue(flow.value.dislikedBvids.isEmpty())
    }

    @Test fun oldAccountActionCannotWriteNewAccountsLocalFeedback(): Unit = runBlocking {
        val sessions = DesktopSessionStore.temporary()
        sessions.saveAccount(mapOf("SESSDATA" to "fixture", "bili_jct" to "fixture"), AccountSummary(102, "fixture", ""))
        val preferences = DesktopDiscoveryPreferences(Files.createTempDirectory("bp-feed-"))
        val discovery = DesktopDiscoveryRepository(DesktopRepository(sessions), preferences)
        assertEquals(-101, assertFailsWith<BiliApiException> {
            discovery.notInterested(webVideo, RecommendationFeedbackReason(name = "这个内容"), expectedAccountMid = 101)
        }.apiCode)
        assertTrue(preferences.feedback(101).value.dislikedBvids.isEmpty()); assertTrue(preferences.feedback(102).value.dislikedBvids.isEmpty())
    }
}
