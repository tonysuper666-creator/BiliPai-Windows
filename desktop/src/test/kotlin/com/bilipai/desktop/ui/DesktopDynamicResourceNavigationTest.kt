package com.bilipai.desktop.ui

import com.android.purebilibili.navigation.navigateOriginalDynamicCollection
import com.android.purebilibili.navigation.navigateOriginalDynamicCourse
import kotlin.test.*

class DesktopDynamicResourceNavigationTest {
    @Test fun favoriteFolderKeepsOriginalTypeIdOwnerAndTitle() {
        val routes = mutableListOf<List<Any>>()
        navigateOriginalDynamicCollection(812L, 22L, "原版收藏夹", "https://www.bilibili.com/MEDIALIST/DETAIL/ml812",
            onFavorite = { type, id, mid, title -> routes += listOf(type, id, mid, title) },
            onWeb = { _, _ -> fail("Favorite folder must open the public folder page") })
        assertEquals(listOf(listOf<Any>("favorite", 812L, 22L, "原版收藏夹")), routes)
    }

    @Test fun otherCollectionUrlsAndInvalidMediaIdsKeepOriginalWebFallback() {
        for ((id, url) in listOf(812L to "https://www.bilibili.com/list/812", 0L to "https://www.bilibili.com/medialist/detail/ml812")) {
            val opened = mutableListOf<Pair<String, String>>()
            navigateOriginalDynamicCollection(id, 22L, "动态里的标题", url,
                onFavorite = { _, _, _, _ -> fail("Non-folder route") }, onWeb = { raw, title -> opened += raw to title })
            assertEquals(listOf(url to "动态里的标题"), opened)
        }
    }

    @Test fun blankLinksDoNotOpenAnotherDestination() {
        navigateOriginalDynamicCollection(812L, 22L, "title", "  ",
            onFavorite = { _, _, _, _ -> fail("Blank link") }, onWeb = { _, _ -> fail("Blank link") })
        navigateOriginalDynamicCourse("", "title", onPlayer = { _, _, _ -> fail("Blank link") }, onWeb = { _, _ -> fail("Blank link") })
    }

    @Test fun coursesUseOriginalSeasonEpisodeAndSchemeParsing() {
        val examples = listOf(
            "https://www.bilibili.com/cheese/play/ss196" to (196L to 0L),
            "https://www.bilibili.com/cheese/play/ep3388" to (0L to 3388L),
            "bilibili://cheese/play/ss196?ep_id=3388" to (196L to 3388L),
            "https://www.bilibili.com/cheese/play?season_id=196&ep_id=3388" to (196L to 3388L))
        for ((url, expected) in examples) {
            val opened = mutableListOf<Pair<Long, Long>>()
            navigateOriginalDynamicCourse(url, "课程", onPlayer = { sid, eid, course ->
                assertTrue(course); opened += sid to eid
            }, onWeb = { _, _ -> fail("Parsed course") })
            assertEquals(listOf(expected), opened)
        }
    }

    @Test fun unparsedCourseKeepsOriginalTitleAndWebFallback() {
        val url = "https://www.bilibili.com/cheese/list"
        var opened: Pair<String, String>? = null
        navigateOriginalDynamicCourse(url, "课程目录", onPlayer = { _, _, _ -> fail("No playable course id") },
            onWeb = { raw, title -> opened = raw to title })
        assertEquals(url to "课程目录", opened)
    }
}
