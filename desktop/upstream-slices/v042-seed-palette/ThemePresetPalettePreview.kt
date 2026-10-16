package com.android.purebilibili.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * 设置页预设色板单格预览所用的三个角色。
 *
 * 版式对齐 PiliPlus `common/widgets/color_palette.dart`：圆内上半 primary、左下 tertiary、
 * 右下 primaryContainer；额外保留底色与选中圆盘色，避免为每格缓存整份 ColorScheme。
 */
@Immutable
internal data class ThemePresetPaletteSwatch(
    val primary: Color,
    val tertiary: Color,
    val primaryContainer: Color,
    val surfaceContainer: Color = Color.Transparent,
    val inverseOnSurface: Color = Color.Transparent,
)

/**
 * 按所选调色板生成预览；标准模式沿用原有 MaterialKolor 参数。
 */
internal fun resolveThemePresetPaletteSwatch(
    seedColor: Color,
    isDark: Boolean,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2025,
    colorContrast: Contrast = Contrast.Default,
    seedPaletteStyle: SeedPaletteStyle = SeedPaletteStyle.Standard,
): ThemePresetPaletteSwatch {
    val scheme = if (seedPaletteStyle.enabled) createSeedColorScheme(seedColor, isDark, seedPaletteStyle, colorSpec = colorSpec)
    else dynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        style = paletteStyle,
        specVersion = resolveMaterialPaletteSpec(paletteStyle, colorSpec),
        contrastLevel = colorContrast.value,
    )
    return ThemePresetPaletteSwatch(
        primary = scheme.primary,
        tertiary = scheme.tertiary,
        primaryContainer = scheme.primaryContainer,
        surfaceContainer = scheme.surfaceContainer,
        inverseOnSurface = scheme.inverseOnSurface,
    )
}

/**
 * 批量生成预设色板预览：整组结果随色彩风格 / 色彩标准 / 明暗一次性重算，
 * 网格滚动或重组时不会逐格重复生成方案。
 */
@Composable
internal fun rememberThemePresetPaletteSwatches(
    seedColors: List<Color>,
    isDark: Boolean,
    paletteStyle: PaletteStyle,
    colorSpec: ColorSpec.SpecVersion,
    colorContrast: Contrast = Contrast.Default,
    seedPaletteStyle: SeedPaletteStyle = SeedPaletteStyle.Standard,
): List<ThemePresetPaletteSwatch> = remember(seedColors, isDark, paletteStyle, colorSpec, colorContrast, seedPaletteStyle) {
    seedColors.map { seedColor ->
        resolveThemePresetPaletteSwatch(
            seedColor = seedColor,
            isDark = isDark,
            paletteStyle = paletteStyle,
            colorSpec = colorSpec,
            colorContrast = colorContrast,
            seedPaletteStyle = seedPaletteStyle,
        )
    }
}

/**
 * 当前生效方案是否为深色，用于让色板格子的预览配色跟随实际主题。
 * 与 WallpaperPaletteEditor 等处保持同一判据。
 */
internal fun resolveColorSchemeIsDark(colorScheme: ColorScheme): Boolean =
    colorScheme.background.luminance() < 0.5f

/**
 * 按「同一个种子色 + 一组色彩风格」批量生成小样，供色彩风格选项做前导视觉预览。
 * 与色板预览共用 [resolveThemePresetPaletteSwatch]，所以小样与实际生效配色一致；
 * 整组随种子色、明暗、清单与色彩标准变化一次性重算。
 */
@Composable
internal fun rememberThemeStylePaletteSwatches(
    seedColor: Color,
    isDark: Boolean,
    paletteStyles: List<PaletteStyle>,
    colorSpec: ColorSpec.SpecVersion,
    colorContrast: Contrast = Contrast.Default,
    seedPaletteStyle: SeedPaletteStyle = SeedPaletteStyle.Standard,
): List<ThemePresetPaletteSwatch> = remember(seedColor, isDark, paletteStyles, colorSpec, colorContrast, seedPaletteStyle) {
    paletteStyles.map { paletteStyle ->
        resolveThemePresetPaletteSwatch(
            seedColor = seedColor,
            isDark = isDark,
            paletteStyle = paletteStyle,
            colorSpec = colorSpec,
            colorContrast = colorContrast,
            seedPaletteStyle = seedPaletteStyle,
        )
    }
}

@Composable
internal fun rememberSeedStyleSwatches(
    seedColor: Color,
    isDark: Boolean,
    colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2021,
): Map<SeedPaletteStyle, ThemePresetPaletteSwatch> = remember(seedColor, isDark, colorSpec) {
    SeedPaletteStyle.entries.filter { it.enabled }.associateWith { style ->
        resolveThemePresetPaletteSwatch(seedColor, isDark, colorSpec = colorSpec, seedPaletteStyle = style)
    }
}
