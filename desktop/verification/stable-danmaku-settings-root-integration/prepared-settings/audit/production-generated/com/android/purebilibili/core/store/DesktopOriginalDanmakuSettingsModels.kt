package com.android.purebilibili.core.store
import com.android.purebilibili.feature.video.danmaku.DANMAKU_DEFAULT_OPACITY
import kotlin.math.abs

enum class DanmakuPanelWidthMode(val value: Int, val label: String, val widthFraction: Float) {
    FULL(0, "全宽", 1f),
    HALF(1, "半屏", 0.5f),
    THIRD(2, "1/3 屏", 1f / 3f);

    companion object {
        fun fromValue(value: Int): DanmakuPanelWidthMode =
            entries.find { it.value == value } ?: THIRD
    }
}

enum class PortraitDanmakuDisplayAreaMode(val value: Int, val label: String) {
    VIDEO_VIEWPORT(0, "视频画面"),
    SCREEN_TOP(1, "屏幕顶部");

    companion object {
        fun fromValue(value: Int): PortraitDanmakuDisplayAreaMode =
            entries.find { it.value == value } ?: VIDEO_VIEWPORT
    }
}

data class DanmakuSettings(
    val enabled: Boolean = true,
    val opacity: Float = DANMAKU_DEFAULT_OPACITY,
    val fontScale: Float = 1.0f,
    val speed: Float = 1.0f,
    val displayArea: Float = 0.5f,
    val fontWeight: Int = 5,
    val strokeWidth: Float = 1.5f,
    val lineHeight: Float = 1.6f,
    val scrollDurationSeconds: Float = 7.0f,
    val staticDurationSeconds: Float = 4.0f,
    val scrollFixedVelocity: Boolean = false,
    val staticDanmakuToScroll: Boolean = false,
    val massiveMode: Boolean = false,
    val mergeDuplicates: Boolean = true,
    val duplicateMergeWindowMs: Int = 500,
    val duplicateMergeCountThreshold: Int = 2,
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowColorful: Boolean = true,
    val allowSpecial: Boolean = true,
    val weightFilterLevel: Int = 0,
    val hideInteractiveCommands: Boolean = false,
    val blockAttentionCommands: Boolean = false,
    val smartOcclusion: Boolean = false,
    val portraitDisplayAreaMode: PortraitDanmakuDisplayAreaMode =
        PortraitDanmakuDisplayAreaMode.VIDEO_VIEWPORT,
    val fullscreenPanelWidthMode: DanmakuPanelWidthMode = DanmakuPanelWidthMode.THIRD,
    val blockRulesRaw: String = "",
    val blockRules: List<String> = emptyList()
)

internal fun normalizeDanmakuDisplayArea(value: Float): Float {
    val normalized = value.coerceIn(0.25f, 1.0f)
    val supportedOptions = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)
    return supportedOptions.minByOrNull { abs(it - normalized) } ?: 0.5f
}

internal fun normalizeDanmakuFontScale(value: Float): Float = value.coerceIn(0.3f, 2.0f)

internal fun normalizeDanmakuFullscreenPanelWidthMode(
    mode: DanmakuPanelWidthMode
): DanmakuPanelWidthMode = DanmakuPanelWidthMode.THIRD

internal fun resolveDanmakuSettingsScope(isLandscape: Boolean): DanmakuSettingsScope {
    return if (isLandscape) DanmakuSettingsScope.LANDSCAPE else DanmakuSettingsScope.PORTRAIT
}
