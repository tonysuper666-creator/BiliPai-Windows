package com.android.purebilibili.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleCommentNavigationTest {
    @Test
    fun nativeAndWebLinksPreserveCommentLocation() {
        listOf(
            "https://www.bilibili.com/read/cv123?comment_root_id=456#reply789",
            "bilibili://article/123?root=456&comment_id=789",
            "bilibili://browser/?url=https%3A%2F%2Fwww.bilibili.com%2Fread%2Fcv123%3Froot_id%3D456%26comment_id%3D789",
        ).forEach { link ->
            assertEquals(BilibiliNavigationTarget.Article(123L, 456L, 789L), BilibiliNavigationTargetParser.parse(link), link)
        }
    }

    @Test
    fun invalidCommentIdsDoNotTurnReadingIntoCommentEntry() {
        assertEquals(
            BilibiliNavigationTarget.Article(123L),
            BilibiliNavigationTargetParser.parse("https://www.bilibili.com/read/cv123?root_id=-1&comment_id=invalid"),
        )
    }
}
