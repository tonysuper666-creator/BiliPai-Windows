package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
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

    @Test fun aidOnlyRequestWithoutMetadataDegradesWithALogInsteadOfThrowing() {
        val metadata = DesktopOriginalVideoTransportMetadata(ViewInfo(bvid = "BV1other", aid = 7L, title = "别的稿件"))
        val reports = mutableListOf<String>()
        val request = PlaybackRequest(bvid = "", aid = 170001L, cid = 22L)
        val unrelated = ViewInfo(bvid = "BV1committed", aid = 8L, title = "页面上的别的稿件")
        assertNull(metadata.bvidFor(request, { unrelated }) { reports += it })
        assertNull(metadata.bvidFor(request, { null }) { reports += it })
        assertEquals(1, reports.size, "one degradation line per aid")
        assertTrue(reports.single().contains("aid=170001"))
    }

    @Test fun aidOnlyRequestResolvesItsOwnAidFromObservedOrCommittedMetadata() {
        val request = PlaybackRequest(bvid = "", aid = 170001L, cid = 22L)
        val observed = DesktopOriginalVideoTransportMetadata(ViewInfo(bvid = "BV1own", aid = 170001L, title = "自己的"))
        assertEquals("BV1own", observed.bvidFor(request, { null }) { fail("no degradation expected: $it") })
        val committed = DesktopOriginalVideoTransportMetadata(null)
        assertEquals("BV1late", committed.bvidFor(request, { ViewInfo(bvid = "BV1late", aid = 170001L, title = "晚到") }) {
            fail("no degradation expected: $it")
        })
        assertEquals("晚到", committed.titleFor("BV1late", { null }) { fail("no degradation expected: $it") })
        assertEquals("BV1named", committed.bvidFor(PlaybackRequest(bvid = "BV1named", aid = 0L, cid = 1L), { null }) {
            fail("a BV request names its own BV: $it")
        })
    }
}
