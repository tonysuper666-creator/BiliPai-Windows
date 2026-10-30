package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewInfo
import kotlin.test.*

/** The transport used to throw "Original resolved video metadata is required
 * before transport publication" whenever this request's own metadata had not
 * been observed yet, which the player surfaced as a system message box. */
class DesktopOriginalVideoTransportMetadataTest {
    private fun info(bvid: String, title: String) = ViewInfo(bvid = bvid, title = title)

    @Test fun observedOriginalMetadataProvidesTheTitle() {
        val metadata = DesktopOriginalVideoTransportMetadata(info("BV1observed", "已观察的标题"))
        assertEquals("已观察的标题", metadata.titleFor("BV1observed", { null }) { fail("no degradation expected: $it") })
    }

    @Test fun metadataCommittedAfterTheRequestBeganStillProvidesTheTitle() {
        val metadata = DesktopOriginalVideoTransportMetadata(null)
        var committed: ViewInfo? = null
        assertEquals("", metadata.titleFor("BV1late", { committed }) {})
        // The page's detail read finishes after this request was captured.
        committed = info("BV1late", "晚到的标题")
        assertEquals("晚到的标题", metadata.titleFor("BV1late", { committed }) {})
        assertNotNull(metadata.observed("BV1late"))
    }

    @Test fun missingMetadataDegradesWithALogInsteadOfThrowing() {
        val metadata = DesktopOriginalVideoTransportMetadata(null)
        val reports = mutableListOf<String>()
        assertEquals("", metadata.titleFor("BV1missing", { null }) { reports += it })
        assertEquals("", metadata.titleFor("BV1missing", { null }) { reports += it })
        assertEquals(1, reports.size, "one degradation line per bvid")
        assertTrue(reports.single().contains("BV1missing"))
        assertTrue(reports.single().contains("without a title"))
        assertNull(metadata.observed("BV1missing"))
    }

    @Test fun committedMetadataForAnotherVideoIsNotBorrowed() {
        val metadata = DesktopOriginalVideoTransportMetadata(null)
        val reports = mutableListOf<String>()
        assertEquals("", metadata.titleFor("BV1requested", { info("BV1other", "别的视频") }) { reports += it })
        assertEquals(1, reports.size)
        assertNull(metadata.observed("BV1requested"))
    }

    @Test fun blankObservedInfoIsIgnored() {
        val metadata = DesktopOriginalVideoTransportMetadata(info("", "空标识"))
        assertNull(metadata.observed(""))
    }
}
