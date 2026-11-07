package com.android.purebilibili.navigation

import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.feature.home.components.BottomNavItem
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopOriginalBottomSearchSubmitBoundaryTest {
    private data class Calls(
        val searches: List<String> = emptyList(),
        val links: List<String> = emptyList(),
        val targets: List<BilibiliNavigationTarget> = emptyList(),
        val scoped: List<String> = emptyList(),
    )

    private fun submit(
        keyword: String,
        owned: Boolean = true,
        item: BottomNavItem = BottomNavItem.HOME,
        listScoped: Boolean = false,
    ): Calls {
        val searches = mutableListOf<String>()
        val links = mutableListOf<String>()
        val targets = mutableListOf<BilibiliNavigationTarget>()
        val history = Channel<String>(Channel.UNLIMITED)
        submitDesktopOriginalBottomBarSearchKeyword(
            keyword = keyword,
            bottomBarSearchEnabled = true,
            listScopedSearchEnabled = listScoped,
            isMainHost = true,
            currentBottomNavItem = item,
            historyListScopedSearchChannel = history,
            favoriteListScopedSearchChannel = null,
            watchLaterListScopedSearchChannel = null,
            onOpenSearch = { searches += it },
            onOpenNativeTarget = { targets += it },
            onOpenLink = { links += it },
            isOwned = { owned },
        )
        history.close()
        val scoped = generateSequence { history.tryReceive().getOrNull() }.toList()
        return Calls(searches, links, targets, scoped)
    }

    @Test
    fun `empty short links search the whole submitted text`() {
        listOf(
            "https://b23.tv/",
            "b23.tv/",
            "https://b23.tv/，",
            "看看 https://b23.tv/ 这个",
        ).forEach { keyword ->
            assertEquals(Calls(searches = listOf(keyword)), submit("  $keyword "), keyword)
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
        ).forEach { keyword ->
            assertEquals(Calls(searches = listOf(keyword)), submit("  $keyword "), keyword)
        }
    }

    @Test
    fun `complete short links still open the link wherever they sit in the text`() {
        mapOf(
            "b23.tv/abc123" to "https://b23.tv/abc123",
            "https://b23.tv/abc123" to "https://b23.tv/abc123",
            "HTTPS://B23.TV/AbC123" to "HTTPS://B23.TV/AbC123",
            "【BV1xx411c7mD 视频标题】 https://b23.tv/1RLfKkP 复制后打开哔哩哔哩" to "https://b23.tv/1RLfKkP",
            "看这个https://b23.tv/AbC123哈哈" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123，快看" to "https://b23.tv/AbC123",
            "快看：https://b23.tv/AbC123。" to "https://b23.tv/AbC123",
            "(https://b23.tv/AbC123)" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123." to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123!" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123?share_source=copy_link" to "https://b23.tv/AbC123?share_source=copy_link",
        ).forEach { (keyword, url) ->
            assertEquals(Calls(links = listOf(url)), submit(keyword), keyword)
        }
    }

    @Test
    fun `other submits keep their original intent`() {
        val video = BilibiliNavigationTarget.Video("BV1xx411c7mD")
        assertEquals(Calls(targets = listOf(video)), submit("BV1xx411c7mD"))
        assertEquals(Calls(targets = listOf(video)), submit("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertEquals(Calls(searches = listOf("猫和老鼠")), submit("  猫和老鼠  "))
        assertEquals(Calls(searches = listOf("猫")), submit("https://search.bilibili.com/all?keyword=%E7%8C%AB"))
        assertEquals(Calls(searches = listOf("https://notb23.tv/abc123")), submit("https://notb23.tv/abc123"))
        assertEquals(Calls(), submit("   "))
    }

    @Test
    fun `retired owner refuses every submit`() {
        listOf("https://b23.tv/", "b23.tv/abc-123", "https://b23.tv/abc123", "猫和老鼠").forEach { keyword ->
            assertEquals(Calls(), submit(keyword, owned = false), keyword)
        }
    }

    @Test
    fun `same-list search takes a broken short link as text but never a complete one`() {
        assertEquals(Calls(scoped = listOf("https://b23.tv/")), submit("https://b23.tv/", item = BottomNavItem.HISTORY, listScoped = true))
        assertEquals(Calls(scoped = listOf("b23.tv/abc-123")), submit("b23.tv/abc-123", item = BottomNavItem.HISTORY, listScoped = true))
        assertEquals(
            Calls(links = listOf("https://b23.tv/abc123")),
            submit("https://b23.tv/abc123", item = BottomNavItem.HISTORY, listScoped = true),
        )
    }
}
