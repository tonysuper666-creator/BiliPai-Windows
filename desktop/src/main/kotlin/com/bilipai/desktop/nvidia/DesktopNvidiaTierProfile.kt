package com.bilipai.desktop.nvidia

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Pure tier table, budget and scale math. No I/O, no clock. Every number in the table is a
// proposal that needs hardware calibration; the constants live in one marked block so a load
// failure elsewhere can never demote a GPU.
//
// Intensity stays the user's knob and the native presentation settings stay fixed: neither is a
// tier lever. The 4x scale ceiling is a hard contract of the native filter, so no tier may
// exceed it.

internal enum class NvidiaScalePolicy { STICKY_UP, FOLLOWS_DISPLAY }

/** Residual mpv scaler class for the optional scaler profile; the profile itself is a later slice. */
internal enum class NvidiaMpvScalerClass { HQ_ULTRA, HQ, MPV_DEFAULT }

internal data class NvidiaTierProfile(
    val tier: NvidiaTier,
    /** Highest NGX quality level the tier may request (1..4). */
    val qualityCeiling: Int,
    val maxScale: Double,
    /** SR input box for scale above 1: the long side against the long edge, the short side against the short edge. */
    val srInputMaxWidth: Int,
    val srInputMaxHeight: Int,
    /** Input pixel cap for native-size paths (1x SR through the native-resolution decision). */
    val nativeSizeMaxInputPixels: Long,
    /** Input pixel cap for HDR-only. Equal to the native-size cap in v1. */
    val hdrOnlyMaxInputPixels: Long,
    val maxOutputPixels: Long,
    /** Output megapixels per second at cost factor 1.0. */
    val rateBaseMpixPerSecond: Double,
    val hdrWithSrAllowed: Boolean,
    val hdrOnlyAllowed: Boolean,
    val scalePolicy: NvidiaScalePolicy,
    val mpvScaler: NvidiaMpvScalerClass,
)

/** The native filter accepts at most 4x. */
internal const val NVIDIA_ABSOLUTE_MAX_SCALE: Double = 4.0

/** Unknown frame rate is treated as 60. */
internal const val NVIDIA_UNKNOWN_FRAME_RATE: Double = 60.0

// TIER-TABLE-BEGIN
internal const val HDR_RATE_FACTOR: Double = 1.25

internal val NVIDIA_TIER_PROFILES: List<NvidiaTierProfile> = listOf(
    NvidiaTierProfile(
        tier = NvidiaTier.ULTRA,
        qualityCeiling = 4,
        maxScale = 4.0,
        srInputMaxWidth = 3440,
        srInputMaxHeight = 1440,
        nativeSizeMaxInputPixels = 8_847_360L,
        hdrOnlyMaxInputPixels = 8_847_360L,
        maxOutputPixels = 14_745_600L,
        rateBaseMpixPerSecond = 1000.0,
        hdrWithSrAllowed = true,
        hdrOnlyAllowed = true,
        scalePolicy = NvidiaScalePolicy.STICKY_UP,
        mpvScaler = NvidiaMpvScalerClass.HQ_ULTRA,
    ),
    NvidiaTierProfile(
        tier = NvidiaTier.HIGH,
        qualityCeiling = 4,
        maxScale = 4.0,
        srInputMaxWidth = 3440,
        srInputMaxHeight = 1440,
        nativeSizeMaxInputPixels = 8_847_360L,
        hdrOnlyMaxInputPixels = 8_847_360L,
        maxOutputPixels = 8_294_400L,
        rateBaseMpixPerSecond = 500.0,
        hdrWithSrAllowed = true,
        hdrOnlyAllowed = true,
        scalePolicy = NvidiaScalePolicy.STICKY_UP,
        mpvScaler = NvidiaMpvScalerClass.HQ,
    ),
    NvidiaTierProfile(
        tier = NvidiaTier.BALANCED,
        qualityCeiling = 3,
        maxScale = 3.0,
        srInputMaxWidth = 3440,
        srInputMaxHeight = 1440,
        nativeSizeMaxInputPixels = 4_953_600L,
        hdrOnlyMaxInputPixels = 4_953_600L,
        maxOutputPixels = 3_686_400L,
        rateBaseMpixPerSecond = 250.0,
        hdrWithSrAllowed = true,
        hdrOnlyAllowed = true,
        scalePolicy = NvidiaScalePolicy.FOLLOWS_DISPLAY,
        mpvScaler = NvidiaMpvScalerClass.MPV_DEFAULT,
    ),
    NvidiaTierProfile(
        tier = NvidiaTier.LIGHT,
        qualityCeiling = 2,
        maxScale = 2.0,
        srInputMaxWidth = 2560,
        srInputMaxHeight = 1080,
        nativeSizeMaxInputPixels = 2_764_800L,
        hdrOnlyMaxInputPixels = 2_764_800L,
        maxOutputPixels = 2_073_600L,
        rateBaseMpixPerSecond = 125.0,
        hdrWithSrAllowed = false,
        hdrOnlyAllowed = true,
        scalePolicy = NvidiaScalePolicy.FOLLOWS_DISPLAY,
        mpvScaler = NvidiaMpvScalerClass.MPV_DEFAULT,
    ),
)
// TIER-TABLE-END

internal fun nvidiaTierProfile(tier: NvidiaTier): NvidiaTierProfile = NVIDIA_TIER_PROFILES.first { it.tier == tier }

/** Unknown, non-finite or non-positive frame rates count as 60. */
internal fun nvidiaEffectiveFrameRate(frameRate: Double?): Double =
    if (frameRate == null || frameRate.isNaN() || frameRate.isInfinite() || frameRate <= 0.0) {
        NVIDIA_UNKNOWN_FRAME_RATE
    } else {
        frameRate
    }

/** Rounds down to a multiple of 0.25. */
internal fun floorQuarter(value: Double): Double = if (value.isNaN()) 1.0 else floor(value * 4.0) / 4.0

/**
 * The limits one source is checked against: tier caps with the SKU cost factor applied to the
 * rate. Built by buildBudget or NvidiaPlan.budget.
 */
internal data class DesktopNvidiaVideoBudget(
    val tier: NvidiaTier,
    val maxScale: Double,
    val srInputMaxWidth: Int,
    val srInputMaxHeight: Int,
    val nativeSizeMaxInputPixels: Long,
    val hdrOnlyMaxInputPixels: Long,
    val maxOutputPixels: Long,
    /** Tier rate base times the SKU cost factor, in output megapixels per second. */
    val rateMpixPerSecond: Double,
    /** Source frame rate; null when unknown, which counts as 60. */
    val frameRate: Double?,
    val hdrWithSrAllowed: Boolean,
    val hdrOnlyAllowed: Boolean,
    val scalePolicy: NvidiaScalePolicy,
    val extendedRangeDenied: Boolean,
) {
    fun effectiveFrameRate(): Double = nvidiaEffectiveFrameRate(frameRate)

    /** Inside the SR input box. Portrait sources fit the rotated box. */
    fun withinSrInputBox(inputWidth: Int, inputHeight: Int): Boolean {
        if (inputWidth <= 0 || inputHeight <= 0) return false
        val longSide = max(inputWidth, inputHeight)
        val shortSide = min(inputWidth, inputHeight)
        return longSide <= max(srInputMaxWidth, srInputMaxHeight) &&
            shortSide <= min(srInputMaxWidth, srInputMaxHeight)
    }

    fun withinNativeSizeCap(inputWidth: Int, inputHeight: Int): Boolean =
        inputWidth > 0 && inputHeight > 0 && inputWidth.toLong() * inputHeight.toLong() <= nativeSizeMaxInputPixels

    fun withinHdrOnlyCap(inputWidth: Int, inputHeight: Int): Boolean =
        inputWidth > 0 && inputHeight > 0 && inputWidth.toLong() * inputHeight.toLong() <= hdrOnlyMaxInputPixels

    /** Beyond the SR box but inside the native-size cap: attempted, and its failures are contained. */
    fun inExtendedRange(inputWidth: Int, inputHeight: Int): Boolean =
        !withinSrInputBox(inputWidth, inputHeight) && withinNativeSizeCap(inputWidth, inputHeight)

    /** Output megapixels per second, with the HDR factor when HDR is on. */
    fun outputRate(outputWidth: Double, outputHeight: Double, hdr: Boolean): Double {
        if (!(outputWidth > 0.0) || !(outputHeight > 0.0)) return 0.0
        val hdrFactor = if (hdr) HDR_RATE_FACTOR else 1.0
        return outputWidth * outputHeight * effectiveFrameRate() * hdrFactor / 1_000_000.0
    }

    /** True when processing the input at the given scale stays inside the rate budget. */
    fun withinRate(inputWidth: Int, inputHeight: Int, scale: Double, hdr: Boolean): Boolean {
        if (inputWidth <= 0 || inputHeight <= 0 || !(scale >= 1.0)) return false
        val rate = outputRate(inputWidth.toDouble() * scale, inputHeight.toDouble() * scale, hdr)
        return rate <= rateMpixPerSecond * (1.0 + 1e-9)
    }

    /**
     * Largest scale this source may use: the rate term rounded down to a quarter, then clamped by
     * the tier maximum, the 4x ceiling, the texture limit and the maximum output pixels. Never
     * below 1.0, so a result of exactly 1.0 means "no upscale fits"; the caller checks withinRate
     * at 1.0 to tell that apart from a source that is over budget even unscaled. An unknown texture
     * limit never licenses an upscale.
     */
    fun scaleCap(inputWidth: Int, inputHeight: Int, hdr: Boolean, maximumTextureDimension: Int?): Double {
        if (inputWidth <= 0 || inputHeight <= 0) return 1.0
        if (maximumTextureDimension == null || maximumTextureDimension <= 0) return 1.0
        val inputPixels = inputWidth.toDouble() * inputHeight.toDouble()
        val hdrFactor = if (hdr) HDR_RATE_FACTOR else 1.0
        val byRate = floorQuarter(
            sqrt(rateMpixPerSecond * 1_000_000.0 / (inputPixels * effectiveFrameRate() * hdrFactor)),
        )
        val byOutput = sqrt(maxOutputPixels.toDouble() / inputPixels)
        val byTexture = maximumTextureDimension.toDouble() / max(inputWidth, inputHeight).toDouble()
        val limit = min(min(byRate, byOutput), min(byTexture, min(maxScale, NVIDIA_ABSOLUTE_MAX_SCALE)))
        return max(1.0, limit)
    }
}

/** costFactor is clamped to 0..1; a null SKU is an unknown model. */
internal fun buildBudget(
    tier: NvidiaTier,
    sku: NvidiaSkuInfo?,
    frameRate: Double?,
    extendedRangeDenied: Boolean = false,
): DesktopNvidiaVideoBudget {
    val profile = nvidiaTierProfile(tier)
    val factor = (sku?.costFactor ?: NVIDIA_UNKNOWN_MODEL_COST_FACTOR).coerceIn(0.0, 1.0)
    val boxArea = profile.srInputMaxWidth.toLong() * profile.srInputMaxHeight.toLong()
    val nativeCap = if (extendedRangeDenied) min(profile.nativeSizeMaxInputPixels, boxArea) else profile.nativeSizeMaxInputPixels
    val hdrOnlyCap = if (extendedRangeDenied) min(profile.hdrOnlyMaxInputPixels, boxArea) else profile.hdrOnlyMaxInputPixels
    return DesktopNvidiaVideoBudget(
        tier = tier,
        maxScale = min(profile.maxScale, NVIDIA_ABSOLUTE_MAX_SCALE),
        srInputMaxWidth = profile.srInputMaxWidth,
        srInputMaxHeight = profile.srInputMaxHeight,
        nativeSizeMaxInputPixels = nativeCap,
        hdrOnlyMaxInputPixels = hdrOnlyCap,
        maxOutputPixels = profile.maxOutputPixels,
        rateMpixPerSecond = profile.rateBaseMpixPerSecond * factor,
        frameRate = frameRate,
        hdrWithSrAllowed = profile.hdrWithSrAllowed,
        hdrOnlyAllowed = profile.hdrOnlyAllowed,
        scalePolicy = profile.scalePolicy,
        extendedRangeDenied = extendedRangeDenied,
    )
}

/**
 * What one active capability allows. The session combines it with the user's choices:
 * quality is min(user ceiling, tierQuality, guard cap) and HDR needs hdrAllowedFor.
 */
internal data class NvidiaPlan(
    val tier: NvidiaTier,
    /** Tier quality ceiling after the SKU override (class D starts at 1). */
    val tierQuality: Int,
    val hdrWithSrAllowed: Boolean,
    val hdrOnlyAllowed: Boolean,
    /** False when the known driver is below the HDR floor; unknown drivers pass. */
    val driverHdrOk: Boolean,
    val sku: NvidiaSkuInfo,
    val gpuKey: String,
    val gpuDescription: String,
    val mobile: Boolean,
    val crossAdapter: Boolean,
    val notes: Set<NvidiaCapabilityNote>,
) {
    /** HDR conversion with SR (srEnabled) or HDR-only (not srEnabled). */
    fun hdrAllowedFor(srEnabled: Boolean): Boolean =
        driverHdrOk && (if (srEnabled) hdrWithSrAllowed else hdrOnlyAllowed)

    fun budget(frameRate: Double?, extendedRangeDenied: Boolean = false): DesktopNvidiaVideoBudget =
        buildBudget(tier, sku, frameRate, extendedRangeDenied)
}

internal fun buildNvidiaPlan(capable: NvidiaCapability.Capable): NvidiaPlan {
    val profile = nvidiaTierProfile(capable.tier)
    val qualityOverride = capable.sku.qualityCeilingOverride
    val quality = if (qualityOverride == null) profile.qualityCeiling else min(profile.qualityCeiling, qualityOverride)
    return NvidiaPlan(
        tier = capable.tier,
        tierQuality = quality.coerceIn(1, 4),
        hdrWithSrAllowed = profile.hdrWithSrAllowed,
        hdrOnlyAllowed = profile.hdrOnlyAllowed,
        driverHdrOk = nvidiaHdrDriverOk(capable.gpu.driverPublicX100),
        sku = capable.sku,
        gpuKey = capable.gpu.gpuKey,
        gpuDescription = capable.gpu.description,
        mobile = capable.gpu.mobile,
        crossAdapter = NvidiaCapabilityNote.CROSS_ADAPTER in capable.notes,
        notes = capable.notes,
    )
}

// ---- Sticky scale floor (upscale only) -------------------------------------------------

/** The highest upscale bucket already used for one source. A null scale means no floor. */
internal data class NvidiaStickyFloor(val sourceKey: String? = null, val scale: Double? = null)

/** The floor for this source, or null when none was recorded for it. */
internal fun nvidiaStickyFloorFor(state: NvidiaStickyFloor, sourceKey: String?): Double? =
    if (state.scale != null && state.sourceKey == sourceKey) state.scale else null

/**
 * scale = max(stable bucket, sticky floor), then clamped by budgetCap, never below 1.0. The clamp
 * may lower the result below the stable bucket but never raises it. The floor applies only to a
 * real upscale decision on a sticky tier; native-size, HDR-only and direct decisions keep the
 * stable bucket untouched because the native contract rejects any scale other than 1.0 there.
 */
internal fun nvidiaStickyUpscale(
    budget: DesktopNvidiaVideoBudget,
    stableBucket: Double,
    stickyFloor: Double?,
    upscaleDecision: Boolean,
    budgetCap: Double,
): Double {
    if (!upscaleDecision || budget.scalePolicy != NvidiaScalePolicy.STICKY_UP) return stableBucket
    if (!(stableBucket > 1.0)) return stableBucket
    val candidate = max(stableBucket, stickyFloor ?: 1.0)
    return max(1.0, min(candidate, budgetCap))
}

/**
 * Next floor after a decision was applied. A non-upscale decision, or a scale of 1.0, resets it,
 * and so does a different source. A guard step resets it by the caller passing a fresh state
 * (see nvidiaStickyFloorAfterGuardCommit).
 */
internal fun nvidiaAdvanceStickyFloor(
    current: NvidiaStickyFloor,
    sourceKey: String?,
    upscaleDecision: Boolean,
    appliedScale: Double,
): NvidiaStickyFloor {
    if (!upscaleDecision || !(appliedScale > 1.0)) return NvidiaStickyFloor()
    val previous = nvidiaStickyFloorFor(current, sourceKey) ?: 1.0
    return NvidiaStickyFloor(sourceKey, max(previous, appliedScale))
}

/**
 * The floor after the adaptive guard committed a decision, given the guard's step count before and after the commit.
 * A committed step lowers the rung, so the floor of the old rung ends and the next request starts from the stable
 * bucket again (design 6.5). A committed busy retry leaves the count unchanged: it changes no rung and must resend
 * identical options, so it keeps the floor.
 */
internal fun nvidiaStickyFloorAfterGuardCommit(current: NvidiaStickyFloor, stepsBefore: Int, stepsAfter: Int): NvidiaStickyFloor =
    if (stepsAfter != stepsBefore) NvidiaStickyFloor() else current
