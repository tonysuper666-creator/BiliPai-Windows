// 文件路径: test/.../ViewPointSegmentBarPolicyTest.kt
package com.android.purebilibili.feature.video.ui.overlay

import com.android.purebilibili.data.model.response.ViewPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewPointSegmentBarPolicyTest {

    private fun point(from: Int, to: Int, content: String = "章节") =
        ViewPoint(from = from, to = to, content = content)

    @Test
    fun normalize_sorts_clampsAndDropsInvalidSegments() {
        val segments = normalizeViewPointSegments(
            viewPoints = listOf(
                point(from = 60, to = 30, content = "倒序"),   // 非法：to <= from
                point(from = 40, to = 120, content = "B"),
                point(from = 0, to = 50, content = "A"),
                point(from = 100, to = 200, content = "重叠段"),
                point(from = 300, to = 400, content = "  ")    // 非法：空标题
            ),
            durationMs = 250_000L
        )

        assertEquals(2, segments.size)
        assertEquals(0L to 50_000L, segments[0].fromMs to segments[0].toMs)
        // 40s 起点被前段截断到 50s；重叠部分被吸收
        assertEquals(50_000L to 200_000L, segments[1].fromMs to segments[1].toMs)
    }

    @Test
    fun normalize_clampsBeyondDuration() {
        val segments = normalizeViewPointSegments(
            viewPoints = listOf(point(from = 0, to = 9999, content = "超长段")),
            durationMs = 120_000L
        )

        assertEquals(1, segments.size)
        assertEquals(120_000L, segments[0].toMs)
    }

    @Test
    fun normalize_returnsEmptyWithoutDuration() {
        assertTrue(normalizeViewPointSegments(listOf(point(0, 10)), durationMs = 0L).isEmpty())
    }

    @Test
    fun find_locatesContainingSegment() {
        val segments = normalizeViewPointSegments(
            viewPoints = listOf(point(0, 60, "A"), point(60, 120, "B"), point(120, 180, "C")),
            durationMs = 180_000L
        )

        assertEquals("A", findViewPointSegmentAt(segments, 30_000L)?.content)
        assertEquals("B", findViewPointSegmentAt(segments, 90_000L)?.content)
    }

    @Test
    fun find_lastSegmentExtendsToVideoEnd() {
        val segments = normalizeViewPointSegments(
            viewPoints = listOf(point(0, 60, "A"), point(60, 120, "B")),
            durationMs = 180_000L
        )

        // 120s 之后视频还在继续：视为仍在最后一段，点击回跳段起点
        assertEquals("B", findViewPointSegmentAt(segments, 170_000L)?.content)
    }

    @Test
    fun find_emptySegmentsReturnsNull() {
        assertTrue(findViewPointSegmentAt(emptyList(), 0L) == null)
    }
}
