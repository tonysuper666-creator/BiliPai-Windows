package com.bilipai.desktop.download

import com.android.purebilibili.data.repository.resolveDanmakuSegmentCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 弹幕下载完整性校验的回归：应有段数必须用上游下载器给的值（dmSge.total 优先），
 * 不能只用时长反推，否则完整的弹幕会被判成缺段（TON-1004 验收退回的两例）。
 */
class DesktopDanmakuSegmentIntegrityTest {

    private fun manifest(standardSegmentCount: Int, durationMs: Long, segmentCount: Int = standardSegmentCount) =
        """{"bvid":"BV1xx","cid":100,"aid":200,"durationMs":$durationMs,""" +
            """"segmentPaths":${(1..segmentCount).joinToString(",", "[", "]") { "\"seg_$it.pb\"" }},""" +
            """"standardSegmentCount":$standardSegmentCount,"savedAt":1}"""

    @Test
    fun shortClipWithFiveMetadataSegmentsIsNotReportedAsShort() {
        // 1 秒稿件、dmSge.total=5：上游下载 5 段，按时长反推只有 1 段。
        assertEquals(5, resolveDanmakuSegmentCount(1_000L, 5))
        assertEquals(1, resolveDanmakuSegmentCount(1_000L, null))
        assertNull(desktopDanmakuStandardSegmentShortfall(manifest(5, 1_000L), 5))
    }

    @Test
    fun longClipWithOneMetadataSegmentIsNotReportedAsShort() {
        // 12 分钟稿件、dmSge.total=1：上游下载 1 段，按时长反推是 3 段。
        assertEquals(1, resolveDanmakuSegmentCount(720_001L, 1))
        assertEquals(3, resolveDanmakuSegmentCount(720_001L, null))
        assertNull(desktopDanmakuStandardSegmentShortfall(manifest(1, 720_001L), 1))
    }

    @Test
    fun aMissingStandardSegmentIsStillReported() {
        val reason = desktopDanmakuStandardSegmentShortfall(manifest(4, 1_000L), 5)
        assertNotNull(reason)
        assertEquals("弹幕标准分段未全部下载（4/5），媒体文件继续下载", reason)
    }

    @Test
    fun extraStandardSegmentsBeyondTheExpectationAreAlsoReported() {
        // 多出的段同样说明清单和下载器对不上，校验保持双向严格。
        assertNotNull(desktopDanmakuStandardSegmentShortfall(manifest(3, 720_001L, segmentCount = 4), 1))
    }
}
