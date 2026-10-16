package com.android.purebilibili.feature.settings

import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.SeedPaletteStyle
import com.android.purebilibili.core.theme.material2025Style
import com.android.purebilibili.core.theme.resolveMaterialPaletteSpec
import com.android.purebilibili.core.theme.resolveSeedPaletteSpec
import com.android.purebilibili.core.theme.supportsMaterialSpec2025
import com.android.purebilibili.core.ui.AppIconStyle
import com.android.purebilibili.core.ui.AppListItemStyle
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

internal fun resolveThemeModeSegmentOptions(
    followSystemLabel: String = AppThemeMode.FOLLOW_SYSTEM.label,
    lightLabel: String = AppThemeMode.LIGHT.label,
    darkLabel: String = AppThemeMode.DARK.label
): List<AppSegmentOption<AppThemeMode>> {
    return listOf(
        AppSegmentOption(AppThemeMode.FOLLOW_SYSTEM, followSystemLabel),
        AppSegmentOption(AppThemeMode.LIGHT, lightLabel),
        AppSegmentOption(AppThemeMode.DARK, darkLabel)
    )
}

internal fun resolveSeedPaletteStyleOptions(): List<AppSegmentOption<SeedPaletteStyle>> {
    val material = listOf(
        SeedPaletteStyle.Standard, SeedPaletteStyle.TonalSpot, SeedPaletteStyle.Neutral,
        SeedPaletteStyle.Vibrant, SeedPaletteStyle.Expressive, SeedPaletteStyle.Rainbow,
        SeedPaletteStyle.FruitSalad, SeedPaletteStyle.Monochrome, SeedPaletteStyle.Fidelity,
        SeedPaletteStyle.Content,
    )
    return (material + SeedPaletteStyle.entries.filterNot { it in material }).map { style ->
        AppSegmentOption(style, style.label, resolveSeedPaletteStyleDescription(style))
    }
}

internal fun resolveSeedPaletteStyleDescription(style: SeedPaletteStyle): String = when (style) {
    SeedPaletteStyle.Standard -> "沿用已设置的色彩风格、标准与对比度"
    SeedPaletteStyle.TonalSpot -> "默认风格，柔和低彩度，最贴近 Material 3 观感"
    SeedPaletteStyle.Neutral -> "接近灰阶，只保留很少彩度"
    SeedPaletteStyle.Vibrant -> "提高主色彩度，副色与第三色色相按风格变化"
    SeedPaletteStyle.Expressive -> "主色色相刻意偏离种子色，配色更有表现力"
    SeedPaletteStyle.Rainbow -> "保留种子色色相，彩度固定，背景接近灰阶"
    SeedPaletteStyle.FruitSalad -> "主色与副色色相都偏离种子色，第三色保留原色相"
    SeedPaletteStyle.Monochrome -> "全灰阶，完全不带彩度"
    SeedPaletteStyle.Fidelity -> "调色板贴近种子色，第三色使用互补色"
    SeedPaletteStyle.Content -> "主副色贴近种子色，第三色使用相邻色"
    SeedPaletteStyle.Material -> "固定低彩度与标准色调，柔和的 Material 3 配色"
    SeedPaletteStyle.Material3Legacy -> "经典 Material 3，主色彩度较高，中性表面染色较少"
    SeedPaletteStyle.Soft -> "降低主色、副色与第三色彩度，整体更柔和"
    SeedPaletteStyle.Vivid -> "加深浅色主色并提高彩度，保留鲜明的种子色特征"
    SeedPaletteStyle.VividSurfaces -> "鲜明主色搭配更明显的表面染色与文字层次"
    SeedPaletteStyle.HighContrast -> "提高彩度与部分角色对比，强化文字和容器区分"
    SeedPaletteStyle.UltraContrast -> "更深的浅色主色与更亮的深色文字，进一步强化对比"
    SeedPaletteStyle.Jolly -> "主副色更鲜艳，明亮副色搭配活泼的第三色"
    SeedPaletteStyle.VividBackground -> "生动表面配色搭配更明亮的浅色背景和更深的暗色背景"
    SeedPaletteStyle.OneHue -> "主色、副色与第三色共用一个色相，以彩度和深浅区分"
    SeedPaletteStyle.CandyPop -> "明亮鲜艳的糖果配色，文字前景自动补足可读对比"
    SeedPaletteStyle.Chroma -> "主副色彩度跟随种子色，保留原色个性"
}

internal fun resolveSelectedColorSpec(
    requested: ColorSpec.SpecVersion,
    style: PaletteStyle,
    seedStyle: SeedPaletteStyle,
): ColorSpec.SpecVersion = if (seedStyle.enabled) resolveSeedPaletteSpec(seedStyle, requested)
    else resolveMaterialPaletteSpec(style, requested)

internal fun shouldShowColorSpecControl(seedStyle: SeedPaletteStyle): Boolean =
    !seedStyle.enabled || seedStyle.material2025Style() != null

internal fun resolveSelectedColorSpecSubtitle(
    requested: ColorSpec.SpecVersion,
    style: PaletteStyle,
    seedStyle: SeedPaletteStyle,
): String {
    val effective = resolveSelectedColorSpec(requested, style, seedStyle)
    val supported = if (seedStyle.enabled) seedStyle.material2025Style() != null else supportsMaterialSpec2025(style)
    val name = if (seedStyle.enabled) seedStyle.label else style.name
    return if (!supported) {
        "当前 ${effective.name}：$name 仅支持此标准" +
            if (requested != effective) "；已保留 ${requested.name} 偏好，切换至兼容风格后恢复" else ""
    } else resolveColorSpecSubtitle(effective)
}

internal fun resolveColorStyleOptions(): List<AppSegmentOption<PaletteStyle>> {
    return (listOf(PaletteStyle.TonalSpot) + PaletteStyle.entries.filterNot { it == PaletteStyle.TonalSpot })
        .map { style ->
            AppSegmentOption(
                value = style,
                label = style.name,
                description = resolveColorStyleDescription(style),
            )
        }
}

/** 色彩风格在下拉里的补充说明，口径与 MaterialKolor / MCU 的语义一致。 */
internal fun resolveColorStyleDescription(style: PaletteStyle): String = when (style) {
    PaletteStyle.TonalSpot -> "默认风格，柔和低彩度，最贴近 Material 3 观感"
    PaletteStyle.Neutral -> "接近灰阶，只保留很少彩度"
    PaletteStyle.Vibrant -> "提高主色彩度，副色与第三色色相按风格变化"
    PaletteStyle.Expressive -> "主色色相刻意偏离种子色，配色更有玩味"
    PaletteStyle.Rainbow -> "保留种子色色相，彩度固定，背景接近灰阶"
    PaletteStyle.FruitSalad -> "主色与副色色相都偏离种子色"
    PaletteStyle.Monochrome -> "全灰阶，完全不带彩度"
    PaletteStyle.Fidelity -> "调色板贴近种子色，高彩度种子也保留"
    PaletteStyle.Content -> "与 Fidelity 几乎一致，更强调贴合种子色"
    else -> "使用 ${style.name} 色调映射"
}

/** 色彩风格在当前设置项上的显示：枚举名 + 一行含义，不展开也能看明白。 */
internal fun resolveColorStyleSubtitle(style: PaletteStyle): String =
    "当前 ${style.name}：${resolveColorStyleDescription(style)}"

internal fun resolveColorSpecOptions(
    style: PaletteStyle = PaletteStyle.TonalSpot,
    seedStyle: SeedPaletteStyle = SeedPaletteStyle.Standard,
): List<AppSegmentOption<ColorSpec.SpecVersion>> {
    val supports2025 = if (seedStyle.enabled) seedStyle.material2025Style() != null else supportsMaterialSpec2025(style)
    return listOf(
        ColorSpec.SpecVersion.SPEC_2021,
        ColorSpec.SpecVersion.SPEC_2025
    ).filter { it != ColorSpec.SpecVersion.SPEC_2025 || supports2025 }.map { spec ->
        AppSegmentOption(
            value = spec,
            label = spec.name,
            description = resolveColorSpecDescription(spec),
        )
    }
}

internal fun resolveColorSpecDescription(spec: ColorSpec.SpecVersion): String = when (spec) {
    ColorSpec.SpecVersion.SPEC_2021 -> "旧版 Material 3 配色规则，兼容传统色彩风格"
    ColorSpec.SpecVersion.SPEC_2025 -> "新版 Material 3 配色规则，更新色板、容器与对比度映射"
    else -> "使用 ${spec.name} 色调映射"
}

/** 色彩标准在当前设置项上的显示：枚举名 + 一行含义。 */
internal fun resolveColorSpecSubtitle(spec: ColorSpec.SpecVersion): String =
    "当前 ${spec.name}：${resolveColorSpecDescription(spec)}"

/**
 * 对比度档。对齐 PiliPlus 的 highContrast / ultraContrast 需求，但走 MaterialKolor
 * 原生的 Contrast（相对默认对比度的加减），不引入 flex_seed_scheme 的 FlexTones。
 */
internal fun resolveColorContrastOptions(): List<AppSegmentOption<Contrast>> {
    return listOf(
        Contrast.Reduced,
        Contrast.Default,
        Contrast.Medium,
        Contrast.High,
    ).map { contrast ->
        AppSegmentOption(
            value = contrast,
            label = contrast.name,
            description = resolveColorContrastDescription(contrast),
        )
    }
}

internal fun resolveColorContrastDescription(contrast: Contrast): String = when (contrast) {
    Contrast.Reduced -> "低于默认对比，配色更柔"
    Contrast.Default -> "Material 3 默认对比度"
    Contrast.Medium -> "高于默认对比，文字更清晰"
    Contrast.High -> "最高对比，接近无障碍高对比主题"
    else -> "使用 ${contrast.name} 对比度"
}

/** 对比度在当前设置项上的显示：枚举名 + 一行含义。 */
internal fun resolveColorContrastSubtitle(contrast: Contrast): String =
    "当前 ${contrast.name}：${resolveColorContrastDescription(contrast)}"

internal fun shouldShowMd3CustomColorControls(source: Md3ColorSource): Boolean =
    source == Md3ColorSource.CUSTOM

internal fun shouldShowMd3PaletteStyleControls(source: Md3ColorSource): Boolean =
    source != Md3ColorSource.FOLLOW_WALLPAPER

internal fun resolveAdvancedPaletteSubtitle(uiStyle: AppUiStyle): String =
    if (uiStyle == AppUiStyle.MIUIX) {
        "已保留配置，仅切换到 Material 3 后生效"
    } else {
        "自定义明暗模式的背景、文字与控件色"
    }

internal fun resolveMd3ColorSourceOptions(): List<AppSegmentOption<Md3ColorSource>> {
    return listOf(
        AppSegmentOption(Md3ColorSource.FOLLOW_WALLPAPER, Md3ColorSource.FOLLOW_WALLPAPER.label),
        AppSegmentOption(Md3ColorSource.WALLPAPER_RETONED, Md3ColorSource.WALLPAPER_RETONED.label),
        AppSegmentOption(Md3ColorSource.CUSTOM, Md3ColorSource.CUSTOM.label)
    )
}

internal fun resolveAppIconStyleOptions(): List<AppSegmentOption<AppIconStyle>> {
    return listOf(
        AppSegmentOption(AppIconStyle.AUTO, "跟随预设"),
        AppSegmentOption(AppIconStyle.THEME_CONTAINER, "主题色容器"),
        AppSegmentOption(AppIconStyle.MD3_STANDARD, "MD3 官方推荐")
    )
}

internal fun resolveAppListItemStyleOptions(): List<AppSegmentOption<AppListItemStyle>> {
    return listOf(
        AppSegmentOption(AppListItemStyle.AUTO, "跟随预设"),
        AppSegmentOption(AppListItemStyle.CUSTOM, "自定义条目"),
        AppSegmentOption(AppListItemStyle.NATIVE, "原生组件")
    )
}

internal fun resolveDarkThemeStyleSegmentOptions(
    defaultLabel: String = DarkThemeStyle.DEFAULT.label,
    amoledLabel: String = DarkThemeStyle.AMOLED.label
): List<AppSegmentOption<DarkThemeStyle>> {
    return listOf(
        AppSegmentOption(DarkThemeStyle.DEFAULT, defaultLabel),
        AppSegmentOption(DarkThemeStyle.AMOLED, amoledLabel)
    )
}

internal fun resolveAppLanguageSegmentOptions(
    followSystemLabel: String = "跟随系统",
    simplifiedChineseLabel: String = "简体中文",
    traditionalChineseLabel: String = "繁體中文",
    englishLabel: String = "英语"
): List<AppSegmentOption<AppLanguage>> {
    return listOf(
        AppSegmentOption(AppLanguage.FOLLOW_SYSTEM, followSystemLabel),
        AppSegmentOption(AppLanguage.SIMPLIFIED_CHINESE, simplifiedChineseLabel),
        AppSegmentOption(AppLanguage.TRADITIONAL_CHINESE_TAIWAN, traditionalChineseLabel),
        AppSegmentOption(AppLanguage.ENGLISH, englishLabel)
    )
}
