package com.bilipai.desktop.data

import com.android.purebilibili.core.util.BilibiliUrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCustomSchemeVideoUrlTest {
    @Test
    fun numericVideoRoutesKeepTheirHostSegmentForBothSchemeAliases() {
        for (scheme in listOf("bilibili", "bili")) {
            for (host in listOf("video", "VIDEO")) {
                val result = BilibiliUrlParser.parse("$scheme://$host/170001?from=share")
                assertTrue(result.isValid)
                assertEquals(170001L, result.aid)
                assertEquals("av170001", result.getVideoId())
                assertNull(result.dynamicId)
            }
        }
    }

    @Test
    fun largeNumericVideoRoutesRetainTheExistingDynamicIdBoundary() {
        for (scheme in listOf("bilibili", "bili")) {
            val result = BilibiliUrlParser.parse("$scheme://video/1199344045210468386")
            assertTrue(result.isValid)
            assertEquals("1199344045210468386", result.getDynamicTargetId())
            assertNull(result.getVideoId())
            assertNull(result.aid)
        }
    }

    @Test
    fun numericTrackingValuesAndUnrelatedRoutesDoNotBecomeVideoTargets() {
        for (input in listOf(
            "bilibili://video/not-an-id?aid=170001",
            "bili://video/?from=170001",
            "bilibili://user/170001",
            "unrelated://video/170001",
            "https://www.bilibili.com.evil.invalid/video/170001",
        )) {
            assertFalse(BilibiliUrlParser.parse(input).isValid, input)
        }
        assertEquals("av170001", BilibiliUrlParser.parse("https://www.bilibili.com/video/170001").getVideoId())
        assertEquals("av170001", BilibiliUrlParser.parse("bili://video/av170001").getVideoId())
        assertEquals("BV1xx411c7mD", BilibiliUrlParser.parse("bilibili://video/BV1xx411c7mD").getVideoId())
    }
}
