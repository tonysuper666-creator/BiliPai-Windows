package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.android.purebilibili.data.model.response.VideoItem
import kotlin.test.*

class DesktopBrowseMemoryTest {
    @Test fun revisitingRecommendationSourceRestoresOriginalPageCursorFeedbackAndFailure() {
        val memory = DesktopBrowseMemory()
        val key = listOf("discovery", "recommend", "web", 20)
        val page = memory.feeds.page<DiscoveryPage, Int>(key)
        val original = DiscoveryPage(listOf(VideoItem(bvid = "BV-original", title = "original")), 3,
            requestedMode = DesktopRecommendationMode.WEB, actualSources = setOf(DesktopRecommendationMode.WEB), requestPage = 2)
        page.rows = listOf(original); page.next = 3; page.initialized = true
        page.statusMessage = "本机已保存反馈"; page.failure = BiliApiException(412, "fixture")
        page.failedCursor = 3; page.failedReplace = false
        memory.feeds.page<DiscoveryPage, Int>(listOf("discovery", "recommend", "merged", 20)).rows = emptyList()
        val restored = memory.feeds.page<DiscoveryPage, Int>(key)
        assertSame(page, restored); assertSame(original, restored.rows.single()); assertEquals(3, restored.next)
        assertEquals("本机已保存反馈", restored.statusMessage); assertEquals(3, restored.failedCursor); assertFalse(restored.failedReplace)
        assertEquals(setOf(DesktopRecommendationMode.WEB), restored.rows.single().actualSources)
    }

    @Test fun accountMemoryDoesNotShareSearchPagesOrScreenState() {
        val first = DesktopBrowseMemory(); val second = DesktopBrowseMemory()
        first.feeds.page<String, Int>("search").rows = listOf("account one")
        val filter = first.screen("filters") { DesktopSearchFilters(videoTid = 167) }
        assertSame(filter, first.screen("filters") { DesktopSearchFilters() })
        assertTrue(second.feeds.page<String, Int>("search").rows.isEmpty())
        assertEquals(0, second.screen("filters") { DesktopSearchFilters() }.videoTid)
    }
}
