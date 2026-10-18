package com.bilipai.desktop.nvidia

import com.bilipai.desktop.player.DesktopNvidiaVideoDecision
import com.bilipai.desktop.player.DesktopNvidiaVideoDecisionKind
import com.bilipai.desktop.player.DesktopNvidiaVideoHdrMode
import com.bilipai.desktop.player.DesktopNvidiaVideoPreferences
import com.bilipai.desktop.player.NvidiaVideoBackend
import com.bilipai.desktop.player.NvidiaVideoOptions
import com.bilipai.desktop.player.nvidiaEffectiveHdrMode
import com.bilipai.desktop.player.nvidiaRequestScale
import com.bilipai.desktop.player.optionsFor
import com.bilipai.desktop.player.resolveDesktopNvidiaVideoDecision
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNvidiaTierProfileTest {
    private val vectors = NvidiaGoldenVectors

    private fun gpuVector(id: String): JsonObject = vectors.objects("gpus").first { vectors.string(it, "id") == id }

    private fun planFor(id: String): NvidiaPlan {
        val capable = assertIs<NvidiaCapability.Capable>(classifyNvidiaCapability(vectors.gpuInput(gpuVector(id))), id)
        return buildNvidiaPlan(capable)
    }

    private fun budgetFor(id: String, frameRate: Double? = 60.0): DesktopNvidiaVideoBudget = planFor(id).budget(frameRate)

    // ---- table shape ----------------------------------------------------------------------

    @Test fun tierTableIsMonotoneAndNeverExceedsTheNativeScaleCeiling() {
        assertEquals(NvidiaTier.entries.toList(), NVIDIA_TIER_PROFILES.map { it.tier }, "one row per tier, best first")
        for (profile in NVIDIA_TIER_PROFILES) {
            assertTrue(profile.qualityCeiling in 1..4, "${profile.tier} quality")
            assertTrue(profile.maxScale >= 1.0 && profile.maxScale <= NVIDIA_ABSOLUTE_MAX_SCALE, "${profile.tier} scale")
            assertEquals(profile.nativeSizeMaxInputPixels, profile.hdrOnlyMaxInputPixels, "HDR-only cap equals the native-size cap in v1")
            assertTrue(
                profile.nativeSizeMaxInputPixels >= profile.srInputMaxWidth.toLong() * profile.srInputMaxHeight.toLong(),
                "${profile.tier} native-size cap covers the SR box",
            )
            assertTrue(profile.hdrOnlyAllowed, "${profile.tier} HDR-only")
        }
        for (index in 1 until NVIDIA_TIER_PROFILES.size) {
            val better = NVIDIA_TIER_PROFILES[index - 1]
            val lower = NVIDIA_TIER_PROFILES[index]
            assertTrue(better.qualityCeiling >= lower.qualityCeiling, "quality ${lower.tier}")
            assertTrue(better.maxScale >= lower.maxScale, "scale ${lower.tier}")
            assertTrue(better.nativeSizeMaxInputPixels >= lower.nativeSizeMaxInputPixels, "native ${lower.tier}")
            assertTrue(better.maxOutputPixels >= lower.maxOutputPixels, "output ${lower.tier}")
            assertTrue(better.rateBaseMpixPerSecond >= lower.rateBaseMpixPerSecond, "rate ${lower.tier}")
            assertTrue(better.srInputMaxWidth * better.srInputMaxHeight >= lower.srInputMaxWidth * lower.srInputMaxHeight, "box ${lower.tier}")
        }
    }

    @Test fun tierTableHoldsTheDocumentedValues() {
        val ultra = nvidiaTierProfile(NvidiaTier.ULTRA)
        assertEquals(4, ultra.qualityCeiling)
        assertEquals(4.0, ultra.maxScale)
        assertEquals(1000.0, ultra.rateBaseMpixPerSecond)
        assertEquals(8_847_360L, ultra.nativeSizeMaxInputPixels)
        assertEquals(14_745_600L, ultra.maxOutputPixels)
        assertEquals(NvidiaScalePolicy.STICKY_UP, ultra.scalePolicy)
        assertEquals(NvidiaMpvScalerClass.HQ_ULTRA, ultra.mpvScaler)
        val light = nvidiaTierProfile(NvidiaTier.LIGHT)
        assertEquals(2, light.qualityCeiling)
        assertFalse(light.hdrWithSrAllowed)
        assertEquals(2560, light.srInputMaxWidth)
        assertEquals(1080, light.srInputMaxHeight)
        assertEquals(NvidiaScalePolicy.FOLLOWS_DISPLAY, light.scalePolicy)
        assertEquals(1.25, HDR_RATE_FACTOR)
        assertEquals(60.0, NVIDIA_UNKNOWN_FRAME_RATE)
    }

    @Test fun noSkuOtherThanTheTopThreeExceedFiveHundredMegapixelsPerSecond() {
        val tierByClass = mapOf(
            NvidiaSkuClass.S to NvidiaTier.ULTRA, NvidiaSkuClass.A to NvidiaTier.HIGH, NvidiaSkuClass.B to NvidiaTier.BALANCED,
            NvidiaSkuClass.C to NvidiaTier.LIGHT, NvidiaSkuClass.D to NvidiaTier.LIGHT,
        )
        val topThree = setOf(5090, 5080, 4090)
        val budgetsByClass = HashMap<NvidiaSkuClass, MutableList<Double>>()
        for (row in NVIDIA_SKU_ROWS) {
            val tier = requireNotNull(tierByClass[row.skuClass])
            val rate = nvidiaTierProfile(tier).rateBaseMpixPerSecond * row.costFactor
            if (row.model !in topThree) assertTrue(rate <= 500.0, "${row.model} ${row.ti} $rate")
            budgetsByClass.getOrPut(row.skuClass) { ArrayList() }.add(rate)
        }
        val order = listOf(NvidiaSkuClass.S, NvidiaSkuClass.A, NvidiaSkuClass.B, NvidiaSkuClass.C, NvidiaSkuClass.D)
        for (index in 1 until order.size) {
            val better = requireNotNull(budgetsByClass[order[index - 1]]).minOrNull()!!
            val lower = requireNotNull(budgetsByClass[order[index]]).maxOrNull()!!
            assertTrue(better >= lower, "a lower class never gets a bigger budget: ${order[index]}")
        }
    }

    // ---- plan -----------------------------------------------------------------------------

    @Test fun thisPcPlanIsUltraWithFullQualityAndHdr() {
        val plan = planFor("rtx-5080-this-pc")
        assertEquals(NvidiaTier.ULTRA, plan.tier)
        assertEquals(4, plan.tierQuality)
        assertTrue(plan.hdrAllowedFor(true))
        assertTrue(plan.hdrAllowedFor(false))
        assertFalse(plan.mobile)
        assertFalse(plan.crossAdapter)
        assertEquals("10de:2c02:10431234:a1", plan.gpuKey)
    }

    @Test fun lightTierDeniesHdrWithSrButKeepsHdrOnly() {
        val plan = planFor("rtx-3060")
        assertEquals(NvidiaTier.LIGHT, plan.tier)
        assertFalse(plan.hdrAllowedFor(true))
        assertTrue(plan.hdrAllowedFor(false))
    }

    @Test fun oldDriverClosesTheHdrGateForEveryTierButNotSr() {
        val plan = planFor("rtx-4070-driver-550-no-hdr")
        assertFalse(plan.driverHdrOk)
        assertFalse(plan.hdrAllowedFor(true))
        assertFalse(plan.hdrAllowedFor(false))
        assertEquals(4, plan.tierQuality)
        assertTrue(planFor("rtx-4070-driver-5123-hdr-boundary").hdrAllowedFor(true))
    }

    @Test fun classDStartsAtQualityOneEvenOnTheLightTier() {
        assertEquals(1, planFor("rtx-3050-class-d").tierQuality)
        assertEquals(1, planFor("rtx-2050-class-d").tierQuality)
        assertEquals(2, planFor("rtx-2060").tierQuality)
        assertEquals(3, planFor("rtx-3060-ti").tierQuality)
        assertEquals(4, planFor("rtx-4070").tierQuality)
    }

    @Test fun mobileAndCrossAdapterStepsLowerTheQualityCeilingToo() {
        val laptop = planFor("rtx-5080-laptop")
        assertEquals(NvidiaTier.HIGH, laptop.tier)
        assertTrue(laptop.mobile)
        assertEquals(3, planFor("rtx-4070-laptop").tierQuality)
        val scenario = vectors.objects("scenarios").first { vectors.string(it, "id") == "cross-adapter-display-elsewhere" }
        val capable = assertIs<NvidiaCapability.Capable>(classifyNvidiaCapability(vectors.scenarioInput(scenario)))
        val plan = buildNvidiaPlan(capable)
        assertTrue(plan.crossAdapter)
        assertEquals(NvidiaTier.HIGH, plan.tier)
    }

    // ---- budget and rate math -------------------------------------------------------------

    @Test fun ultraRatesMatchTheDocumentedExamples() {
        val budget = budgetFor("rtx-5080-this-pc")
        assertEquals(1000.0, budget.rateMpixPerSecond)
        // 1080p60 into a 2560x1440 rectangle: bucket 1.5, output 2880x1620.
        assertEquals(279.936, budget.outputRate(2880.0, 1620.0, false), 1e-9)
        assertEquals(349.92, budget.outputRate(2880.0, 1620.0, true), 1e-9)
        // 720p30 fullscreen into 2560x1440 is scale 2.0.
        val slow = budgetFor("rtx-5080-this-pc", 30.0)
        assertEquals(110.592, slow.outputRate(2560.0, 1440.0, false), 1e-9)
        assertTrue(budget.withinRate(1920, 1080, 1.5, true))
        assertEquals(0.0, budget.outputRate(0.0, 1080.0, false))
    }

    @Test fun skuCostFactorScalesTheTierRateBase() {
        assertEquals(500.0, budgetFor("rtx-5070-ti-16g").rateMpixPerSecond)
        assertEquals(500.0, budgetFor("rtx-4080").rateMpixPerSecond)
        assertEquals(350.0, budgetFor("rtx-4070").rateMpixPerSecond)
        assertEquals(500.0, budgetFor("rtx-4070-ti").rateMpixPerSecond)
        assertEquals(75.0, budgetFor("rtx-3050-class-d").rateMpixPerSecond)
        assertEquals(175.0, budgetFor("unknown-id-8g").rateMpixPerSecond)
        assertEquals(1000.0, budgetFor("rtx-4090").rateMpixPerSecond)
    }

    @Test fun scaleCapRoundsTheRateTermDownToAQuarterThenClamps() {
        val ultra = budgetFor("rtx-5080-this-pc")
        // Rate term 2.83 floors to 2.75; the 14.7 Mpx output cap (8/3) is lower still.
        assertEquals(8.0 / 3.0, ultra.scaleCap(1920, 1080, false, 16384), 1e-9)
        // With HDR the rate term is 2.54 and floors to 2.5, below the output cap.
        assertEquals(2.5, ultra.scaleCap(1920, 1080, true, 16384), 1e-9)
        // 720p: the rate term is large, the 4x ceiling decides.
        assertEquals(4.0, ultra.scaleCap(1280, 720, false, 16384), 1e-9)
        val seventy = budgetFor("rtx-4070")
        assertEquals(1.5, seventy.scaleCap(1920, 1080, false, 16384), 1e-9)
        assertEquals(1.5, seventy.scaleCap(1920, 1080, true, 16384), 1e-9)
        assertEquals(2.0, budgetFor("rtx-3060-ti").scaleCap(1280, 720, false, 16384), 1e-9)
    }

    @Test fun scaleCapHonoursTextureLimitTierMaximumAndNeverDropsBelowOne() {
        val ultra = budgetFor("rtx-5080-this-pc")
        assertEquals(2.0, ultra.scaleCap(1280, 720, false, 2560), 1e-9)
        assertEquals(1.0, ultra.scaleCap(1920, 1080, false, null))
        assertEquals(1.0, ultra.scaleCap(1920, 1080, false, 0))
        assertEquals(1.0, ultra.scaleCap(0, 1080, false, 16384))
        val balanced = budgetFor("rtx-3060-ti")
        assertEquals(3.0, balanced.scaleCap(640, 360, false, 16384), 1e-9)
        assertEquals(1.0, budgetFor("rtx-4070").scaleCap(3840, 2160, true, 16384), 1e-9)
    }

    @Test fun overBudgetSourcesAreDetectedByTheRateAtNoScale() {
        val ultra = budgetFor("rtx-5080-this-pc")
        // 4K60 HDR-only is 622 Mpix/s: inside the 1000 budget of the top tier.
        assertEquals(622.08, ultra.outputRate(3840.0, 2160.0, true), 1e-9)
        assertTrue(ultra.withinRate(3840, 2160, 1.0, true))
        val seventy = budgetFor("rtx-4070")
        assertFalse(seventy.withinRate(3840, 2160, 1.0, true))
        assertFalse(seventy.withinRate(3840, 2160, 1.0, false), "4K60 is 498 Mpix/s against 350")
        assertTrue(seventy.withinRate(1920, 1080, 1.0, true))
        assertFalse(ultra.withinRate(0, 1080, 1.0, false))
        assertFalse(ultra.withinRate(1920, 1080, 0.5, false))
    }

    @Test fun unknownFrameRateCountsAsSixty() {
        val unknown = budgetFor("rtx-5080-this-pc", null)
        assertEquals(60.0, unknown.effectiveFrameRate())
        assertEquals(60.0, nvidiaEffectiveFrameRate(Double.NaN))
        assertEquals(60.0, nvidiaEffectiveFrameRate(Double.POSITIVE_INFINITY))
        assertEquals(60.0, nvidiaEffectiveFrameRate(0.0))
        assertEquals(60.0, nvidiaEffectiveFrameRate(-24.0))
        assertEquals(23.976, nvidiaEffectiveFrameRate(23.976))
        assertEquals(unknown.scaleCap(1920, 1080, false, 16384), budgetFor("rtx-5080-this-pc", 60.0).scaleCap(1920, 1080, false, 16384))
    }

    @Test fun floorQuarterRoundsDownToQuarters() {
        assertEquals(1.75, floorQuarter(1.99))
        assertEquals(2.0, floorQuarter(2.0))
        assertEquals(2.25, floorQuarter(2.26))
        assertEquals(0.75, floorQuarter(0.8))
        assertEquals(1.0, floorQuarter(Double.NaN))
    }

    // ---- input ranges ---------------------------------------------------------------------

    @Test fun srInputBoxIsUltrawideSafeAndAcceptsPortrait() {
        val ultra = budgetFor("rtx-5080-this-pc")
        assertTrue(ultra.withinSrInputBox(1920, 1080))
        assertTrue(ultra.withinSrInputBox(3440, 1440))
        assertFalse(ultra.withinSrInputBox(3841, 1440))
        assertFalse(ultra.withinSrInputBox(3840, 2160))
        assertTrue(ultra.withinSrInputBox(1080, 1920), "portrait uses the rotated box")
        assertTrue(ultra.withinSrInputBox(1440, 3440))
        assertFalse(ultra.withinSrInputBox(1441, 3440))
        assertFalse(ultra.withinSrInputBox(0, 1080))
        val light = budgetFor("rtx-3060")
        assertTrue(light.withinSrInputBox(2560, 1080))
        assertFalse(light.withinSrInputBox(2561, 1080))
        assertFalse(light.withinSrInputBox(2560, 1440))
    }

    @Test fun nativeSizeCapsLetTheTopTiersTakeFourK() {
        val ultra = budgetFor("rtx-5080-this-pc")
        assertTrue(ultra.withinNativeSizeCap(3840, 2160))
        assertTrue(ultra.withinNativeSizeCap(4096, 2160))
        assertFalse(ultra.withinNativeSizeCap(7680, 4320))
        assertTrue(ultra.withinHdrOnlyCap(3840, 2160))
        assertTrue(ultra.inExtendedRange(3840, 2160))
        assertFalse(ultra.inExtendedRange(1920, 1080), "inside the box is not extended")
        assertFalse(ultra.inExtendedRange(7680, 4320), "beyond the native-size cap is not extended either")
        val balanced = budgetFor("rtx-3060-ti")
        assertFalse(balanced.withinNativeSizeCap(3840, 2160))
        assertFalse(balanced.inExtendedRange(3840, 2160))
        assertTrue(balanced.withinNativeSizeCap(3440, 1440))
        val light = budgetFor("rtx-3060")
        assertFalse(light.withinNativeSizeCap(3440, 1440))
    }

    @Test fun deniedExtendedRangeLowersTheNativeSizeCapsToTheDocumentedBox() {
        val capable = assertIs<NvidiaCapability.Capable>(classifyNvidiaCapability(vectors.gpuInput(gpuVector("rtx-5080-this-pc"))))
        val plan = buildNvidiaPlan(capable)
        val denied = plan.budget(60.0, extendedRangeDenied = true)
        assertTrue(denied.extendedRangeDenied)
        assertEquals(4_953_600L, denied.nativeSizeMaxInputPixels)
        assertEquals(4_953_600L, denied.hdrOnlyMaxInputPixels)
        assertFalse(denied.withinNativeSizeCap(3840, 2160))
        assertFalse(denied.inExtendedRange(3840, 2160))
        assertTrue(denied.withinNativeSizeCap(3440, 1440))
        val normal = plan.budget(60.0)
        assertFalse(normal.extendedRangeDenied)
        assertEquals(8_847_360L, normal.nativeSizeMaxInputPixels)
    }

    @Test fun budgetCostFactorIsClampedAndUnknownSkuUsesTheUnknownFactor() {
        val tooGenerous = NvidiaSkuInfo(1, NvidiaSkuClass.S, 2.0, null, false)
        assertEquals(1000.0, buildBudget(NvidiaTier.ULTRA, tooGenerous, 60.0).rateMpixPerSecond)
        assertEquals(1000.0 * NVIDIA_UNKNOWN_MODEL_COST_FACTOR, buildBudget(NvidiaTier.ULTRA, null, 60.0).rateMpixPerSecond, 1e-9)
        assertEquals(0.0, buildBudget(NvidiaTier.ULTRA, NvidiaSkuInfo(1, NvidiaSkuClass.S, -1.0, null, false), 60.0).rateMpixPerSecond)
    }

    // ---- sticky floor (upscale only) ------------------------------------------------------

    @Test fun stickyFloorRaisesTheBucketOnlyForUpscaleOnStickyTiers() {
        val ultra = budgetFor("rtx-5080-this-pc")
        assertEquals(2.0, nvidiaStickyUpscale(ultra, 1.5, 2.0, true, 4.0))
        assertEquals(1.5, nvidiaStickyUpscale(ultra, 1.5, null, true, 4.0))
        assertEquals(2.0, nvidiaStickyUpscale(ultra, 2.0, 1.5, true, 4.0), "the floor never lowers a larger bucket")
        // Not an upscale decision: native-size, HDR-only and direct decisions keep their exact value.
        assertEquals(1.0, nvidiaStickyUpscale(ultra, 1.0, 2.0, false, 4.0))
        assertEquals(1.5, nvidiaStickyUpscale(ultra, 1.5, 2.0, false, 4.0))
        assertEquals(1.0, nvidiaStickyUpscale(ultra, 1.0, 2.0, true, 4.0))
        val balanced = budgetFor("rtx-3060-ti")
        assertEquals(1.5, nvidiaStickyUpscale(balanced, 1.5, 2.0, true, 4.0), "follows-display tiers have no floor")
    }

    @Test fun stickyFloorIsStillClampedByTheBudgetCapButNeverBelowOne() {
        val ultra = budgetFor("rtx-5080-this-pc")
        assertEquals(1.25, nvidiaStickyUpscale(ultra, 1.5, 2.0, true, 1.25))
        assertEquals(1.0, nvidiaStickyUpscale(ultra, 1.5, 2.0, true, 0.5))
        assertEquals(2.5, nvidiaStickyUpscale(ultra, 1.5, 3.0, true, 2.5))
    }

    @Test fun stickyFloorAdvancesWithinASourceAndResetsOtherwise() {
        var state = NvidiaStickyFloor()
        assertNull(nvidiaStickyFloorFor(state, "a"))
        state = nvidiaAdvanceStickyFloor(state, "a", true, 1.5)
        assertEquals(1.5, nvidiaStickyFloorFor(state, "a"))
        state = nvidiaAdvanceStickyFloor(state, "a", true, 2.0)
        assertEquals(2.0, nvidiaStickyFloorFor(state, "a"))
        state = nvidiaAdvanceStickyFloor(state, "a", true, 1.25)
        assertEquals(2.0, nvidiaStickyFloorFor(state, "a"), "the floor is the highest bucket already used")
        assertNull(nvidiaStickyFloorFor(state, "b"), "another source has no floor")
        val other = nvidiaAdvanceStickyFloor(state, "b", true, 1.5)
        assertEquals(1.5, nvidiaStickyFloorFor(other, "b"))
        val direct = nvidiaAdvanceStickyFloor(state, "a", false, 1.0)
        assertEquals(NvidiaStickyFloor(), direct)
        assertEquals(NvidiaStickyFloor(), nvidiaAdvanceStickyFloor(state, "a", true, 1.0))
    }

    // ---- Resolver, request scale and sticky floor with real plans (slice S4) ----

    private fun sdrDecision(budget: DesktopNvidiaVideoBudget?, inputWidth: Int, inputHeight: Int,
        displayWidth: Int, displayHeight: Int, sharedCore: Boolean = false) =
        resolveDesktopNvidiaVideoDecision(inputWidth, inputHeight, displayWidth, displayHeight, 16384,
            "bt.1886", null, false, sharedCoreNativeResolutionAvailable = sharedCore, budget = budget)

    private fun hdrOnlyDecision(budget: DesktopNvidiaVideoBudget?, width: Int, height: Int) =
        resolveDesktopNvidiaVideoDecision(width, height, width, height, 16384, "bt.1886", null, true, "pq", "bt.2020",
            hdrMode = DesktopNvidiaVideoHdrMode.AUTO, sharedCoreNativeResolutionAvailable = true, srEnabled = false, budget = budget)

    private fun coreOptionsFor(decision: DesktopNvidiaVideoDecision) = NvidiaVideoOptions(decision.scale, decision.hdr,
        decision.nativeResolutionProcessing, NvidiaVideoBackend.VEYRA_CORE)

    @Test fun thisPcExamplesResolveToTheDocumentedBucketsAndRates() {
        val budget = budgetFor("rtx-5080-this-pc", 60.0)
        // 1080p60 into the 2560x1440 video rectangle of the 5120x1440 panel: scale 1.333, bucket 1.5, output 2880x1620.
        val decision = sdrDecision(budget, 1920, 1080, 2560, 1440)
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, decision.kind)
        assertEquals(4.0 / 3.0, decision.scale, 1e-9)
        val sized = nvidiaRequestScale(coreOptionsFor(decision), decision, budget, 1920, 1080, 16384, null)
        assertEquals(1.5, sized.scale)
        assertEquals(279.936, budget.outputRate(1920.0 * sized.scale, 1080.0 * sized.scale, false), 1e-9)
        assertEquals(349.92, budget.outputRate(1920.0 * sized.scale, 1080.0 * sized.scale, true), 1e-9)
        // 1080p24 lands in the same bucket at a lower rate.
        val film = budgetFor("rtx-5080-this-pc", 24.0)
        val filmDecision = sdrDecision(film, 1920, 1080, 2560, 1440)
        assertEquals(1.5, nvidiaRequestScale(coreOptionsFor(filmDecision), filmDecision, film, 1920, 1080, 16384, null).scale)
        assertEquals(111.9744, film.outputRate(2880.0, 1620.0, false), 1e-9)
        // 720p30 fullscreen into 2560x1440: scale 2.0 and 110.6 Mpix/s.
        val slow = budgetFor("rtx-5080-this-pc", 30.0)
        val slowDecision = sdrDecision(slow, 1280, 720, 2560, 1440)
        assertEquals(2.0, slowDecision.scale)
        assertEquals(2.0, nvidiaRequestScale(coreOptionsFor(slowDecision), slowDecision, slow, 1280, 720, 16384, null).scale)
        assertEquals(110.592, slow.outputRate(2560.0, 1440.0, false), 1e-9)
    }

    @Test fun aFourKSourceIsJudgedByTheRateOfEachGpu() {
        fun nativeUhd(id: String) = sdrDecision(budgetFor(id, 60.0), 3840, 2160, 1920, 1080, sharedCore = true)
        // 4K60 at native size is 497.7 Mpix/s.
        for (id in listOf("rtx-5080-this-pc", "rtx-4090", "rtx-4080", "rtx-4070-ti")) {
            assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, nativeUhd(id).kind, id)
        }
        // A 0.7-factor 4070 (350), and tiers whose native-size cap is below 8.3 megapixels, pass it through.
        for (id in listOf("rtx-4070", "rtx-3060-ti", "rtx-3060")) {
            val decision = nativeUhd(id)
            assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, decision.kind, id)
            assertFalse(decision.needsProcessing, id)
        }
        // 4K60 HDR-only is 622 Mpix/s: the 5080 and 4090 take it, the 4080 (500) and the 4070 do not.
        for (id in listOf("rtx-5080-this-pc", "rtx-4090")) {
            assertEquals(DesktopNvidiaVideoDecisionKind.HDR, hdrOnlyDecision(budgetFor(id, 60.0), 3840, 2160).kind, id)
        }
        for (id in listOf("rtx-4080", "rtx-4070")) {
            assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, hdrOnlyDecision(budgetFor(id, 60.0), 3840, 2160).kind, id)
        }
    }

    @Test fun overBudgetOnlyAppearsForASourceThatNeededProcessing() {
        val light = budgetFor("rtx-3060", 60.0)
        // A video rectangle smaller than the source needs nothing, however large the source is.
        assertEquals(DesktopNvidiaVideoDecisionKind.DIRECT, sdrDecision(light, 7680, 4320, 960, 540).kind)
        // The same light tier trims what it can run and passes through what it cannot.
        val ok = sdrDecision(budgetFor("rtx-3060", 30.0), 1280, 720, 1920, 1080)
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, ok.kind)
        assertEquals(1.5, ok.scale)
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, sdrDecision(light, 1920, 1080, 2560, 1440).kind)
    }

    @Test fun theSrBoxIsUltrawideSafeInTheResolver() {
        val ultra = budgetFor("rtx-5080-this-pc", 60.0)
        val ultrawide = sdrDecision(ultra, 3440, 1440, 5160, 2160)
        assertEquals(DesktopNvidiaVideoDecisionKind.UPSCALE, ultrawide.kind)
        assertEquals(1.5, ultrawide.scale)
        // 4K is beyond the box: an upscale is not attempted.
        assertEquals(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, sdrDecision(ultra, 3840, 2160, 5760, 3240).kind)
    }

    @Test fun theFrameRateOfThePlanBudgetDecidesTheTrim() {
        val plan = planFor("rtx-4070")
        assertEquals(24.0, plan.budget(24.0).effectiveFrameRate())
        assertEquals(2.0, sdrDecision(plan.budget(24.0), 1920, 1080, 3840, 2160).scale)
        assertEquals(1.5, sdrDecision(plan.budget(60.0), 1920, 1080, 3840, 2160).scale)
        assertEquals(1.5, sdrDecision(plan.budget(null), 1920, 1080, 3840, 2160).scale)
    }

    @Test fun theLightTierDropsHdrBeforeTheResolverSoNoRequestNeedsNoFilter() {
        val plan = planFor("rtx-3060")
        val preferences = DesktopNvidiaVideoPreferences(enabled = true, hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
        val effective = nvidiaEffectiveHdrMode(preferences, plan)
        assertEquals(DesktopNvidiaVideoHdrMode.OFF, effective)
        fun unity(mode: DesktopNvidiaVideoHdrMode) = resolveDesktopNvidiaVideoDecision(1280, 720, 1280, 720, 16384, "bt.1886",
            null, true, "pq", "bt.2020", hdrMode = mode, budget = plan.budget(30.0))
        val withEffectiveMode = unity(effective)
        assertEquals(DesktopNvidiaVideoDecisionKind.DIRECT, withEffectiveMode.kind)
        assertFalse(withEffectiveMode.needsProcessing)
        // The HDR flag cannot be masked afterwards: unity HDR would become a request that needs no filter.
        val unmasked = unity(DesktopNvidiaVideoHdrMode.AUTO)
        assertEquals(DesktopNvidiaVideoDecisionKind.HDR, unmasked.kind)
        assertFalse(preferences.optionsFor(unmasked, NvidiaVideoBackend.VEYRA_CORE, plan).requiresFilter)
    }

    @Test fun aFullscreenToWindowTransitionKeepsTheBucketAndAnyDirectDecisionEndsTheFloor() {
        val budget = budgetFor("rtx-5080-this-pc", 60.0)
        var floor = NvidiaStickyFloor()
        fun step(displayWidth: Int, displayHeight: Int): Double {
            val decision = sdrDecision(budget, 1920, 1080, displayWidth, displayHeight)
            if (!decision.needsProcessing) {
                floor = NvidiaStickyFloor()
                return 1.0
            }
            val sized = nvidiaRequestScale(coreOptionsFor(decision), decision, budget, 1920, 1080, 16384,
                nvidiaStickyFloorFor(floor, "source-1"))
            floor = if (sized.stickyApplied) nvidiaAdvanceStickyFloor(floor, "source-1", true, sized.scale) else NvidiaStickyFloor()
            return sized.scale
        }
        assertEquals(1.5, step(2560, 1440), "window on the panel")
        assertEquals(2.0, step(3840, 2160), "fullscreen")
        assertEquals(2.0, step(2560, 1440), "back in the window: the same configuration is kept")
        assertEquals(2.0, step(2880, 1620), "a request of 1.5 still keeps the floor")
        assertEquals(1.0, step(960, 540), "smaller than the source: direct, the floor ends")
        assertEquals(1.5, step(2560, 1440), "a new run starts from the stable bucket")
        // Another source never inherits the floor.
        assertNull(nvidiaStickyFloorFor(floor, "source-2"))
    }

    @Test fun everyStepOfTheFloorSequenceIsAValidNativeRequest() {
        val budget = budgetFor("rtx-5080-this-pc", 60.0)
        val upscale = sdrDecision(budget, 1920, 1080, 2560, 1440)
        val sized = nvidiaRequestScale(coreOptionsFor(upscale), upscale, budget, 1920, 1080, 16384, 2.0)
        coreOptionsFor(upscale).copy(scale = sized.scale).requireValid()
        // After a floor, a native-size decision keeps unity scale and passes requireValid.
        val native = sdrDecision(budget, 1920, 1080, 1920, 1080, sharedCore = true)
        assertEquals(DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION, native.kind)
        val nativeSized = nvidiaRequestScale(coreOptionsFor(native), native, budget, 1920, 1080, 16384, 2.0)
        assertEquals(1.0, nativeSized.scale)
        coreOptionsFor(native).copy(scale = nativeSized.scale).requireValid()
    }
}
