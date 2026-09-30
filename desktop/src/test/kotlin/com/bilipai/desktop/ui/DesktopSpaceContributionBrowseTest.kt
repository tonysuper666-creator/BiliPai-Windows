package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import kotlin.test.*

class DesktopSpaceContributionBrowseTest {
    @Test fun oldestSortSearchAndSubpageRowOwnersSurviveNavigation() {
        val memory = DesktopBrowseMemory()
        val key = listOf("up-space-contributions", 1, 7, 22)
        val state = memory.screen(key) { DesktopSpaceContributionState() }
        state.order = VideoSortOrder.OLDEST_PUBDATE; state.keyword = "保留查询"; state.draft = "下一次输入"
        state.audioRows = listOf(SpaceAudioItem(id = 8))
        state.followedRows = listOf(FollowBangumiItem(seasonId = 9))
        state.interactionRows = mapOf(true to listOf(VideoItem(id = 1)), false to listOf(VideoItem(id = 2)))
        val restored = memory.screen(key) { DesktopSpaceContributionState() }
        assertSame(state, restored); assertSame(state.sortScroll, restored.sortScroll)
        assertEquals(VideoSortOrder.OLDEST_PUBDATE, restored.order); assertEquals("保留查询", restored.keyword)
        assertEquals("下一次输入", restored.draft); assertEquals(8, restored.audioRows.single().id)
        assertEquals(9, restored.followedRows.single().seasonId)
        assertEquals(1, restored.interactionRows[true]?.single()?.id); assertEquals(2, restored.interactionRows[false]?.single()?.id)
    }

    @Test fun accountAndUpChangesNeverReuseContributionMergeHistory() {
        val memory = DesktopBrowseMemory()
        val first = memory.screen(listOf("up-space-contributions", 1, 7, 22)) { DesktopSpaceContributionState() }
        first.audioRows = listOf(SpaceAudioItem(id = 8)); first.order = VideoSortOrder.CLICK
        val switched = memory.screen(listOf("up-space-contributions", 2, 8, 22)) { DesktopSpaceContributionState() }
        val other = memory.screen(listOf("up-space-contributions", 1, 7, 23)) { DesktopSpaceContributionState() }
        listOf(switched, other).forEach { assertTrue(it.audioRows.isEmpty()); assertEquals(VideoSortOrder.PUBDATE, it.order) }
    }
}
