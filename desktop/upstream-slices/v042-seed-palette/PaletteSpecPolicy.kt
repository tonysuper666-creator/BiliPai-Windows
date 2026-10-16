package com.android.purebilibili.core.theme

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/** Matches MaterialKolor 4.1.1 DynamicScheme's supported 2025 variants. */
internal fun supportsMaterialSpec2025(style: PaletteStyle): Boolean = when (style) {
    PaletteStyle.TonalSpot, PaletteStyle.Neutral, PaletteStyle.Vibrant, PaletteStyle.Expressive -> true
    else -> false
}

internal fun resolveMaterialPaletteSpec(
    style: PaletteStyle,
    requested: ColorSpec.SpecVersion,
): ColorSpec.SpecVersion = if (supportsMaterialSpec2025(style)) requested else ColorSpec.SpecVersion.SPEC_2021

internal fun SeedPaletteStyle.material2025Style(): PaletteStyle? = when (this) {
    SeedPaletteStyle.TonalSpot -> PaletteStyle.TonalSpot
    SeedPaletteStyle.Neutral -> PaletteStyle.Neutral
    SeedPaletteStyle.Vibrant -> PaletteStyle.Vibrant
    SeedPaletteStyle.Expressive -> PaletteStyle.Expressive
    else -> null
}

internal fun resolveSeedPaletteSpec(
    style: SeedPaletteStyle,
    requested: ColorSpec.SpecVersion,
): ColorSpec.SpecVersion = if (style.material2025Style() != null) requested else ColorSpec.SpecVersion.SPEC_2021
