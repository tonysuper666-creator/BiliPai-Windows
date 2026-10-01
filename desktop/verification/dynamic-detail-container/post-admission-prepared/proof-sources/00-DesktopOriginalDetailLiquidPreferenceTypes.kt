// Original source app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt
// OriginalLF_SHA256 b43c112cfda44780b29d3a5419f6ce452bf7c0e4859cb0145636eae3f3ec2010
package com.android.purebilibili.core.store



enum class LiquidGlassMode(val value: Int, val label: String) {
    CLEAR(0, "通透玻璃"),
    BALANCED(1, "平衡"),
    FROSTED(2, "柔和磨砂");

    companion object {
        fun fromValue(value: Int): LiquidGlassMode = entries.find { it.value == value } ?: BALANCED
    }
}

enum class LiquidGlassAdvancedPreset(val value: Int, val label: String) {
    READABLE(0, "清晰"),
    BALANCED(1, "均衡"),
    PRISM(2, "棱镜"),
    CUSTOM(3, "自定");

    companion object {
        fun fromValue(value: Int): LiquidGlassAdvancedPreset =
            entries.find { it.value == value } ?: BALANCED
    }
}

data class LiquidGlassAdvancedSettings(
    val preset: LiquidGlassAdvancedPreset = LiquidGlassAdvancedPreset.BALANCED,
    val progressiveBlurRadius: Float = 0.40f,
    val progressiveBlurExtent: Float = 1.0f,
    val progressiveBlurCurve: Float = 0.55f,
    val contentReadability: Float = 0.62f,
    val chromaticAberration: Float = 0.56f,
    val contentDistortion: Float = 0.45f,
)

enum class BottomBarLiquidGlassPreset(
    val value: Int,
    val label: String,
    val description: String
) {
    BILIPAI_TUNED(
        0,
        "BiliPai 调校",
        "保留当前多层折射、色散和指示器动效"
    ),
    IOS26_REFINED(
        1,
        "iOS 26 玻璃",
        "厚边折射 + 顶光高亮环，无色散，沿用 BiliPai 指示器滑动与配色"
    );

    companion object {
        fun fromValue(value: Int): BottomBarLiquidGlassPreset =
            entries.find { it.value == value } ?: BILIPAI_TUNED
    }
}

internal fun normalizeLiquidGlassProgress(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f

/** 长按倍速提示整体缩放（0.8×–1.5×，默认 1.0×）。 */

internal fun normalizeLiquidGlassStrength(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0.52f

internal fun resolveLiquidGlassModeFromProgress(progress: Float): LiquidGlassMode {
    val normalizedProgress = normalizeLiquidGlassProgress(progress)
    return when {
        normalizedProgress < 0.34f -> LiquidGlassMode.CLEAR
        normalizedProgress < 0.68f -> LiquidGlassMode.BALANCED
        else -> LiquidGlassMode.FROSTED
    }
}

internal fun resolveLiquidGlassStrengthFromProgress(progress: Float): Float {
    val normalizedProgress = normalizeLiquidGlassProgress(progress)
    val mode = resolveLiquidGlassModeFromProgress(normalizedProgress)
    val (start, end) = when (mode) {
        LiquidGlassMode.CLEAR -> 0f to 0.32f
        LiquidGlassMode.BALANCED -> 0.34f to 0.66f
        LiquidGlassMode.FROSTED -> 0.68f to 1f
    }
    return normalizeLiquidGlassStrength(
        if (end <= start) {
            0f
        } else {
            (normalizedProgress - start) / (end - start)
        }
    )
}
