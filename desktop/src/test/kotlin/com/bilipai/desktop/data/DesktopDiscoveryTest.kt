package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.resolveRegionRankingRid
import com.android.purebilibili.data.repository.shouldFallbackRegionLatestToRanking
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.partition.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.plugin.BiliPaiFeedFilterConfig
import kotlin.test.*

class DesktopDiscoveryTest {
    @Test fun popularUsesServerNoMoreInsteadOfAssumingFullPages() {
        val first = discoveryPopularPage(PopularData(listOf(PopularItem(aid = 170001, bvid = "BV17x411w7KC", cid = 2, title = "real", owner = Owner(mid = 3, name = "up"))), false), 1)
        assertEquals(2, first.nextPage); assertEquals(2L, first.cards.single().preferredCid); assertEquals(3L, first.cards.single().authorMid)
        assertNull(discoveryPopularPage(PopularData(listOf(PopularItem(bvid = "BV17x411w7KC")), true), 1).nextPage)
        assertNull(discoveryPopularPage(PopularData(emptyList(), false), 1).nextPage)
    }

    @Test fun recommendationRequestRetainsOriginalWebIdxAndSessionTimestampFields() {
        val before = System.currentTimeMillis()
        val first = discoveryRecommendParams(1, 30)
        assertTrue(first.getValue("feed_version").toLong() in before..System.currentTimeMillis())
        assertEquals(mapOf("ps" to "30", "fresh_type" to "3", "fresh_idx" to "0", "y_num" to "0"), first - "feed_version")
        assertEquals("4", discoveryRecommendParams(5, 30)["fresh_idx"])
        assertFailsWith<IllegalArgumentException> { discoveryRecommendParams(0) }
        assertFalse(first.containsKey("buvid")); assertFalse(first.containsKey("web_location"))
    }

    @Test fun originalPartitionsAndRegionFallbackPreserveSpecialAndLegacyRoutes() {
        assertEquals(1, resolvePartitionBangumiType(13)); assertEquals(4, resolvePartitionBangumiType(167))
        assertEquals(2, resolvePartitionBangumiType(23)); assertEquals(5, resolvePartitionBangumiType(11)); assertEquals(3, resolvePartitionBangumiType(177))
        assertNull(resolvePartitionBangumiType(202)); assertEquals(1009, resolveRegionRankingRid(202)); assertEquals(1008, resolveRegionRankingRid(4))
        assertTrue(allPartitions.any { it.id == 202 && it.name == "资讯" })
        assertTrue(shouldFallbackRegionLatestToRanking(202, 1, 0, 0))
        assertTrue(shouldFallbackRegionLatestToRanking(4, 1, 1, -400))
        assertFalse(shouldFallbackRegionLatestToRanking(202, 2, 0, 0))
        assertFalse(shouldFallbackRegionLatestToRanking(17, 1, 0, 0))
    }

    @Test fun originalRefreshAndWeeklyPoliciesAdvanceOnlyPagedFeeds() {
        assertEquals(4, resolvePagedFeedPageToFetch(false, true, 3, true))
        assertEquals(1, resolvePagedFeedPageToFetch(false, true, 3, false))
        assertEquals(4, resolveRecommendFeedRequestIndex(false, true, 3))
        assertEquals(0, resolveRecommendFeedRequestIndex(false, false, 3))
        assertTrue(supportsPopularLoadMore(PopularSubCategory.COMPREHENSIVE))
        assertFalse(supportsPopularLoadMore(PopularSubCategory.RANKING))
        assertEquals(42, resolveWeeklyNumberForRequest(listOf(3, 42, 5))); assertEquals(1, resolveWeeklyNumberForRequest(emptyList()))
    }

    @Test fun ugcQueueKeepsSectionsCidAndPartMetadataWhileReusingOriginalSort() {
        val episodes = listOf(
            UgcEpisode(id = 1, aid = 170001, bvid = "BV17x411w7KC", cid = 10, title = "first", pages = listOf(Page(cid = 10, part = "part1"), Page(cid = 11, part = "part2"))),
            UgcEpisode(id = 2, aid = 170002, bvid = "BV17x411w7KX", cid = 20, title = "second"))
        val season = UgcSeason(id = 99, mid = 3, title = "season", sections = listOf(UgcSection(id = 1, title = "section", episodes = episodes)))
        val details = VideoDetails("BV17x411w7KX", 170002, "second", "", "", "up", 0, 0, listOf(VideoPart(20, "part", 30)), authorMid = 3, raw = ViewInfo(ugc_season = season))
        val ascending = assertNotNull(desktopUgcCollection(details))
        assertEquals(listOf(10L, 20L), ascending.queue.map { it.preferredCid })
        assertEquals(2, ascending.sections.single().episodes.first().pages.size)
        assertEquals(listOf(20L, 10L), desktopUgcCollection(details, CollectionSortMode.DESCENDING)?.queue?.map { it.preferredCid })
        assertEquals(listOf(20L, 10L), desktopUgcCollection(details, CollectionSortMode.RECENT, 20)?.queue?.map { it.preferredCid })
        assertEquals(99L, resolveCollectionSubscriptionId(season))
    }

    @Test fun ugcMissingBvResolvesRealAidAndFirstPartInsteadOfLosingEpisode() {
        val episode = UgcEpisode(id = 1, aid = 170001, title = "first", pages = listOf(Page(cid = 33, part = "part")))
        val season = UgcSeason(id = 1, mid = 8, sections = listOf(UgcSection(episodes = listOf(episode))))
        val details = VideoDetails("BV17x411w7KC", 170001, "first", "", "", "up", 0, 0, emptyList(), raw = ViewInfo(ugc_season = season))
        val collection = assertNotNull(desktopUgcCollection(details))
        assertEquals("BV17x411w7KC", collection.queue.single().bvid)
        assertEquals(33L, collection.queue.single().preferredCid); assertEquals(8L, collection.queue.single().authorMid)
        assertEquals("BV17x411w7KC", discoveryEpisodeBvid(episode))
        assertNull(desktopUgcCollection(details.copy(raw = null)))
    }

    @Test fun originalFilterPreservesWhitelistFollowedAndPerFeedSwitches() {
        val short = VideoItem(bvid = "BV1", title = "blocked title", duration = 3, owner = Owner(mid = 1), stat = Stat(view = 20, like = 1), tname = "游戏")
        val whitelist = short.copy(bvid = "BV2", owner = Owner(mid = 2))
        val followed = short.copy(bvid = "BV3", owner = Owner(mid = 3), isFollowed = true)
        val filters = DesktopDiscoveryFilters(true, BiliPaiFeedFilterConfig(minDurationForRcmd = 30, minPlayForRcmd = 100,
            banWordForRecommend = "blocked", whitelistMids = mapOf(2L to "allowed"), recommendBlockedMids = mapOf(1L to "blocked")))
        assertEquals(listOf("BV2", "BV3"), filterDiscoveryItems(listOf(short, whitelist, followed), filters, DiscoverySection.RECOMMEND).map { it.bvid })
        assertEquals(3, filterDiscoveryItems(listOf(short, whitelist, followed), filters, DiscoverySection.POPULAR).size)
        assertEquals(listOf("BV2"), filterDiscoveryItems(listOf(short, whitelist, followed), filters.copy(config = filters.config.copy(applyToRankVideos = true)), DiscoverySection.RANKING).map { it.bvid })
        assertEquals(3, filterDiscoveryItems(listOf(short, whitelist, followed), filters.copy(enabled = false), DiscoverySection.RECOMMEND).size)
    }

    @Test fun originalKeywordParserAndRegexUseCaseInsensitiveGroupedRules() {
        val filters = DesktopDiscoveryFilters(true, BiliPaiFeedFilterConfig(banWordForRecommend = "alpha|beta\ngamma", banWordForZone = "GAME"))
        val rows = listOf(VideoItem(bvid = "BV1", title = "ALPHA story"), VideoItem(bvid = "BV2", title = "safe", tname = "game"), VideoItem(bvid = "BV3", title = "safe", tname = "music"))
        assertEquals(listOf("BV3"), filterDiscoveryItems(rows, filters, DiscoverySection.RECOMMEND).map { it.bvid })
    }

    @Test fun invalidFilterIsRejectedBeforeUpdatingSharedPluginStore() {
        val valid = DesktopDiscoveryFilters(true, BiliPaiFeedFilterConfig(banWordForRecommend = "A|B\nC"))
        validateDiscoveryFilters(valid)
        assertFails { validateDiscoveryFilters(valid.copy(config = valid.config.copy(banWordForRecommend = "["))) }
        assertFailsWith<IllegalArgumentException> { validateDiscoveryFilters(valid.copy(config = valid.config.copy(minLikeRatioForRecommend = 101))) }
    }
}
