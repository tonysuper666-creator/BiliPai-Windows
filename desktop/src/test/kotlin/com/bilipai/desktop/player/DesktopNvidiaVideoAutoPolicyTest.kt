package com.bilipai.desktop.player

import kotlin.test.*

class DesktopNvidiaVideoAutoPolicyTest {

    @Test fun sharedCoreProcessesFourAndEightKInputsAtNativeSizeBeforeRendererDownscale() {
        for (dimensions in listOf(intArrayOf(3840, 2160, 1920, 1080),
            intArrayOf(7680, 4320, 3840, 2160), intArrayOf(3840, 2160, 3840, 2160))) {
            val decision = resolveDesktopNvidiaVideoDecision(dimensions[0], dimensions[1],
                dimensions[2], dimensions[3], 16384, "bt.1886", null, false,
                sharedCoreNativeResolutionAvailable = true)
            assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, decision.kind)
            assertEquals(1.0, decision.scale)
            assertTrue(decision.nativeResolutionProcessing)
            assertTrue(decision.needsProcessing)
            assertFalse(decision.hdr)
        }
    }

    @Test fun sharedCoreUnityHdrRemainsSeparateFromNativeSizeProcessing() {
        for (mode in DesktopNvidiaVideoHdrMode.entries) {
            val decision = resolveDesktopNvidiaVideoDecision(3840, 2160, 1920, 1080, 16384,
                "srgb", null, true, "pq", "bt.2020", hdrMode = mode,
                sharedCoreNativeResolutionAvailable = true)
            assertEquals(1.0, decision.scale)
            assertTrue(decision.nativeResolutionProcessing)
            assertEquals(mode == DesktopNvidiaVideoHdrMode.AUTO, decision.hdr)
            assertEquals(if (mode == DesktopNvidiaVideoHdrMode.AUTO)
                DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION_AND_HDR
                else DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, decision.kind)
        }
    }

    @Test fun driverPatchRetainsItsExactRectangleGateWithoutSharedCoreCapability() {
        val downscale = resolveDesktopNvidiaVideoDecision(3840, 2160, 1920, 1080, 16384,
            "bt.1886", null, false, nativeResolutionPatchAvailable = true)
        assertEquals(DesktopNvidiaVideoDecisionKind.DIRECT, downscale.kind)
        assertFalse(downscale.needsProcessing)
        val exact = resolveDesktopNvidiaVideoDecision(3840, 2160, 3840, 2160, 16384,
            "bt.1886", null, false, nativeResolutionPatchAvailable = true)
        assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, exact.kind)
        assertFalse(resolveDesktopNvidiaVideoDecision(3840, 2160, 3840, 2160, 16384,
            "bt.1886", null, false).needsProcessing)
    }

    @Test fun sharedCoreUnityStillRequiresEvenBoundedKnownSdrInputAndValidViewport() {
        for ((transfer, dv) in listOf(null to null, "unknown" to null, "linear" to null,
            "bt.709" to null, "pq" to null, "hlg" to null, "bt.1886" to 5,
            "bt.1886" to 0)) {
            val decision = resolveDesktopNvidiaVideoDecision(3840, 2160, 1920, 1080,
                16384, transfer, dv, false, sharedCoreNativeResolutionAvailable = true)
            assertFalse(decision.nativeResolutionProcessing, "$transfer/$dv")
            assertFalse(decision.needsProcessing, "$transfer/$dv")
        }
        for ((width, height, limit) in listOf(Triple(3839, 2160, 16384), Triple(3840, 2159, 16384),
            Triple(7680, 4320, 4096), Triple(3840, 2160, null), Triple(3840, 2160, 0))) {
            val decision = resolveDesktopNvidiaVideoDecision(width, height, 1920, 1080,
                limit, "srgb", null, false, sharedCoreNativeResolutionAvailable = true)
            assertFalse(decision.nativeResolutionProcessing)
            assertFalse(decision.needsProcessing)
        }
        for ((width, height) in listOf(0 to 1080, 1920 to 0, -1 to 1080)) {
            val decision = resolveDesktopNvidiaVideoDecision(3840, 2160, width, height,
                16384, "srgb", null, false, sharedCoreNativeResolutionAvailable = true)
            assertEquals(DesktopNvidiaVideoDecisionKind.WAITING_VIDEO, decision.kind)
            assertFalse(decision.needsProcessing)
        }
        assertFalse(resolveDesktopNvidiaVideoDecision(3840, 2160, 3841, 2160, 16384,
            "srgb", null, false, sharedCoreNativeResolutionAvailable = true).nativeResolutionProcessing)
    }

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
    @Test fun hdrOnlyPreservesNativeHdrAndDolbyVisionWithoutChangingExistingSrRouting() {
        for ((transfer, dv) in listOf("pq" to null, "hlg" to null, "bt.1886" to 5)) {
            val only = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384,
                transfer, dv, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO,
                sharedCoreNativeResolutionAvailable = true, srEnabled = false)
            assertEquals(DesktopNvidiaVideoDecisionKind.DIRECT, only.kind)
            assertEquals(1.0, only.scale)
            assertFalse(only.needsProcessing)
            assertTrue(only.sourceIsHdr)
        }
    }

    @Test fun hdrOnlyNeverUpscalesOrRequestsNativeResolutionSr() {
        for ((displayW, displayH) in listOf(3840 to 2160, 960 to 540)) {
            val decision = resolveDesktopNvidiaVideoDecision(1920, 1080, displayW, displayH, 16384,
                "bt.1886", null, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO,
                sharedCoreNativeResolutionAvailable = true, srEnabled = false)
            assertEquals(DesktopNvidiaVideoDecisionKind.HDR, decision.kind)
            assertEquals(1.0, decision.scale)
            assertTrue(decision.hdr)
            assertFalse(decision.nativeResolutionProcessing)
        }
    }

    @Test fun hdrOnlyRequiresQualifiedCoreKnownSdrHdrTargetAndInputTextureLimit() {
        for (case in listOf("core", "source", "nativeHDR", "dv", "display", "target", "odd", "limit")) {
            val decision = resolveDesktopNvidiaVideoDecision(if (case == "odd") 1919 else 1920, 1080,
                3840, 2160, if (case == "limit") 1024 else 16384,
                if (case == "source") "bt.709" else if (case == "nativeHDR") "pq" else "bt.1886",
                if (case == "dv") 5 else null, case != "display", if (case == "target") "srgb" else "pq",
                "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO,
                sharedCoreNativeResolutionAvailable = case != "core", srEnabled = false)
            assertFalse(decision.needsProcessing, case)
            assertEquals(1.0, decision.scale, case)
        }
        val waiting = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null,
            "srgb", null, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO,
            sharedCoreNativeResolutionAvailable = true, srEnabled = false)
        assertEquals(DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT, waiting.kind)
        assertFalse(waiting.needsProcessing)
    }

}
