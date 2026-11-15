package com.android.purebilibili.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/** The compiled search page submit calls this guard; see the search-pages producer. */
class DesktopSearchSubmitCandidateTest {
    private val searchPageSubmit: (String) -> SearchSubmitAction = ::resolveDesktopSearchSubmitAction

    @Test
    fun `empty short links search the whole submitted text`() {
        listOf(
            "https://b23.tv/",
            "http://b23.tv/",
            "b23.tv/",
            "https://b23.tv/，",
            "看看 https://b23.tv/ 这个",
        ).forEach { keyword ->
            assertEquals(SearchSubmitAction.OpenSearch(keyword), searchPageSubmit("  $keyword "), keyword)
        }
    }

    @Test
    fun `cut-short short links search the whole submitted text`() {
        listOf(
            "b23.tv/abc-123",
            "https://b23.tv/abc-123",
            "b23.tv/abc_123",
            "https://b23.tv/abc/def",
            "b23.tv/abc.html",
            "【视频标题】 https://b23.tv/abc-123 复制后打开哔哩哔哩",
            "先看 https://b23.tv/abc-123 再看 https://b23.tv/AbC123",
        ).forEach { keyword ->
            assertEquals(SearchSubmitAction.OpenSearch(keyword), searchPageSubmit("  $keyword "), keyword)
        }
    }

    @Test
    fun `complete short links still open the link wherever they sit in the text`() {
        mapOf(
            "b23.tv/abc123" to "https://b23.tv/abc123",
            "https://b23.tv/abc123" to "https://b23.tv/abc123",
            "【BV1xx411c7mD 视频标题】 https://b23.tv/1RLfKkP 复制后打开哔哩哔哩" to "https://b23.tv/1RLfKkP",
            "【BV1xx411c7mD 视频标题】 b23.tv/1RLfKkP 复制后打开哔哩哔哩" to "https://b23.tv/1RLfKkP",
            "看这个https://b23.tv/AbC123哈哈" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123，快看" to "https://b23.tv/AbC123",
            "快看：https://b23.tv/AbC123。" to "https://b23.tv/AbC123",
            "(https://b23.tv/AbC123)" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123." to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123!" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123?share_source=copy_link" to "https://b23.tv/AbC123?share_source=copy_link",
        ).forEach { (keyword, url) ->
            assertEquals(SearchSubmitAction.OpenLink(url), searchPageSubmit(keyword), keyword)
        }
    }

    @Test
    fun `every action other than a short link is the original policy result`() {
        listOf(
            "BV1xx411c7mD",
            "https://www.bilibili.com/video/BV1xx411c7mD",
            "【BV1Q541167Qg 视频标题】 https://www.bilibili.com/video/BV1xx411c7mD/?share_source=copy_link",
            "  猫和老鼠  ",
            "https://search.bilibili.com/all?keyword=%E7%8C%AB",
            "https://notb23.tv/abc123",
            "https://example.com/b23.tv/abc",
            "",
            "   ",
        ).forEach { keyword ->
            val original = resolveSearchSubmitAction(keyword)
            assert(original !is SearchSubmitAction.OpenLink) { keyword }
            assertEquals(original, searchPageSubmit(keyword), keyword)
        }
    }
}
