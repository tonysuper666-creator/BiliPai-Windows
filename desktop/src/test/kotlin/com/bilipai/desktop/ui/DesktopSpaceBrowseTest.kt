package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import kotlin.test.*

class DesktopSpaceBrowseTest {
    @Test fun revisitingAnUpRestoresSubtabFolderRankFilterAndTabRailPosition() {
        val memory = DesktopBrowseMemory()
        val owner = desktopSpaceOwner(1, 7, 22)
        val state = memory.screen(owner) { DesktopSpaceBrowseState() }
        state.tab = DesktopSpaceTab.FAVORITES
        state.folderSource = FavFolderSource.SUBSCRIBED
        state.folder = FavFolder(id = 9, title = "完整文件夹")
        state.selectedPrivilege = 2
        memory.screen(desktopSpaceOwner(1, 7, 23)) { DesktopSpaceBrowseState() }.tab = DesktopSpaceTab.GUARDS
        val restored = memory.screen(owner) { DesktopSpaceBrowseState() }
        assertSame(state, restored); assertSame(state.tabScroll, restored.tabScroll)
        assertEquals(DesktopSpaceTab.FAVORITES, restored.tab); assertEquals(FavFolderSource.SUBSCRIBED, restored.folderSource)
        assertEquals(9, restored.folder?.id); assertEquals(2, restored.selectedPrivilege)
    }

    @Test fun accountEpochAndDifferentUpHaveSeparateScreenOwners() {
        val memory = DesktopBrowseMemory()
        val original = memory.screen(desktopSpaceOwner(1, 7, 22)) { DesktopSpaceBrowseState() }
        original.tab = DesktopSpaceTab.CHARGE; original.profileError = IllegalStateException("old owner")
        val relogin = memory.screen(desktopSpaceOwner(2, 7, 22)) { DesktopSpaceBrowseState() }
        val switched = memory.screen(desktopSpaceOwner(2, 8, 22)) { DesktopSpaceBrowseState() }
        val otherUp = memory.screen(desktopSpaceOwner(1, 7, 23)) { DesktopSpaceBrowseState() }
        listOf(relogin, switched, otherUp).forEach { assertNotSame(original, it); assertNull(it.profileError); assertEquals(DesktopSpaceTab.VIDEOS, it.tab) }
    }

    @Test fun refreshAndLoadMoreFailureKeepRowsCursorScrollAndSuppressStaleReplacement() {
        val memory = DesktopBrowseMemory()
        val key = Pair(desktopSpaceOwner(1, 7, 22), DesktopSpaceTab.ARTICLES)
        val feed = memory.feeds.page<SpaceArticleItem, Int>(key)
        val original = SpaceArticleItem(id = 5, title = "上一页")
        assertTrue(feed.acceptBatch(0, CommunityBatch(listOf(original), 2), true) { it.id })
        assertTrue(feed.acceptFailure(0, IllegalStateException("retry"), 2, false))
        assertEquals(listOf(original), feed.rows); assertEquals(2, feed.next); assertEquals(2, feed.failedCursor)
        val scroll = feed.scroll
        feed.invalidate()
        assertEquals(listOf(original), feed.rows); assertSame(scroll, feed.scroll)
        assertFalse(feed.acceptBatch(0, CommunityBatch(listOf(SpaceArticleItem(id = 6)), null), true) { it.id })
        assertTrue(feed.acceptFailure(1, IllegalStateException("refresh failed"), 1, true))
        assertEquals(listOf(original), feed.rows)
        assertTrue(feed.acceptBatch(1, CommunityBatch(listOf(SpaceArticleItem(id = 8)), null), true) { it.id })
        assertEquals(8, feed.rows.single().id); assertSame(scroll, feed.scroll)
    }

    @Test fun returningFromFolderDetailsDoesNotReplaceSubscribedFolderPagination() {
        val memory = DesktopBrowseMemory()
        val namespace = desktopSpaceOwner(1, 7, 22)
        val folderKey = Pair(namespace, Pair("space-folders", FavFolderSource.SUBSCRIBED))
        val folders = memory.feeds.page<FavFolder, Int>(folderKey)
        folders.acceptBatch(0, CommunityBatch(listOf(FavFolder(id = 9, title = "订阅", media_count = 10)), 3), true) { it.id }
        memory.feeds.page<String, Int>(Pair(namespace, listOf("space-folder", 9))).rows = listOf("folder content")
        val restored = memory.feeds.page<FavFolder, Int>(folderKey)
        assertSame(folders, restored); assertEquals(3, restored.next); assertEquals(9, restored.rows.single().id)
    }

    @Test fun articleRoutingUsesOriginalJumpTargetBeforeIdAndDistinguishesOpus() {
        val events = mutableListOf<String>()
        fun route(article: SpaceArticleItem) = navigateSpaceArticle(article,
            { events += "article:$it" }, { events += "dynamic:$it" }, { events += "user:$it" }, { events += "video:${it.bvid}" })
        route(SpaceArticleItem(id = 3, jump_url = "https://www.bilibili.com/opus/1000000000000001"))
        route(SpaceArticleItem(id = 1000000000000001, jump_url = "https://www.bilibili.com/read/cv88"))
        route(SpaceArticleItem(id = 1000000000000002))
        route(SpaceArticleItem(id = 7))
        route(SpaceArticleItem(id = 0))
        assertEquals(listOf("dynamic:1000000000000001", "article:88", "dynamic:1000000000000002", "article:7"), events)
    }

    @Test fun originalNavigationParserKeepsWrappedLinksAndJvmUriBinding() {
        val parser = com.android.purebilibili.core.util.BilibiliNavigationTargetParser
        assertEquals(com.android.purebilibili.core.util.BilibiliNavigationTarget.Article(88),
            parser.parse("https://www.bilibili.com/blackboard?url=https%3A%2F%2Fwww.bilibili.com%2Fread%2Fcv88"))
        assertEquals("1000000000000001", com.android.purebilibili.core.util.BilibiliUrlParser
            .parseUri(java.net.URI("bilibili://opus/1000000000000001")).dynamicId)
    }
}
