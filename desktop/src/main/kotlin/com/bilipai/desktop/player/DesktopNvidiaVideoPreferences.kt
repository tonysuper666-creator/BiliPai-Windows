package com.bilipai.desktop.player

import com.bilipai.desktop.nvidia.NvidiaPlan

/** Actual Veyra/NGX VSR quality levels; these are processing quality, not a sharpness filter. */
enum class DesktopNvidiaVideoQuality(val nativeLevel: Int, val label: String) {
    STANDARD(2, "标准"), HIGH(3, "高"), HIGHEST(4, "最高")
}

/** Conversion policy only. Native HDR/PQ/HLG and Dolby Vision keep their original route. */
enum class DesktopNvidiaVideoHdrMode(val storedValue: String, val label: String) {
    AUTO("auto", "自动转换"), OFF("off", "关闭")
}

/** Functional effects only; no content classifier or image-strength mapping. */
enum class DesktopNvidiaVideoContent(val label: String, val srEnabled: Boolean, val hdrMode: DesktopNvidiaVideoHdrMode) {
    CLARITY("清晰度", true, DesktopNvidiaVideoHdrMode.OFF),
    CLARITY_AND_HDR("清晰度 + HDR 自动", true, DesktopNvidiaVideoHdrMode.AUTO),
    HDR_ONLY("仅 HDR 自动", false, DesktopNvidiaVideoHdrMode.AUTO),
}

/** Application GPU output fusion; NGX processing quality remains independent. */
enum class DesktopNvidiaVideoIntensity(val nativePercent: Int, val label: String) {
    GENTLE(50, "轻柔"), BALANCED(75, "均衡"), FULL(100, "完整")
}

/** One atomic preference snapshot feeds the existing owned video session. */
data class DesktopNvidiaVideoPreferences(
    val enabled: Boolean = false,
    val quality: DesktopNvidiaVideoQuality = DesktopNvidiaVideoQuality.HIGHEST,
    val hdrMode: DesktopNvidiaVideoHdrMode = DesktopNvidiaVideoHdrMode.OFF,
    val srEnabled: Boolean = true,
    val intensity: DesktopNvidiaVideoIntensity = DesktopNvidiaVideoIntensity.FULL,
) {
    val content: DesktopNvidiaVideoContent get() = when {
        !srEnabled -> DesktopNvidiaVideoContent.HDR_ONLY
        hdrMode == DesktopNvidiaVideoHdrMode.AUTO -> DesktopNvidiaVideoContent.CLARITY_AND_HDR
        else -> DesktopNvidiaVideoContent.CLARITY
    }
}

/**
 * What the adaptive guard currently allows for one source. None changes nothing. The session maps the rung the
 * guard is at to these two limits (quality cap and HDR) when it builds a request, and the request builder reads them here.
 */
internal data class NvidiaGuardLimits(val qualityCap: Int = 4, val hdrAllowed: Boolean = true) {
    companion object {
        val None: NvidiaGuardLimits = NvidiaGuardLimits()
    }
}

/**
 * A driver fallback cannot consume quality, so preference changes keep its exact request stable.
 * On the shared core the stored quality is a ceiling (质量上限): the request is the lowest of that ceiling,
 * the GPU tier's quality and the guard's cap. HDR additionally needs the plan and the guard to allow it.
 * Without a plan and with the default guard this is exactly the request the preferences alone describe.
 */
internal fun DesktopNvidiaVideoPreferences.optionsFor(decision: DesktopNvidiaVideoDecision,
    backend: NvidiaVideoBackend, plan: NvidiaPlan? = null, guard: NvidiaGuardLimits = NvidiaGuardLimits.None): NvidiaVideoOptions {
    val sharedCore = backend == NvidiaVideoBackend.VEYRA_CORE
    val hdrAllowed = (plan?.hdrAllowedFor(srEnabled) ?: true) && guard.hdrAllowed
    val level = if (sharedCore && srEnabled) {
        minOf(quality.nativeLevel, plan?.tierQuality ?: 4, guard.qualityCap).coerceIn(1, 4)
    } else 4
    return NvidiaVideoOptions(decision.scale,
        decision.hdr && hdrMode == DesktopNvidiaVideoHdrMode.AUTO && hdrAllowed,
        decision.nativeResolutionProcessing, backend,
        qualityLevel = level,
        srEnabled = srEnabled,
        intensityPercent = if (sharedCore) intensity.nativePercent else 100)
}
