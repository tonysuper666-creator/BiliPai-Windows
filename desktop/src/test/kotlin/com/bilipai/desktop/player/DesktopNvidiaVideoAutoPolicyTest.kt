package com.bilipai.desktop.player

import com.bilipai.desktop.nvidia.DesktopNvidiaVideoBudget
import com.bilipai.desktop.nvidia.NvidiaScalePolicy
import com.bilipai.desktop.nvidia.NvidiaSkuClass
import com.bilipai.desktop.nvidia.NvidiaSkuInfo
import com.bilipai.desktop.nvidia.NvidiaTier
import com.bilipai.desktop.nvidia.buildBudget
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

    @Test fun smallLayoutChangesShareAnUpperOutputBucketWithoutAnExtraUpscale() {
        val values = listOf(2.01, 2.05, 2.12, 2.19, 2.24)
        for (requested in values) {
            val actual = nvidiaStableUpscale(requested, 1920, 1080, 16384)
            assertEquals(2.25, actual)
            assertTrue(actual >= requested)
        }
        assertEquals(2.5, nvidiaStableUpscale(2.26, 1920, 1080, 16384))
    }

    @Test fun scaleBucketsNeverExceedTheActualTextureCapOrFourTimesLimit() {
        assertEquals(2.1, nvidiaStableUpscale(2.01, 1000, 600, 2100))
        assertEquals(4.0, nvidiaStableUpscale(5.0, 1920, 1080, 16384))
        assertEquals(1.0, nvidiaStableUpscale(1.05, 1920, 1080, 1000))
    }

    @Test fun nativeHdrOnlyAndUnknownGeometryAreNeverPromotedToAnUpscale() {
        assertEquals(1.0, nvidiaStableUpscale(1.0, 1920, 1080, 16384))
        assertEquals(1.0, nvidiaStableUpscale(Double.NaN, 1920, 1080, 16384))
        assertEquals(1.0, nvidiaStableUpscale(Double.POSITIVE_INFINITY, 1920, 1080, 16384))
        assertEquals(1.0, nvidiaStableUpscale(2.0, 0, 1080, 16384))
        assertEquals(1.0, nvidiaStableUpscale(2.0, 1920, 1080, null))
    }

    // ---- Budget (slice S4). Every case above runs with budget = null and is unchanged. ----

    private fun sku(model: Int, skuClass: NvidiaSkuClass, costFactor: Double) = NvidiaSkuInfo(model, skuClass, costFactor, null, false)

    // 5080 class: ULTRA, rate 1000.
    private fun ultra(frameRate: Double? = 60.0, extendedRangeDenied: Boolean = false) =
        buildBudget(NvidiaTier.ULTRA, sku(5080, NvidiaSkuClass.S, 1.0), frameRate, extendedRangeDenied)
    // 4070 Ti class: HIGH with factor 1.0, rate 500.
    private fun highTi(frameRate: Double? = 60.0) = buildBudget(NvidiaTier.HIGH, sku(4070, NvidiaSkuClass.A, 1.0), frameRate)
    // 4070 class: HIGH with factor 0.7, rate 350.
    private fun high(frameRate: Double? = 60.0) = buildBudget(NvidiaTier.HIGH, sku(4070, NvidiaSkuClass.A, 0.7), frameRate)
    // 3060 class: LIGHT, rate 125.
    private fun light(frameRate: Double? = 60.0) = buildBudget(NvidiaTier.LIGHT, sku(3060, NvidiaSkuClass.C, 1.0), frameRate)

    // A budget with no practical limit: it must change nothing.
    private val ample = DesktopNvidiaVideoBudget(
        tier = NvidiaTier.ULTRA, maxScale = 4.0, srInputMaxWidth = 1_000_000, srInputMaxHeight = 1_000_000,
        nativeSizeMaxInputPixels = Long.MAX_VALUE, hdrOnlyMaxInputPixels = Long.MAX_VALUE,
        maxOutputPixels = Long.MAX_VALUE / 4, rateMpixPerSecond = 1.0e12, frameRate = 60.0,
        hdrWithSrAllowed = true, hdrOnlyAllowed = true, scalePolicy = NvidiaScalePolicy.STICKY_UP, extendedRangeDenied = false,
    )

    private val existingCases: List<(DesktopNvidiaVideoBudget?) -> DesktopNvidiaVideoDecision> = listOf(
        { b -> resolveDesktopNvidiaVideoDecision(3840, 2160, 1920, 1080, 16384, "bt.1886", null, false,
            sharedCoreNativeResolutionAvailable = true, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(7680, 4320, 3840, 2160, 16384, "bt.1886", null, false,
            sharedCoreNativeResolutionAvailable = true, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(3840, 2160, 1920, 1080, 16384, "srgb", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, sharedCoreNativeResolutionAvailable = true, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(3840, 2160, 3840, 2160, 16384, "bt.1886", null, false,
            nativeResolutionPatchAvailable = true, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 5120, 2880, 16384, "bt.1886", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.OFF, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "bt.1886", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 960, 540, 16384, "bt.1886", null, false, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 7680, 4320, 2880, "bt.1886", null, false, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(320, 180, 7680, 4320, 16384, "bt.1886", null, false, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null, "bt.1886", null, false, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null, "bt.1886", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "pq", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "bt.1886", 5, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 1920, 1080, 16384, "bt.1886", null, true, "linear", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(0, 1080, 1920, 1080, 16384, "bt.1886", null, true, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "pq", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, sharedCoreNativeResolutionAvailable = true, srEnabled = false, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "bt.1886", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, sharedCoreNativeResolutionAvailable = true, srEnabled = false, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1919, 1080, 3840, 2160, 16384, "bt.1886", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, sharedCoreNativeResolutionAvailable = true, srEnabled = false, budget = b) },
        { b -> resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null, "srgb", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, sharedCoreNativeResolutionAvailable = true, srEnabled = false, budget = b) },
    )

    @Test fun aBudgetWithNoPracticalLimitChangesNoExistingDecision() {
        for ((index, case) in existingCases.withIndex()) {
            assertEquals(case(null), case(ample), "case $index")
        }
    }

    @Test fun aSourceThatNeededNoProcessingKeepsItsOutcomeUnderEveryBudget() {
        val tiny = light()
        // A smaller video rectangle is DIRECT whatever the source size: the budget never creates a decision.
        val direct = resolveDesktopNvidiaVideoDecision(7680, 4320, 960, 540, 16384, "bt.1886", null, false, budget = tiny)
        assertEquals(DesktopNvidiaVideoDecisionKind.DIRECT, direct.kind)
        assertFalse(direct.needsProcessing)
        val waitingVideo = resolveDesktopNvidiaVideoDecision(0, 2160, 1920, 1080, 16384, "bt.1886", null, false, budget = tiny)
        assertEquals(DesktopNvidiaVideoDecisionKind.WAITING_VIDEO, waitingVideo.kind)
        val waitingLimit = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null, "bt.1886", null, false, budget = tiny)
        assertEquals(DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT, waitingLimit.kind)
        val nativeHdrOnly = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384, "pq", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, sharedCoreNativeResolutionAvailable = true, srEnabled = false, budget = tiny)
        assertEquals(DesktopNvidiaVideoDecisionKind.DIRECT, nativeHdrOnly.kind)
        assertTrue(nativeHdrOnly.sourceIsHdr)
    }

    @Test fun overBudgetNeverNeedsProcessingAndKeepsTheSourceColourFact() {
        val over = DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.OVER_BUDGET)
        assertFalse(over.needsProcessing)
        assertEquals(1.0, over.scale)
        assertFalse(over.hdr)
        assertFalse(over.nativeResolutionProcessing)
        assertFalse(over.isUpscale)
        // A native HDR source that would be upscaled beyond the SR box stays marked as an HDR source.
        val hdrSource = resolveDesktopNvidiaVideoDecision(3840, 2160, 7680, 4320, 16384, "pq", null, false, budget = ultra())
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, hdrSource.kind)
        assertTrue(hdrSource.sourceIsHdr)
        assertFalse(hdrSource.needsProcessing)
    }

    @Test fun onlyTheTwoUpscaleKindsAreUpscales() {
        for (kind in DesktopNvidiaVideoDecisionKind.entries) {
            val expected = kind == DesktopNvidiaVideoDecisionKind.UPSCALE || kind == DesktopNvidiaVideoDecisionKind.UPSCALE_AND_HDR
            assertEquals(expected, DesktopNvidiaVideoDecision(kind, scale = 2.0).isUpscale, kind.name)
        }
    }

    @Test fun nativeSizeSourcesAreCheckedAgainstTheNativeSizeCapAndTheRate() {
        fun nativeUhd(budget: DesktopNvidiaVideoBudget?) = resolveDesktopNvidiaVideoDecision(3840, 2160, 1920, 1080, 16384,
            "bt.1886", null, false, sharedCoreNativeResolutionAvailable = true, budget = budget)
        assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, nativeUhd(null).kind)
        assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, nativeUhd(ultra()).kind)
        // 4K60 is 497.7 Mpix/s: inside a rate of 500, outside 350.
        assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, nativeUhd(highTi()).kind)
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, nativeUhd(high()).kind)
        // The frame rate is part of the rate: 4K30 fits the same 350 budget.
        assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, nativeUhd(high(30.0)).kind)
        // Beyond the native-size cap, and when a failed extended range lowered the cap to the SR box.
        val eightK = resolveDesktopNvidiaVideoDecision(7680, 4320, 3840, 2160, 16384, "bt.1886", null, false,
            sharedCoreNativeResolutionAvailable = true, budget = ultra())
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, eightK.kind)
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, nativeUhd(ultra(extendedRangeDenied = true)).kind)
        // Native-size with HDR uses the HDR factor: 622 Mpix/s fits 1000, not 500.
        fun nativeUhdHdr(budget: DesktopNvidiaVideoBudget?) = resolveDesktopNvidiaVideoDecision(3840, 2160, 1920, 1080, 16384,
            "srgb", null, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO,
            sharedCoreNativeResolutionAvailable = true, budget = budget)
        assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION_AND_HDR, nativeUhdHdr(ultra()).kind)
        assertTrue(nativeUhdHdr(ultra()).hdr)
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, nativeUhdHdr(highTi()).kind)
    }

    @Test fun hdrOnlyUsesTheHdrOnlyCapAndTheHdrRate() {
        fun hdrOnly(width: Int, height: Int, budget: DesktopNvidiaVideoBudget?) = resolveDesktopNvidiaVideoDecision(width, height,
            width, height, 16384, "bt.1886", null, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO,
            sharedCoreNativeResolutionAvailable = true, srEnabled = false, budget = budget)
        assertEquals(DesktopNvidiaVideoDecisionKind.HDR, hdrOnly(3840, 2160, ultra()).kind)
        assertTrue(hdrOnly(3840, 2160, ultra()).hdr)
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, hdrOnly(3840, 2160, highTi()).kind)
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, hdrOnly(3840, 2160, ultra(extendedRangeDenied = true)).kind)
        // 1080p60 with HDR is 155.5 Mpix/s: over the 125 of the light tier, inside it at 24 fps.
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, hdrOnly(1920, 1080, light()).kind)
        assertEquals(DesktopNvidiaVideoDecisionKind.HDR, hdrOnly(1920, 1080, light(24.0)).kind)
        // Unknown frame rate counts as 60.
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, hdrOnly(1920, 1080, light(null)).kind)
    }

    @Test fun anUpscaleIsTrimmedToTheBudgetScaleCeilingAndKeepsItsKind() {
        fun toUhd(budget: DesktopNvidiaVideoBudget?) = resolveDesktopNvidiaVideoDecision(1920, 1080,
            3840, 2160, 16384, "bt.1886", null, false, budget = budget)
        assertEquals(2.0, toUhd(null).scale)
        val top = toUhd(ultra())
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, top.kind)
        assertEquals(2.0, top.scale)
        // The 4070 rate of 350 allows 1.5x for 1080p60.
        val trimmed = toUhd(high())
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, trimmed.kind)
        assertEquals(1.5, trimmed.scale)
        assertFalse(trimmed.nativeResolutionProcessing)
        assertTrue(trimmed.isUpscale)
        // The frame rate matters: 1080p24 into 4K is not trimmed on the same budget, unknown counts as 60.
        assertEquals(2.0, toUhd(high(24.0)).scale)
        assertEquals(1.5, toUhd(high(null)).scale)
        // With HDR the kind is kept and the HDR rate factor applies.
        fun toUhdHdr(budget: DesktopNvidiaVideoBudget?) = resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, 16384,
            "bt.1886", null, true, "pq", "bt.2020", hdrMode = DesktopNvidiaVideoHdrMode.AUTO, budget = budget)
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE_AND_HDR, toUhdHdr(ultra()).kind)
        assertEquals(2.0, toUhdHdr(ultra()).scale)
        val hdrTrimmed = toUhdHdr(high())
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE_AND_HDR, hdrTrimmed.kind)
        assertTrue(hdrTrimmed.hdr)
        assertEquals(1.5, hdrTrimmed.scale)
    }

    @Test fun aScaleTrimmedToExactlyOneOrAnInputBeyondTheBoxIsOverBudget() {
        // The light tier cannot output more than 1080p, so 1080p into a 1440p rectangle has no scale that fits.
        val noScaleFits = resolveDesktopNvidiaVideoDecision(1920, 1080, 2560, 1440, 16384, "bt.1886", null, false, budget = light())
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, noScaleFits.kind)
        assertFalse(noScaleFits.needsProcessing)
        assertEquals(1.0, noScaleFits.scale)
        // A 4K input into an 8K rectangle needs an upscale, and 4K is beyond the SR box of 3440x1440.
        val beyondBox = resolveDesktopNvidiaVideoDecision(3840, 2160, 7680, 4320, 16384, "bt.1886", null, false, budget = ultra())
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, beyondBox.kind)
        // The same upscale is inside the box when the input is 3440x1440.
        val insideBox = resolveDesktopNvidiaVideoDecision(3440, 1440, 6880, 2880, 16384, "bt.1886", null, false, budget = ultra())
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, insideBox.kind)
    }

    @Test fun theTextureLimitStillBoundsABudgetedUpscale() {
        val limited = resolveDesktopNvidiaVideoDecision(1920, 1080, 7680, 4320, 2880, "bt.1886", null, false, budget = ultra())
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, limited.kind)
        assertEquals(1.5, limited.scale)
        assertEquals(DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT,
            resolveDesktopNvidiaVideoDecision(1920, 1080, 3840, 2160, null, "bt.1886", null, false, budget = ultra()).kind)
    }

    @Test fun theStableBucketDefaultCeilingChangesNothingAndABudgetCeilingOnlyLowersIt() {
        for (requested in listOf(1.05, 2.01, 2.26, 3.7, 5.0)) {
            assertEquals(nvidiaStableUpscale(requested, 1920, 1080, 16384),
                nvidiaStableUpscale(requested, 1920, 1080, 16384, 4.0), "$requested")
        }
        assertEquals(2.25, nvidiaStableUpscale(2.01, 1920, 1080, 16384, 2.5))
        assertEquals(2.0, nvidiaStableUpscale(2.01, 1920, 1080, 16384, 2.0))
        assertEquals(1.0, nvidiaStableUpscale(2.01, 1920, 1080, 16384, 0.5), "a ceiling below one never undersizes")
        assertEquals(1.0, nvidiaStableUpscale(2.01, 1920, 1080, 16384, Double.NaN))
        // The texture limit stays the tighter bound when it is lower.
        assertEquals(2.1, nvidiaStableUpscale(2.01, 1000, 600, 2100, 4.0))
        assertEquals(2.05, nvidiaStableUpscale(2.01, 1000, 600, 2100, 2.05))
        // Not an upscale: nothing changes.
        assertEquals(1.0, nvidiaStableUpscale(1.0, 1920, 1080, 16384, 2.0))
    }

    // ---- Request scale: the budget ceiling and the upscale-only sticky floor ----

    private fun coreOptions(scale: Double, hdr: Boolean = false, nativeResolution: Boolean = false, sr: Boolean = true) =
        NvidiaVideoOptions(scale, hdr, nativeResolution, NvidiaVideoBackend.VEYRA_CORE, srEnabled = sr)

    private val upscaleDecision = DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.UPSCALE, scale = 4.0 / 3.0)

    @Test fun withoutABudgetTheRequestScaleIsTheStableBucketAndTheFloorIsIgnored() {
        val sized = nvidiaRequestScale(coreOptions(4.0 / 3.0), upscaleDecision, null, 1920, 1080, 16384, 2.0)
        assertEquals(1.5, sized.scale)
        assertFalse(sized.stickyApplied)
    }

    @Test fun aStickyTierRaisesAnUpscaleToTheFloorAndClampsItToTheBudgetCeiling() {
        val options = coreOptions(4.0 / 3.0)
        val first = nvidiaRequestScale(options, upscaleDecision, ultra(), 1920, 1080, 16384, null)
        assertEquals(1.5, first.scale)
        assertTrue(first.stickyApplied)
        assertEquals(2.0, nvidiaRequestScale(options, upscaleDecision, ultra(), 1920, 1080, 16384, 2.0).scale)
        assertEquals(1.5, nvidiaRequestScale(options, upscaleDecision, ultra(), 1920, 1080, 16384, 1.25).scale, "the floor never lowers")
        // The floor stays under the budget ceiling (the 14.7 Mpx output cap is 8/3 for 1080p).
        assertEquals(8.0 / 3.0, nvidiaRequestScale(options, upscaleDecision, ultra(), 1920, 1080, 16384, 3.5).scale, 1e-9)
        // A tighter budget clamps the floor too: the 4070 ceiling for 1080p60 is 1.5.
        assertEquals(1.5, nvidiaRequestScale(options, upscaleDecision, high(), 1920, 1080, 16384, 2.0).scale)
    }

    @Test fun theFloorNeverTouchesNativeSizeHdrOnlyOrDirectRequests() {
        val floor = 2.0
        val nativeDecision = DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, nativeResolutionProcessing = true)
        val nativeSized = nvidiaRequestScale(coreOptions(1.0, nativeResolution = true), nativeDecision, ultra(), 3840, 2160, 16384, floor)
        assertEquals(1.0, nativeSized.scale)
        assertFalse(nativeSized.stickyApplied)
        val hdrOnlyDecision = DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.HDR, hdr = true)
        val hdrSized = nvidiaRequestScale(coreOptions(1.0, hdr = true, sr = false), hdrOnlyDecision, ultra(), 1920, 1080, 16384, floor)
        assertEquals(1.0, hdrSized.scale)
        assertFalse(hdrSized.stickyApplied)
        // Unity HDR with super resolution on is not an upscale either, so no floor can reach it.
        val unityHdr = nvidiaRequestScale(coreOptions(1.0, hdr = true), hdrOnlyDecision, ultra(), 1920, 1080, 16384, floor)
        assertEquals(1.0, unityHdr.scale)
        assertFalse(unityHdr.stickyApplied)
        val direct = nvidiaRequestScale(coreOptions(1.0), DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.DIRECT),
            ultra(), 1920, 1080, 16384, floor)
        assertEquals(1.0, direct.scale)
        assertFalse(direct.stickyApplied)
        // The native contract accepts each of these requests as it is.
        coreOptions(nativeSized.scale, nativeResolution = true).requireValid()
        coreOptions(hdrSized.scale, hdr = true, sr = false).requireValid()
        coreOptions(unityHdr.scale, hdr = true).requireValid()
    }

    @Test fun tiersThatFollowTheDisplayHaveNoFloor() {
        val follows = buildBudget(NvidiaTier.BALANCED, sku(3060, NvidiaSkuClass.B, 1.0), 30.0)
        assertEquals(NvidiaScalePolicy.FOLLOWS_DISPLAY, follows.scalePolicy)
        val decision = DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.UPSCALE, scale = 4.0 / 3.0)
        val sized = nvidiaRequestScale(coreOptions(4.0 / 3.0), decision, follows, 1280, 720, 16384, 2.0)
        assertEquals(1.5, sized.scale)
        assertFalse(sized.stickyApplied)
    }
}
