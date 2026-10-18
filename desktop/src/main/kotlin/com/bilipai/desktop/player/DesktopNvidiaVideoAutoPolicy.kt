package com.bilipai.desktop.player

import com.bilipai.desktop.nvidia.DesktopNvidiaVideoBudget
import com.bilipai.desktop.nvidia.NVIDIA_ABSOLUTE_MAX_SCALE
import com.bilipai.desktop.nvidia.NvidiaScalePolicy
import com.bilipai.desktop.nvidia.nvidiaStickyUpscale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

/** OVER_BUDGET: the source needed processing but exceeds the GPU's input range or rate budget, so it passes through. */
internal enum class DesktopNvidiaVideoDecisionKind { WAITING_VIDEO, WAITING_GPU_LIMIT, DIRECT, UPSCALE, HDR, UPSCALE_AND_HDR, NATIVE_RESOLUTION, NATIVE_RESOLUTION_AND_HDR, OVER_BUDGET }

/** A decision about the decoded input and the actual native video rectangle.
 * Processed output metadata must never feed back into the next input decision. */
internal data class DesktopNvidiaVideoDecision(
    val kind: DesktopNvidiaVideoDecisionKind,
    val scale: Double = 1.0,
    val hdr: Boolean = false,
    val sourceIsHdr: Boolean = false,
    val nativeResolutionProcessing: Boolean = false,
) {
    val needsProcessing: Boolean get() = scale > 1.0 || hdr || nativeResolutionProcessing

    /** A real upscale: the only kinds that carry a scale above one, and the only ones the sticky floor may touch. */
    val isUpscale: Boolean get() = kind == DesktopNvidiaVideoDecisionKind.UPSCALE ||
        kind == DesktopNvidiaVideoDecisionKind.UPSCALE_AND_HDR
}

/**
 * The decision for the decoded input and the actual video rectangle. Without a budget this is exactly the
 * unbudgeted decision. With one, the budget never creates a decision on its own: it only judges a decision
 * that needs processing, so a source that needs none keeps its DIRECT or WAITING outcome and its text.
 */
internal fun resolveDesktopNvidiaVideoDecision(
    inputWidth: Int,
    inputHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    maximumTextureDimension: Int?,
    inputTransfer: String?,
    dolbyVisionProfile: Int?,
    hdrDisplayEnabled: Boolean,
    targetTransfer: String? = null,
    targetPrimaries: String? = null,
    nativeResolutionPatchAvailable: Boolean = false,
    hdrMode: DesktopNvidiaVideoHdrMode = DesktopNvidiaVideoHdrMode.OFF,
    sharedCoreNativeResolutionAvailable: Boolean = false,
    srEnabled: Boolean = true,
    budget: DesktopNvidiaVideoBudget? = null,
): DesktopNvidiaVideoDecision {
    val decision = resolveUnbudgetedNvidiaVideoDecision(inputWidth, inputHeight, displayWidth, displayHeight,
        maximumTextureDimension, inputTransfer, dolbyVisionProfile, hdrDisplayEnabled, targetTransfer, targetPrimaries,
        nativeResolutionPatchAvailable, hdrMode, sharedCoreNativeResolutionAvailable, srEnabled)
    if (budget == null || !decision.needsProcessing) return decision
    return applyNvidiaVideoBudget(decision, budget, inputWidth, inputHeight, maximumTextureDimension)
}

/**
 * Judges a decision that needs processing against one GPU's budget. Native-size paths (1x through the
 * native-resolution decision, and HDR at unity) check the pixel cap and the rate; a real upscale needs the
 * input to fit the SR box and is trimmed to the budget's scale ceiling. A scale trimmed to exactly 1.0 while
 * the display needs more, or an input beyond the caps, is OVER_BUDGET. The effective HDR mode is decided
 * before the resolver, so this never masks the HDR flag of a decision.
 */
private fun applyNvidiaVideoBudget(
    decision: DesktopNvidiaVideoDecision,
    budget: DesktopNvidiaVideoBudget,
    inputWidth: Int,
    inputHeight: Int,
    maximumTextureDimension: Int?,
): DesktopNvidiaVideoDecision {
    val overBudget = DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.OVER_BUDGET, sourceIsHdr = decision.sourceIsHdr)
    if (decision.nativeResolutionProcessing) {
        val fits = budget.withinNativeSizeCap(inputWidth, inputHeight) &&
            (!decision.hdr || budget.withinHdrOnlyCap(inputWidth, inputHeight)) &&
            budget.withinRate(inputWidth, inputHeight, 1.0, decision.hdr)
        return if (fits) decision else overBudget
    }
    if (decision.scale > 1.0) {
        if (!budget.withinSrInputBox(inputWidth, inputHeight)) return overBudget
        val trimmed = min(decision.scale, budget.scaleCap(inputWidth, inputHeight, decision.hdr, maximumTextureDimension))
        return when {
            trimmed <= 1.0 -> overBudget
            trimmed < decision.scale -> decision.copy(scale = trimmed)
            else -> decision
        }
    }
    // HDR at unity: HDR-only, or the shared core's unity HDR with super resolution on.
    val fits = budget.withinHdrOnlyCap(inputWidth, inputHeight) &&
        budget.withinRate(inputWidth, inputHeight, 1.0, decision.hdr)
    return if (fits) decision else overBudget
}

private fun resolveUnbudgetedNvidiaVideoDecision(
    inputWidth: Int,
    inputHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    maximumTextureDimension: Int?,
    inputTransfer: String?,
    dolbyVisionProfile: Int?,
    hdrDisplayEnabled: Boolean,
    targetTransfer: String? = null,
    targetPrimaries: String? = null,
    nativeResolutionPatchAvailable: Boolean = false,
    hdrMode: DesktopNvidiaVideoHdrMode = DesktopNvidiaVideoHdrMode.OFF,
    sharedCoreNativeResolutionAvailable: Boolean = false,
    srEnabled: Boolean = true,
): DesktopNvidiaVideoDecision {
    val transfer = inputTransfer?.lowercase()
    val sourceIsHdr = dolbyVisionProfile != null || transfer in setOf("pq", "hlg", "st2084", "smpte2084")
    // Unknown colour information is not proof of SDR. Preserve native HDR/DV;
    // request SDR-to-HDR only for a known decoded SDR transfer on an active HDR display.
    val knownSdr = transfer in setOf("bt.1886", "bt.709", "srgb", "linear", "gamma1.8", "gamma2.0",
        "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8", "prophoto", "st428")
    val hdr = hdrMode == DesktopNvidiaVideoHdrMode.AUTO && hdrDisplayEnabled && knownSdr &&
        !sourceIsHdr && nvidiaHdrTarget(targetTransfer, targetPrimaries)
    if (inputWidth <= 0 || inputHeight <= 0 || displayWidth <= 0 || displayHeight <= 0)
        return DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.WAITING_VIDEO, sourceIsHdr = sourceIsHdr)

    // HDR-only preserves native HDR/DV; existing SR driver routing is unchanged.
    if (!srEnabled && sourceIsHdr)
        return DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.DIRECT, sourceIsHdr = true)
    if (!srEnabled) {
        if (!hdr || !sharedCoreNativeResolutionAvailable || transfer !in setOf("bt.1886", "srgb") ||
            inputWidth % 2 != 0 || inputHeight % 2 != 0)
            return DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.DIRECT)
        if (maximumTextureDimension == null || maximumTextureDimension <= 0)
            return DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT)
        if (maximumTextureDimension < max(inputWidth, inputHeight))
            return DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.DIRECT)
        // The core's no-SR contract requires output extent exactly equal to input.
        return DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.HDR, hdr = true)
    }

    val requestedScale = max(1.0, max(displayWidth.toDouble() / inputWidth, displayHeight.toDouble() / inputHeight))
    // The Windows NVIDIA contract accepts at most 4x. The driver's observed texture
    // limit is an additional bound; absence of a limit never licenses an upscale.
    val scale = if (maximumTextureDimension != null && maximumTextureDimension > 0) {
        min(4.0, min(requestedScale, min(maximumTextureDimension.toDouble() / inputWidth,
            maximumTextureDimension.toDouble() / inputHeight))).coerceAtLeast(1.0)
    } else 1.0
    // The driver patch still requires an exact native rectangle. The verified shared
    // core can process its qualified SDR input at 1x before the renderer downsizes it.
    val nativeResolutionViewport = (nativeResolutionPatchAvailable &&
        displayWidth == inputWidth && displayHeight == inputHeight) ||
        (sharedCoreNativeResolutionAvailable && transfer in setOf("bt.1886", "srgb") &&
            displayWidth <= inputWidth && displayHeight <= inputHeight)
    val nativeResolution = nativeResolutionViewport && knownSdr && !sourceIsHdr &&
        scale == 1.0 && inputWidth % 2 == 0 && inputHeight % 2 == 0 &&
        maximumTextureDimension != null && maximumTextureDimension >= max(inputWidth, inputHeight)
    val kind = when {
        scale > 1.0 && hdr -> DesktopNvidiaVideoDecisionKind.UPSCALE_AND_HDR
        scale > 1.0 -> DesktopNvidiaVideoDecisionKind.UPSCALE
        nativeResolution && hdr -> DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION_AND_HDR
        nativeResolution -> DesktopNvidiaVideoDecisionKind.NATIVE_RESOLUTION
        hdr -> DesktopNvidiaVideoDecisionKind.HDR
        requestedScale > 1.0 && (maximumTextureDimension == null || maximumTextureDimension <= 0) ->
            DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT
        else -> DesktopNvidiaVideoDecisionKind.DIRECT
    }
    return DesktopNvidiaVideoDecision(kind, scale, hdr, sourceIsHdr, nativeResolution)
}

/** Process a stable upper size bucket, then let the existing renderer downsize.
 * Small rectangle changes retain the native session without undersizing its
 * requested output. Native/HDR-only 1x and the current GPU texture cap stay exact.
 * budgetMaxScale is the GPU budget's scale ceiling; its default of 4 changes nothing. */
internal fun nvidiaStableUpscale(
    requestedScale: Double, inputWidth: Int, inputHeight: Int, maximumTextureDimension: Int?,
    budgetMaxScale: Double = 4.0,
): Double {
    if (!requestedScale.isFinite() || requestedScale <= 1.0) return 1.0
    if (inputWidth <= 0 || inputHeight <= 0 || maximumTextureDimension == null || maximumTextureDimension <= 0) return 1.0
    if (budgetMaxScale.isNaN()) return 1.0
    val cap = min(4.0, min(maximumTextureDimension.toDouble() / max(inputWidth, inputHeight), budgetMaxScale)).coerceAtLeast(1.0)
    return min(ceil(requestedScale * 4.0) / 4.0, cap)
}

/** The scale of one request and whether the sticky floor mechanism shaped it (a real upscale on a sticky tier). */
internal data class NvidiaRequestScale(val scale: Double, val stickyApplied: Boolean)

/**
 * The scale a request carries: the stable bucket bounded by the budget's ceiling and, for a real upscale on a
 * sticky tier only, raised to the floor already used for this source. Native-size, HDR-only and direct decisions
 * never get a floor, because the native contract rejects any scale other than 1.0 there, and tiers that follow
 * the display have none. Without a budget this is exactly the stable bucket.
 */
internal fun nvidiaRequestScale(
    options: NvidiaVideoOptions,
    decision: DesktopNvidiaVideoDecision,
    budget: DesktopNvidiaVideoBudget?,
    inputWidth: Int,
    inputHeight: Int,
    maximumTextureDimension: Int?,
    stickyFloor: Double?,
): NvidiaRequestScale {
    val budgetCap = budget?.scaleCap(inputWidth, inputHeight, options.hdr, maximumTextureDimension) ?: NVIDIA_ABSOLUTE_MAX_SCALE
    val bucket = nvidiaStableUpscale(options.scale, inputWidth, inputHeight, maximumTextureDimension, budgetCap)
    val upscaleOnly = decision.isUpscale && options.srEnabled && !options.nativeResolutionProcessing
    if (budget == null || !upscaleOnly || budget.scalePolicy != NvidiaScalePolicy.STICKY_UP) return NvidiaRequestScale(bucket, false)
    return NvidiaRequestScale(nvidiaStickyUpscale(budget, bucket, stickyFloor, true, budgetCap), true)
}
