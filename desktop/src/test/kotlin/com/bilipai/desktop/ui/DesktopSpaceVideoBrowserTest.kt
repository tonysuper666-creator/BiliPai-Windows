package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import kotlin.test.*

class DesktopSpaceVideoBrowserTest {
    @Test fun browserControlsAndEachQueryKeepTheirOwnGridRowsAndScroll() {
        val memory = DesktopBrowseMemory(); val owner = listOf("up-space-video-browser", 1L, 7L, 22L)
        val controls = memory.screen(owner) { DesktopSpaceVideoBrowserState() }
        controls.categoryId = 160; controls.order = VideoSortOrder.OLDEST_PUBDATE; controls.keyword = "查询"; controls.draft = "未提交"
        controls.layout = toggleSpaceContributionVideoLayoutMode(controls.layout)
        val query = DesktopSpaceVideoQuery(controls.order, controls.categoryId, controls.keyword)
        val page = memory.screen(owner + query) { DesktopSpaceVideoPageState() }
        page.feed.acceptBatch(0, CommunityBatch(listOf(SpaceVideoItem(bvid = "BV1")), 2), true) { it.bvid }
        val restored = memory.screen(owner) { DesktopSpaceVideoBrowserState() }
        assertSame(controls, restored); assertEquals(160, restored.categoryId); assertEquals("未提交", restored.draft)
        assertEquals(SpaceContributionVideoLayoutMode.SINGLE_COLUMN, restored.layout)
        val same = memory.screen(owner + query) { DesktopSpaceVideoPageState() }; assertSame(page, same); assertSame(page.grid, same.grid)
        val other = memory.screen(owner + query.copy(categoryId = 1)) { DesktopSpaceVideoPageState() }; assertTrue(other.feed.rows.isEmpty())
        val switched = memory.screen(listOf("up-space-video-browser", 2L, 8L, 22L) + query) { DesktopSpaceVideoPageState() }; assertTrue(switched.feed.rows.isEmpty())
    }

    @Test fun failedRefreshKeepsRowsAndOnlyCurrentRevisionCanReplaceThem() {
        val page = DesktopSpaceVideoPageState(); val original = SpaceVideoItem(bvid = "BVold")
        page.feed.acceptBatch(0, CommunityBatch(listOf(original), 2), true) { it.bvid }
        page.feed.invalidate(); val revision = page.feed.reloadRevision
        assertSame(original, page.feed.rows.single()); assertFalse(page.feed.initialized)
        page.feed.acceptFailure(revision, IllegalStateException("fixture"), null, true)
        assertSame(original, page.feed.rows.single()); assertFalse(page.feed.acceptBatch(0, CommunityBatch(listOf(SpaceVideoItem(bvid = "BVstale")), null), true) { it.bvid })
        assertTrue(page.feed.acceptBatch(revision, CommunityBatch(listOf(SpaceVideoItem(bvid = "BVnew")), null), true) { it.bvid })
        assertEquals("BVnew", page.feed.rows.single().bvid); assertNull(page.feed.failure)
    }

    @Test fun originalLocatePolicyStopsAtFailureAndDoesNotSkipToAnotherVideo() {
        val rows = listOf(SpaceVideoItem(bvid = "BV1"), SpaceVideoItem(bvid = "BV2"))
        assertEquals(SpaceLocateTargetPageAction.Found(1), resolveSpaceLocateTargetPageAction("BV2", rows, false, true))
        assertEquals(SpaceLocateTargetPageAction.LoadFailed, resolveSpaceLocateTargetPageAction("BV3", rows, false, true, true))
        assertEquals(SpaceLocateTargetPageAction.Wait, resolveSpaceLocateTargetPageAction("BV3", rows, true, true))
        assertEquals(SpaceLocateTargetPageAction.LoadMore, resolveSpaceLocateTargetPageAction("BV3", rows, false, true))
        assertEquals(SpaceLocateTargetPageAction.Missing, resolveSpaceLocateTargetPageAction("BV3", rows, false, false))
    }
}
