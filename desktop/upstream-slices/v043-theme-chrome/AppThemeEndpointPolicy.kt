package com.android.purebilibili.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.core.store.WallpaperPaletteOverrides
import com.android.purebilibili.core.store.WallpaperPaletteRole
import com.android.purebilibili.feature.settings.Md3ColorSource

/** One endpoint resolver for app content, both component renderers, glass and notifications.
 * Call before animation and cache by settings/base palette, never by interpolated frame colors.
 * Each wallpaper role is overridden once. Native neutral surfaces retain their style. */
internal fun resolveAppThemeEndpointPalette(
    base: ColorScheme,
    uiStyle: AppUiStyle,
    source: Md3ColorSource,
    darkTheme: Boolean,
    amoledDarkTheme: Boolean = false,
    wallpaperOverrides: WallpaperPaletteOverrides = WallpaperPaletteOverrides(),
    customSeed: Color = base.primary,
    roleOverrides: ThemeRoleOverrides = ThemeRoleOverrides(),
    liquidGlassEnabled: Boolean = false,
    preserveGeneratedPalette: Boolean = false,
): AppThemePalette {
    val overridden = applyWallpaperPaletteOverrides(base, wallpaperOverrides, source, darkTheme)
    // Wallpaper edits can change a role's backdrop. Repair only extended foregrounds
    // before either native bridge; glass on/off consumes the same corrected palette.
    val configured = if (preserveGeneratedPalette) enforceSeedTextContrast(overridden) else overridden
    val selectedAccent = resolveLiquidControlAccentColor(
        source = source,
        customSeed = customSeed,
        generatedAccent = configured.primary,
        roleOverridesEnabled = roleOverrides.enabled,
        roleControlHex = if (darkTheme) roleOverrides.dark.controlAccentHex else roleOverrides.light.controlAccentHex,
    )
    // Exact manual primary is a color-source contract, independent of glass rendering.
    // Keep the selected primary independent of material. Selection containers are
    // resolved separately below; glass treatment stays in alignLiquidAccentColorScheme.
    // Extended palettes retain generated accent/container roles and corrected
    // foregrounds for both native renderers and glass. Standard keeps its picked-color rules.
    val exactPrimary = if (!preserveGeneratedPalette && source == Md3ColorSource.CUSTOM) configured.copy(
        primary = selectedAccent,
        onPrimary = resolveReadableForeground(selectedAccent),
    ) else configured
    val nativeMiuix = shouldUseNativeMiuixPalette(uiStyle)
    val nativeMiuixColors = if (nativeMiuix) {
        resolveNativeMiuixColors(exactPrimary, darkTheme, amoledDarkTheme)
    } else null
    // 原生导航指示器、tonal 按钮及筛选胶囊消费 secondaryContainer，而非 primary。
    // 自定义色在关闭玻璃后也必须覆盖这个选中角色；只桥接颜色，不改原生布局和动效。
    // 玻璃分支沿用 alignLiquidAccentColorScheme，壁纸配色保留其独立角色。
    val useNativeCustomSelection = !preserveGeneratedPalette && !liquidGlassEnabled && source == Md3ColorSource.CUSTOM
    val material = if (useNativeCustomSelection) {
        val selectionSurface = nativeMiuixColors?.surface ?: exactPrimary.surface
        val selectionContainer = opaqueCompositeOver(selectedAccent.copy(alpha = 0.14f), selectionSurface)
        val onSelectionContainer = resolveCustomSelectionContentColor(
            accent = selectedAccent,
            container = selectionContainer,
        )
        // Standard custom colors share one accent family across tonal actions and tabs.
        // Glass and generated/wallpaper palettes retain their independent role contracts.
        exactPrimary.copy(
            primaryContainer = selectionContainer,
            onPrimaryContainer = onSelectionContainer,
            secondaryContainer = selectionContainer,
            onSecondaryContainer = onSelectionContainer,
        )
    } else exactPrimary
    val bridgedMiuix = nativeMiuixColors
        ?: resolveMiuixColorsFromMaterialBridge(createMiuixMaterialBridge(material), darkTheme)
    // Both hosts can contain Miuix components. Apply explicit secondary edits after
    // either bridge, while unedited secondary controls retain their native neutral role.
    val selectionMiuix = if (useNativeCustomSelection) bridgedMiuix.copy(
        primaryContainer = material.primaryContainer,
        onPrimaryContainer = material.onPrimaryContainer,
        secondaryContainer = material.secondaryContainer,
        onSecondaryContainer = material.onSecondaryContainer,
    ) else bridgedMiuix
    val miuix = if (source == Md3ColorSource.WALLPAPER_RETONED &&
        WallpaperPaletteRole.SECONDARY in wallpaperOverrides.forMode(darkTheme)
    ) selectionMiuix.copy(secondary = material.secondary, onSecondary = material.onSecondary)
        else selectionMiuix
    val aligned = if (nativeMiuix) {
        alignMaterialSurfacesWithMiuix(
            material, miuix, preserveMaterialAccentRoles = preserveGeneratedPalette || source == Md3ColorSource.WALLPAPER_RETONED,
        )
    } else material
    return AppThemePalette(aligned, miuix, aligned.primary)
}

/** Preserve the picked accent family instead of jumping straight to black/white at the threshold. */
internal fun resolveCustomSelectionContentColor(accent: Color, container: Color): Color {
    val target = resolveReadableForeground(container)
    for (step in 0..256) {
        val candidate = lerp(accent, target, step / 256f)
        if (calculateContrastRatio(candidate, container) >= ACCESSIBLE_TEXT_MIN_CONTRAST) {
            return candidate
        }
    }
    return target
}
