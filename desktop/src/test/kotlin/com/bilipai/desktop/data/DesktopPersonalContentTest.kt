package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DesktopPersonalContentTest {
    @Test
    fun favoriteMappingAndUnsupportedResources() {
        val result = personalFavoritePage(FavoriteResourceData(info = FavoriteInfo(media_count = 3), has_more = true,
            medias = listOf(FavoriteData(id = 42, bv_id = "BV1test000001", title = "saved", cover = "//image",
                upper = Upper(name = "creator"), cnt_info = CntInfo(play = 123), ugc = FavoriteUgc(first_cid = 8)),
                FavoriteData(type = 21, id = 100), FavoriteData(id = 55))))
        assertEquals(1, result.items.size)
        assertEquals("BV1test000001", result.items.single().bvid)
        assertEquals("creator", result.items.single().author)
        assertEquals(123L, result.items.single().playCount)
        assertEquals(8L, result.items.single().preferredCid)
        assertEquals("https://image", result.items.single().cover)
        assertTrue(result.hasMore)
        assertEquals(3, result.totalCount)
    }

    @Test
    fun filteredFavoritePageRetainsServerPagination() {
        assertTrue(personalFavoritePage(FavoriteResourceData(has_more = true,
            medias = listOf(FavoriteData(type = 21, id = 1)))).hasMore)
        assertFalse(personalFavoritePage(FavoriteResourceData(has_more = true, medias = emptyList())).hasMore)
    }

    @Test
    fun historyCursorAndResumeMetadata() {
        val result = personalHistoryPage(HistoryListData(list = listOf(
            HistoryData(title = "history", cover = "//cover", progress = 61, view_at = 1000,
                history = HistoryPage(bvid = "BV1test000001", cid = 7, oid = 4, business = "archive", page = 3)),
            HistoryData(title = "live", history = HistoryPage(oid = 9, business = "live"))),
            cursor = HistoryCursor(max = 4, view_at = 1000, business = "archive")), null)
        assertEquals(1, result.items.size)
        assertEquals(61, result.items.single().progressSeconds)
        assertEquals(2, result.items.single().pageIndex)
        assertEquals(7L, result.items.single().preferredCid)
        assertEquals(1000L, result.items.single().viewedAt)
        assertEquals(CloudHistoryCursor(4, 1000, "archive"), result.nextCursor)
    }

    @Test
    fun terminalAndRepeatedHistoryCursorStop() {
        val history = HistoryData(history = HistoryPage(bvid = "BV1test000001", business = "archive"))
        assertNull(personalHistoryPage(HistoryListData(listOf(history), HistoryCursor(max = 0)), null).nextCursor)
        val previous = CloudHistoryCursor(4, 1000, "archive")
        assertNull(personalHistoryPage(HistoryListData(listOf(history), HistoryCursor(4, 1000, "archive")), previous).nextCursor)
        assertNull(personalHistoryPage(HistoryListData(emptyList(), HistoryCursor(max = 4)), null).nextCursor)
    }

    @Test
    fun watchLaterMatchesUpstreamSignedParametersAndPagination() {
        assertEquals(mapOf("pn" to "2", "ps" to "20", "viewed" to "0", "key" to "", "asc" to "false",
            "need_split" to "true", "web_location" to "333.881"), personalWatchLaterParams(2))
        val data = WatchLaterData(count = 41, list = listOf(WatchLaterItem(bvid = "BV1test000001", progress = 32)))
        assertTrue(personalWatchLaterPage(data, 2).hasMore)
        assertFalse(personalWatchLaterPage(data, 3).hasMore)
        assertEquals(32, personalWatchLaterPage(data, 1).items.single().progressSeconds)
        assertFalse(personalWatchLaterPage(WatchLaterData(count = 99, list = emptyList()), 1).hasMore)
    }

    @Test
    fun watchLaterFiltersUnsupportedNavigationAndKeepsNextPage() {
        val result = personalWatchLaterPage(WatchLaterData(60, listOf(
            WatchLaterItem(bvid = "BV1test000001", isPgc = true), WatchLaterItem(bvid = "BV1test000002", isPugv = true))), 1)
        assertTrue(result.items.isEmpty())
        assertTrue(result.hasMore)
    }

    @Test
    fun followingArchiveMappingKeepsServerOffsetAndMetadata() {
        val dynamic = DynamicItem(modules = DynamicModules(module_author = DynamicAuthorModule(name = "creator", pub_ts = 1200),
            module_dynamic = DynamicContentModule(major = DynamicMajor(archive = ArchiveMajor(bvid = "BV1test000001",
                title = "new", cover = "//cover", duration_text = "1:02:03", stat = ArchiveStat(play = "1.2万"))))))
        val result = personalFollowingPage(DynamicFeedData(items = listOf(dynamic), offset = "next", has_more = true,
            update_baseline = "baseline"), "")
        assertEquals(3723, result.items.single().duration)
        assertEquals(12000L, result.items.single().playCount)
        assertEquals(1200L, result.items.single().publishedAt)
        assertEquals("next", result.nextOffset)
        assertEquals("baseline", result.updateBaseline)
    }

    @Test
    fun filteredDynamicPageKeepsAdvancingOffsetAndStopsLoops() {
        assertNotNull(personalFollowingPage(DynamicFeedData(offset = "next", has_more = true), "").nextOffset)
        assertNull(personalFollowingPage(DynamicFeedData(offset = "next", has_more = true), "next").nextOffset)
        assertNull(personalFollowingPage(DynamicFeedData(offset = "", has_more = true), "").nextOffset)
        assertNull(personalFollowingPage(DynamicFeedData(offset = "next", has_more = false), "").nextOffset)
    }

    @Test
    fun localizedStatisticsAndDurationAreBounded() {
        assertEquals(340000000L, personalCountText("3.4亿"))
        assertEquals(1234L, personalCountText("1,234"))
        assertEquals(0L, personalCountText("未公开"))
        assertEquals(0L, personalCountText("NaN"))
        assertEquals(0, personalDurationText("1:99"))
        assertEquals(0, personalDurationText("1:2:3:4"))
        assertEquals(123, personalDurationText("2:03"))
    }

    @Test
    fun anonymousPersonalRequestsStopBeforeNetworking() = runBlocking {
        val path = Files.createTempDirectory("bp-personal-").resolve("session.json")
        val repository = DesktopRepository(DesktopSessionStore(path))
        val failures = listOf(
            assertFailsWith<BiliApiException> { repository.cloudFavoriteFolders() },
            assertFailsWith<BiliApiException> { repository.cloudFavoriteVideos(1) },
            assertFailsWith<BiliApiException> { repository.cloudHistory() },
            assertFailsWith<BiliApiException> { repository.watchLater() },
            assertFailsWith<BiliApiException> { repository.followingVideos() })
        assertTrue(failures.all { it.apiCode == -101 })
        assertFalse(Files.exists(path))
    }
}
