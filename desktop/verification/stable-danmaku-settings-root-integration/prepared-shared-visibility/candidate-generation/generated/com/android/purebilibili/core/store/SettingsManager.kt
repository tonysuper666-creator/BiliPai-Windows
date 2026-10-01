// OriginalSource: app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt
// OriginalSHA256: 680005e1f25e8a365d30f0c78c988765e7d2140008c57d9bf31d859c5b835b1c
package com.android.purebilibili.core.store


enum class LiquidGlassStyle(val value: Int) {
    CLASSIC(0),      // BiliPai's Wavy Ripple
    SUKISU(1),       // SukiSU floating bottom bar glass
    IOS26(2);        // iOS26-like layered liquid glass

    companion object {
        fun fromValue(value: Int): LiquidGlassStyle = entries.find { it.value == value } ?: CLASSIC
    }
}

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

internal fun normalizeLiquidGlassAdvancedValue(value: Float, fallback: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else fallback

internal fun resolveLiquidGlassAdvancedPreset(
    preset: LiquidGlassAdvancedPreset,
): LiquidGlassAdvancedSettings = when (preset) {
    LiquidGlassAdvancedPreset.READABLE -> LiquidGlassAdvancedSettings(
        preset = preset,
        progressiveBlurRadius = 0.25f,
        progressiveBlurExtent = 0.6f,
        progressiveBlurCurve = 0.4f,
        contentReadability = 1f,
        chromaticAberration = 0.08f,
        contentDistortion = 0f,
    )
    LiquidGlassAdvancedPreset.BALANCED -> LiquidGlassAdvancedSettings(
        preset = preset,
        progressiveBlurRadius = 0.40f,
        progressiveBlurExtent = 1.0f,
        progressiveBlurCurve = 0.55f,
        contentReadability = 0.62f,
        chromaticAberration = 0.56f,
        contentDistortion = 0.45f,
    )
    LiquidGlassAdvancedPreset.PRISM -> LiquidGlassAdvancedSettings(
        preset = preset,
        progressiveBlurRadius = 0.8f,
        progressiveBlurExtent = 0.9f,
        progressiveBlurCurve = 0.65f,
        contentReadability = 0.72f,
        chromaticAberration = 0.96f,
        contentDistortion = 1f,
    )
    LiquidGlassAdvancedPreset.CUSTOM -> LiquidGlassAdvancedSettings(preset = preset)
}

internal fun resolveLiquidGlassAdvancedSettings(
    presetValue: Int?,
    progressiveBlurRadius: Float? = null,
    progressiveBlurExtent: Float? = null,
    progressiveBlurCurve: Float? = null,
    contentReadability: Float?,
    chromaticAberration: Float?,
    contentDistortion: Float?,
): LiquidGlassAdvancedSettings {
    val preset = presetValue
        ?.let(LiquidGlassAdvancedPreset::fromValue)
        ?: LiquidGlassAdvancedPreset.BALANCED
    val defaults = resolveLiquidGlassAdvancedPreset(preset)
    if (preset != LiquidGlassAdvancedPreset.CUSTOM) return defaults
    return defaults.copy(
        progressiveBlurRadius = normalizeLiquidGlassAdvancedValue(
            progressiveBlurRadius ?: defaults.progressiveBlurRadius,
            defaults.progressiveBlurRadius,
        ),
        progressiveBlurExtent = normalizeLiquidGlassAdvancedValue(
            progressiveBlurExtent ?: defaults.progressiveBlurExtent,
            defaults.progressiveBlurExtent,
        ),
        progressiveBlurCurve = normalizeLiquidGlassAdvancedValue(
            progressiveBlurCurve ?: defaults.progressiveBlurCurve,
            defaults.progressiveBlurCurve,
        ),
        contentReadability = normalizeLiquidGlassAdvancedValue(
            contentReadability ?: defaults.contentReadability,
            defaults.contentReadability,
        ),
        chromaticAberration = normalizeLiquidGlassAdvancedValue(
            chromaticAberration ?: defaults.chromaticAberration,
            defaults.chromaticAberration,
        ),
        contentDistortion = normalizeLiquidGlassAdvancedValue(
            contentDistortion ?: defaults.contentDistortion,
            defaults.contentDistortion,
        ),
    )
}

internal fun resolveLegacyLiquidGlassMode(style: LiquidGlassStyle): LiquidGlassMode = when (style) {
    LiquidGlassStyle.IOS26 -> LiquidGlassMode.CLEAR
    LiquidGlassStyle.CLASSIC -> LiquidGlassMode.BALANCED
    LiquidGlassStyle.SUKISU -> LiquidGlassMode.BALANCED
}

internal fun resolveDefaultLiquidGlassStrength(mode: LiquidGlassMode): Float = when (mode) {
    LiquidGlassMode.CLEAR -> 0.42f
    LiquidGlassMode.BALANCED -> 0.52f
    LiquidGlassMode.FROSTED -> 0.62f
}

internal fun normalizeLiquidGlassStrength(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0.52f

internal fun normalizeLiquidGlassProgress(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f

/** 长按倍速提示整体缩放（0.8×–1.5×，默认 1.0×）。 */

internal fun resolveLegacyLiquidGlassProgress(
    mode: LiquidGlassMode,
    strength: Float
): Float {
    val normalizedStrength = normalizeLiquidGlassStrength(strength)
    val (start, end) = when (mode) {
        LiquidGlassMode.CLEAR -> 0f to 0.32f
        LiquidGlassMode.BALANCED -> 0.34f to 0.66f
        LiquidGlassMode.FROSTED -> 0.68f to 1f
    }
    return normalizeLiquidGlassProgress(start + (end - start) * normalizedStrength)
}

internal fun resolveLegacyLiquidGlassProgress(style: LiquidGlassStyle): Float {
    val mode = resolveLegacyLiquidGlassMode(style)
    return resolveLegacyLiquidGlassProgress(
        mode = mode,
        strength = resolveDefaultLiquidGlassStrength(mode)
    )
}

/** Prefer the v2 continuous value, falling back to the complete legacy material selection. */

internal fun resolveStoredLiquidGlassProgress(
    progress: Float?,
    legacyModeValue: Int?,
    legacyStrength: Float?,
    legacyStyleValue: Int?,
): Float {
    progress?.let { return normalizeLiquidGlassProgress(it) }
    val legacyStyle = LiquidGlassStyle.fromValue(
        legacyStyleValue ?: LiquidGlassStyle.SUKISU.value
    )
    val mode = legacyModeValue
        ?.let(LiquidGlassMode::fromValue)
        ?: resolveLegacyLiquidGlassMode(legacyStyle)
    val strength = normalizeLiquidGlassStrength(
        legacyStrength ?: resolveDefaultLiquidGlassStrength(mode)
    )
    return resolveLegacyLiquidGlassProgress(mode, strength)
}

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
