package com.bilipai.desktop.player

import kotlin.math.max
import kotlin.math.min

internal enum class DesktopNvidiaVideoDecisionKind { WAITING_VIDEO, WAITING_GPU_LIMIT, DIRECT, UPSCALE, HDR, UPSCALE_AND_HDR, NATIVE_RESOLUTION, NATIVE_RESOLUTION_AND_HDR }

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
}

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

    val requestedScale = max(1.0, max(displayWidth.toDouble() / inputWidth, displayHeight.toDouble() / inputHeight))
    // The Windows NVIDIA contract accepts at most 4x. The driver's observed texture
    // limit is an additional bound; absence of a limit never licenses an upscale.
    val scale = if (maximumTextureDimension != null && maximumTextureDimension > 0) {
        min(4.0, min(requestedScale, min(maximumTextureDimension.toDouble() / inputWidth,
            maximumTextureDimension.toDouble() / inputHeight))).coerceAtLeast(1.0)
    } else 1.0
    // First bounded candidate: known SDR and an exact even-sized native rectangle.
    // Downscale, unknown dimensions/colour/limit and native HDR/DV keep prior behavior.
    val nativeResolution = nativeResolutionPatchAvailable && knownSdr && !sourceIsHdr &&
        scale == 1.0 && displayWidth == inputWidth && displayHeight == inputHeight &&
        inputWidth % 2 == 0 && inputHeight % 2 == 0 &&
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
