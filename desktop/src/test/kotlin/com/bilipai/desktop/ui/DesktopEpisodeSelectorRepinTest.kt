package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.Page
import com.android.purebilibili.data.model.response.UgcEpisode
import com.android.purebilibili.data.model.response.UgcEpisodeArc
import com.android.purebilibili.feature.video.ui.components.PagesSelectorPresentation
import com.android.purebilibili.feature.video.ui.components.filterPageIndicesForSelector
import com.android.purebilibili.feature.video.ui.components.resolveCollectionEpisodeAbsoluteTimeText
import com.android.purebilibili.feature.video.ui.components.resolvePageChapterGroupKey
import com.android.purebilibili.feature.video.ui.components.resolvePageSelectorGroups
import com.android.purebilibili.feature.video.ui.components.resolvePagesSelectorBottomContentPaddingDp
import com.android.purebilibili.feature.video.ui.components.resolvePagesSelectorLayoutPolicy
import com.android.purebilibili.feature.video.ui.components.shouldShowPagesExpandAction
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Roadmap lst-episode-selector-collection. What this class covers, exactly:
 *
 * - NEW behaviour (four cases, they fail if the slice is reverted): Page.firstFrame, the per-part thumbnail that the whole
 *   v0.4.6 VideoDetailResponse adds (server key first_frame, blank default, also inside collection episode pages).
 * - REGRESSION pins (seven cases) on pure policies that this slice does not change (PagesSelectorLayoutPolicy.kt is a
 *   vendored direct file with the same blob at v0.4.6; resolveCollectionEpisodeAbsoluteTimeText is unchanged from v0.2.5).
 *   The new part selector and collection sheet still call them, so they pass with the slice reverted too.
 *
 * NOT covered here: the composable behaviour (the PageCoverList initial position and ascending or descending order, the
 * enabled flags of EpisodeListNavigationActions, introCanExpand in the collection sheet). It has no pure function to call
 * and is covered only by the Python source checks (test_v046_episode_selector_source.py) and by the emitted original
 * PagePreviewNestedScrollTest, which covers the nested scroll boundary of the part row.
 *
 * Pure functions and data classes only: no composition, window, GPU or network is created. */
class DesktopEpisodeSelectorRepinTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val parts = listOf(
        Page(cid = 1, page = 1, part = "1.1 开篇"),
        Page(cid = 2, page = 2, part = "1.2 环境"),
        Page(cid = 3, page = 3, part = "第2章 进阶"),
        Page(cid = 4, page = 4, part = "彩蛋"),
        Page(cid = 5, page = 10, part = "10. 终章"),
    )

    @Test
    fun pageFirstFrame_isReadFromTheServerKey() {
        val page = json.decodeFromString(
            Page.serializer(),
            """{"cid":9,"page":2,"from":"vupload","part":"第二集","duration":61,"first_frame":"http://i0.hdslb.com/bfs/storyff/a.jpg","unknown":1}""",
        )
        assertEquals(9L, page.cid)
        assertEquals(2, page.page)
        assertEquals("第二集", page.part)
        assertEquals(61L, page.duration)
        assertEquals("http://i0.hdslb.com/bfs/storyff/a.jpg", page.firstFrame)
    }

    @Test
    fun pageWithoutFirstFrame_staysBlankSoTheListFallsBackToTheVideoCover() {
        val page = json.decodeFromString(Page.serializer(), """{"cid":9,"page":1,"part":"P1","duration":10}""")
        assertEquals("", page.firstFrame)
        assertEquals("http://cover.example/c.jpg", page.firstFrame.ifBlank { "http://cover.example/c.jpg" })
        assertEquals("", Page().firstFrame)
    }

    @Test
    fun pageFirstFrame_isWrittenUnderTheServerKey() {
        val encoded = Json { encodeDefaults = true }.encodeToString(Page.serializer(), Page(cid = 1, page = 1, firstFrame = "x"))
        assertTrue(encoded.contains("\"first_frame\":\"x\""), encoded)
        assertFalse(encoded.contains("firstFrame"), encoded)
    }

    @Test
    fun collectionEpisodePages_carryTheirOwnFirstFrame() {
        val episode = json.decodeFromString(
            UgcEpisode.serializer(),
            """{"id":1,"aid":2,"bvid":"BV1xx","cid":3,"title":"t","pages":[{"cid":3,"page":1,"first_frame":"f1"},{"cid":4,"page":2}]}""",
        )
        assertEquals(listOf("f1", ""), episode.pages.map { it.firstFrame })
    }

    @Test
    fun chapterGroups_followNumericAndChapterPrefixesAndSortNumbersBeforeOther() {
        assertEquals(listOf("1", "1", "2", "other", "10"), parts.map(::resolvePageChapterGroupKey))
        val groups = resolvePageSelectorGroups(parts)
        assertEquals(listOf("1", "2", "10", "other"), groups.map { it.key })
        assertEquals(listOf("1章", "2章", "10章", "其他"), groups.map { it.label })
        assertEquals(listOf(2, 1, 1, 1), groups.map { it.count })
        assertEquals(emptyList(), resolvePageSelectorGroups(emptyList()))
    }

    @Test
    fun partFilter_keepsOriginalIndicesSoSelectingARowPlaysTheRightPart() {
        assertEquals(listOf(0, 1, 2, 3, 4), filterPageIndicesForSelector(parts, selectedGroupKey = null, query = ""))
        assertEquals(listOf(0, 1, 2, 3, 4), filterPageIndicesForSelector(parts, selectedGroupKey = " ", query = "  "))
        assertEquals(listOf(0, 1), filterPageIndicesForSelector(parts, selectedGroupKey = "1", query = ""))
        assertEquals(listOf(3), filterPageIndicesForSelector(parts, selectedGroupKey = "other", query = ""))
        assertEquals(listOf(1), filterPageIndicesForSelector(parts, selectedGroupKey = "1", query = "环境"))
        assertEquals(emptyList(), filterPageIndicesForSelector(parts, selectedGroupKey = "2", query = "环境"))
    }

    @Test
    fun partFilter_matchesThePartNumberAsASubstringIgnoringCase() {
        assertEquals(listOf(2), filterPageIndicesForSelector(parts, selectedGroupKey = null, query = "P3"))
        // "p1" is also a prefix of "p10": the search is a substring match, not an exact part number match.
        assertEquals(listOf(0, 4), filterPageIndicesForSelector(parts, selectedGroupKey = null, query = " p1 "))
    }

    @Test
    fun bottomPadding_addsTheNavigationInsetToTheBase() {
        assertEquals(24, resolvePagesSelectorBottomContentPaddingDp(navigationBarBottomDp = 0))
        assertEquals(34, resolvePagesSelectorBottomContentPaddingDp(navigationBarBottomDp = 10))
        assertEquals(24, resolvePagesSelectorBottomContentPaddingDp(navigationBarBottomDp = -8))
    }

    @Test
    fun layoutPolicy_aLargeLandscapeWindowUsesAFiveColumnGridCappedAt520Dp() {
        val policy = resolvePagesSelectorLayoutPolicy(widthDp = 3413, isLandscape = true, pagesCount = 40, forceGridMode = false)
        assertEquals(PagesSelectorPresentation.InlineGrid, policy.presentation)
        assertEquals(5, policy.gridColumns)
        assertEquals(520, policy.maxGridHeightDp)
        assertFalse(shouldShowPagesExpandAction(policy, pagesCount = 40))
    }

    @Test
    fun layoutPolicy_aNarrowPortraitWindowKeepsTheHorizontalRowAndOffersExpansionForLongLists() {
        val long = resolvePagesSelectorLayoutPolicy(widthDp = 400, isLandscape = false, pagesCount = 12, forceGridMode = false)
        assertEquals(PagesSelectorPresentation.HorizontalPreview, long.presentation)
        assertTrue(shouldShowPagesExpandAction(long, pagesCount = 12))
        val short = resolvePagesSelectorLayoutPolicy(widthDp = 400, isLandscape = false, pagesCount = 5, forceGridMode = false)
        assertEquals(PagesSelectorPresentation.InlineGrid, short.presentation)
        assertFalse(shouldShowPagesExpandAction(short, pagesCount = 5))
        val forced = resolvePagesSelectorLayoutPolicy(widthDp = 400, isLandscape = false, pagesCount = 12, forceGridMode = true)
        assertEquals(PagesSelectorPresentation.InlineGrid, forced.presentation)
    }

    @Test
    fun collectionEpisodeTime_isBlankWithoutAPublishDateAndAbsoluteWithOne() {
        assertEquals("", resolveCollectionEpisodeAbsoluteTimeText(UgcEpisode(id = 1)))
        assertEquals("", resolveCollectionEpisodeAbsoluteTimeText(UgcEpisode(id = 1, arc = UgcEpisodeArc(pubdate = 0L))))
        val text = resolveCollectionEpisodeAbsoluteTimeText(UgcEpisode(id = 1, arc = UgcEpisodeArc(pubdate = 1_790_000_000L)))
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(text), text)
    }
}
