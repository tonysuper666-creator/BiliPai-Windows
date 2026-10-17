package com.android.purebilibili.data.model.response

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchUpItemTest {
    @Test
    fun relatedVideosInheritMissingAuthorFromUpResult() {
        val up = SearchUpItem(
            mid = 123L,
            uname = "<em>录像分析员</em>",
            res = listOf(SearchVideoItem(bvid = "BV123", author = " "))
        ).cleanupFields()

        val video = up.res!!.single().toVideoItem()
        assertEquals("录像分析员", video.owner.name)
        assertEquals(123L, video.owner.mid)
    }

    @Test
    fun relatedVideosPreserveExistingAuthorInformation() {
        val up = SearchUpItem(
            mid = 123L,
            uname = "录像分析员",
            res = listOf(SearchVideoItem(author = "原作者", mid = 456L))
        ).cleanupFields()

        val video = up.res!!.single().toVideoItem()
        assertEquals("原作者", video.owner.name)
        assertEquals(456L, video.owner.mid)
    }
}
