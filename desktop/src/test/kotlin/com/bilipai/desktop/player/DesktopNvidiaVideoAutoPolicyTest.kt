package com.bilipai.desktop.player

import kotlin.test.*

class DesktopNvidiaVideoAutoPolicyTest {
    @Test fun userHdrOffOnlyDisablesConversionAndKeepsTheBoundedUpscale() {
        val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, 5120, 2880, 16384, "bt.1886", null,
            true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.OFF)
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, decision.kind)
        assertEquals(5120.0 / 1920.0, decision.scale)
        assertFalse(decision.hdr)
        for (transfer in listOf("pq", "hlg")) {
            val native = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, transfer, null,
                true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.OFF)
            assertTrue(native.sourceIsHdr)
            assertFalse(native.hdr)
        }
    }

    @Test fun actualVideoRectangleRequestsBothUpscalingAndHdrOnAnActiveHdrDisplay() {
        val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "bt.1886", null, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE_AND_HDR, decision.kind)
        assertEquals(2.0, decision.scale)
        assertTrue(decision.hdr)
    }

    @Test fun smallVideoRectangleDoesNotUpscaleEvenOnALargeMonitor() {
        val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, 960, 540, 16384, "bt.1886", null, false)
        assertEquals(DesktopNvidiaVideoDecisionKind.DIRECT, decision.kind)
        assertEquals(1.0, decision.scale)
        assertFalse(decision.needsProcessing)
    }

    @Test fun textureLimitAndPinnedNativeScaleRangeBothBoundTheRequest() {
        assertEquals(1.5, resolveDesktopNvidiaVideoDecision(1920, 1080, 7680, 4320, 2880,
            "bt.1886", null, false).scale)
        assertEquals(4.0, resolveDesktopNvidiaVideoDecision(320, 180, 7680, 4320, 16384,
            "bt.1886", null, false).scale)
    }

    @Test fun unknownGpuLimitCannotLicenseAnUpscaleButHdrCanBeIndependent() {
        val wait = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null, "bt.1886", null, false)
        assertEquals(DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT, wait.kind)
        assertFalse(wait.needsProcessing)
        val hdr = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null, "bt.1886", null, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
        assertEquals(DesktopNvidiaVideoDecisionKind.HDR, hdr.kind)
        assertEquals(1.0, hdr.scale)
        assertTrue(hdr.hdr)
    }

    @Test fun nativeHdrAndDolbyVisionNeverEnterSdrToHdrConversion() {
        for ((gamma, dv) in listOf("pq" to null, "hlg" to null, "bt.1886" to 5)) {
            val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, gamma, dv, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
            assertTrue(decision.sourceIsHdr)
            assertFalse(decision.hdr)
            assertEquals(2.0, decision.scale)
        }
    }

    @Test fun hdrRequiresKnownDecodedSdrAndAnActuallyEnabledHdrDisplay() {
        for ((gamma, display) in listOf(null to true, "unknown" to true, "bt.1886" to false)) {
            val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, 1920, 1080, 16384, gamma, null, display, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
            assertFalse(decision.hdr)
        }
    }

    @Test fun unknownOrSdrTargetDoesNotRejectTheEligibleVsrRequest() {
        for ((transfer, primaries) in listOf(null to null, "bt.1886" to "bt.709", "linear" to "unknown")) {
            val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "bt.1886", null, true,
                transfer, primaries, hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
            assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, decision.kind)
            assertEquals(2.0, decision.scale)
            assertFalse(decision.hdr)
        }
        val hdr = resolveDesktopNvidiaVideoDecision(1920, 1080, 1920, 1080, 16384, "bt.1886", null, true,
            "linear", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
        assertEquals(DesktopNvidiaVideoDecisionKind.HDR, hdr.kind)
    }

    @Test fun absentOrInvalidVideoDimensionsRetainOriginalOutput() {
        for (dimensions in listOf(intArrayOf(0, 1080, 1920, 1080), intArrayOf(1920, -1, 1920, 1080), intArrayOf(1920, 1080, 0, 0))) {
            val decision = resolveDesktopNvidiaVideoDecision(dimensions[0], dimensions[1], dimensions[2], dimensions[3],
                16384, "bt.1886", null, true)
            assertEquals(DesktopNvidiaVideoDecisionKind.WAITING_VIDEO, decision.kind)
            assertFalse(decision.needsProcessing)
        }
    }
}
