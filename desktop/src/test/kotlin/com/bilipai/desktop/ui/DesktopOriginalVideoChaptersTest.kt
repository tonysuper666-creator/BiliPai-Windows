package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewPoint
import com.android.purebilibili.feature.video.ui.overlay.findViewPointSegmentAt
import com.android.purebilibili.feature.video.ui.overlay.normalizeViewPointSegments
import kotlin.test.*

class DesktopOriginalVideoChaptersTest {
    private fun result(cid: Long = 101L, token: Long = 7L) = DesktopOriginalVideoChapterResult(
        "BV1chapter", cid, token, listOf(ViewPoint("开场", 0, 20), ViewPoint("中段", 20, 40), ViewPoint("收尾", 40, 60)))

    @Test fun originalSecondsBecomeMillisecondsForActualSeek() {
        val chapter = result()
        val segments = normalizeViewPointSegments(chapter.points, 60_000L)
        assertEquals(listOf(0L, 20_000L, 40_000L), segments.map { it.fromMs })
        assertEquals("中段", findViewPointSegmentAt(segments, 20_000L)?.content)
        assertTrue(isDesktopOriginalVideoChapterSeekCurrent(chapter, chapter, "BV1chapter", 101L, 7L, 20_000L, 60_000L))
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(chapter, chapter, "BV1chapter", 101L, 7L, 20L, 60_000L))
    }

    @Test fun replacingPartRejectsPreviousMetadataBeforeNewResultArrives() {
        val previous = result()
        assertSame(previous, desktopOriginalVideoChaptersForOwner(previous, "BV1chapter", 101L, 7L))
        assertNull(desktopOriginalVideoChaptersForOwner(previous, "BV1chapter", 102L, 8L))
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(previous, previous, "BV1chapter", 102L, 8L, 20_000L, 60_000L))
    }

    @Test fun delayedOldResponseCannotMatchNewRequestForSamePart() {
        val delayed = result(token = 7L)
        val current = result(token = 8L)
        assertNull(desktopOriginalVideoChaptersForOwner(delayed, "BV1chapter", 101L, 8L))
        assertSame(current, desktopOriginalVideoChaptersForOwner(current, "BV1chapter", 101L, 8L))
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(delayed, current, "BV1chapter", 101L, 8L, 20_000L, 60_000L))
    }

    @Test fun returningToSamePartDoesNotReviveOldMenu() {
        val old = result(token = 7L)
        val returned = result(token = 9L)
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(old, returned, "BV1chapter", 101L, 9L, 0L, 60_000L))
        assertTrue(isDesktopOriginalVideoChapterSeekCurrent(returned, returned, "BV1chapter", 101L, 9L, 0L, 60_000L))
    }

    @Test fun equalPayloadFromNewResponseRequiresItsActualIdentity() {
        val captured = result()
        val replacement = result()
        assertEquals(captured.points, replacement.points)
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(captured, replacement, "BV1chapter", 101L, 7L, 20_000L, 60_000L))
    }

    @Test fun foreignVideoAndUnknownDurationFailClosed() {
        val chapter = result()
        assertNull(desktopOriginalVideoChaptersForOwner(chapter, "BV2foreign", 101L, 7L))
        assertNull(desktopOriginalVideoChaptersForOwner(chapter, "", 101L, 7L))
        assertNull(desktopOriginalVideoChaptersForOwner(chapter, "BV1chapter", 0L, 7L))
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(chapter, chapter, "BV1chapter", 101L, 7L, 0L, 0L))
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(chapter, chapter, "BV1chapter", 101L, 7L, 0L, -1L))
    }

    @Test fun originalNormalizationKeepsGapsAndClampsOverlappingStarts() {
        val chapter = DesktopOriginalVideoChapterResult("BV1chapter", 101L, 7L,
            listOf(ViewPoint("后段", 30, 70), ViewPoint("开场", 0, 10), ViewPoint("重叠", 50, 80), ViewPoint("无效", 15, 15)))
        val segments = normalizeViewPointSegments(chapter.points, 60_000L)
        assertEquals(listOf(0L, 30_000L), segments.map { it.fromMs })
        assertEquals(listOf(10_000L, 60_000L), segments.map { it.toMs })
        assertNull(findViewPointSegmentAt(segments, 20_000L))
        assertTrue(isDesktopOriginalVideoChapterSeekCurrent(chapter, chapter, "BV1chapter", 101L, 7L, 30_000L, 60_000L))
        assertFalse(isDesktopOriginalVideoChapterSeekCurrent(chapter, chapter, "BV1chapter", 101L, 7L, 50_000L, 60_000L))
    }

    @Test fun responseCopiesMutableTransportList() {
        val points = mutableListOf(ViewPoint("开场", 0, 20))
        val chapter = DesktopOriginalVideoChapterResult("BV1chapter", 101L, 7L, points)
        points.clear()
        assertEquals("开场", chapter.points.single().content)
        assertTrue(isDesktopOriginalVideoChapterSeekCurrent(chapter, chapter, "BV1chapter", 101L, 7L, 0L, 60_000L))
    }
}
