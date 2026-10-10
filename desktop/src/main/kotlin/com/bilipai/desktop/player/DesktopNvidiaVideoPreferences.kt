package com.bilipai.desktop.player

/** Actual Veyra/NGX VSR quality levels; these are processing quality, not a sharpness filter. */
enum class DesktopNvidiaVideoQuality(val nativeLevel: Int, val label: String) {
    STANDARD(2, "标准"), HIGH(3, "高"), HIGHEST(4, "最高")
}

/** Conversion policy only. Native HDR/PQ/HLG and Dolby Vision keep their original route. */
enum class DesktopNvidiaVideoHdrMode(val storedValue: String, val label: String) {
    AUTO("auto", "自动转换"), OFF("off", "关闭")
}

/** One atomic preference snapshot feeds the existing owned video session. */
data class DesktopNvidiaVideoPreferences(
    val enabled: Boolean = false,
    val quality: DesktopNvidiaVideoQuality = DesktopNvidiaVideoQuality.HIGHEST,
    val hdrMode: DesktopNvidiaVideoHdrMode = DesktopNvidiaVideoHdrMode.OFF,
)

/** A driver fallback cannot consume quality, so preference changes keep its exact request stable. */
internal fun DesktopNvidiaVideoPreferences.optionsFor(decision: DesktopNvidiaVideoDecision,
    backend: NvidiaVideoBackend): NvidiaVideoOptions = NvidiaVideoOptions(decision.scale,
    decision.hdr && hdrMode == DesktopNvidiaVideoHdrMode.AUTO,
    decision.nativeResolutionProcessing, backend,
    qualityLevel = if (backend == NvidiaVideoBackend.VEYRA_CORE) quality.nativeLevel else 4)
