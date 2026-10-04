package com.bilipai.desktop.appearance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.ProvideAppThemeConfig
import com.android.purebilibili.core.ui.LocalAppIconStyle
import com.android.purebilibili.core.ui.LocalAppListItemStyle
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppCard
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.settings.resolveThemePreferenceState
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

internal val LocalDesktopDarkTheme = staticCompositionLocalOf { false }

/** Windows system preferences can differ from the selected app theme.
 * Original components choose their colors using Root's effective palette. */
@Composable internal fun isDesktopInDarkTheme(): Boolean = LocalDesktopDarkTheme.current

internal data class DesktopAppearancePalette(
    val material: ColorScheme,
    val miuixLight: Colors,
    val miuixDark: Colors,
    val baseRoles: ThemeRoleOverrides,
    val dark: Boolean,
    val amoled: Boolean,
)

/** The Android-free branches of the original PureBiliBiliTheme, with Windows wallpaper fallback. */
internal fun buildDesktopAppearancePalette(settings: DesktopThemeSettings, systemDark: Boolean): DesktopAppearancePalette {
    val state = resolveThemePreferenceState(settings.themeMode, settings.darkThemeStyle, systemDark)
    val seed = resolveMd3ThemeSeedColor(settings.md3ColorSource, settings.md3CustomColorHex, settings.themeColorIndex)
    fun base(dark: Boolean) = createBiliPaiStyleColorScheme(
        seed, dark, dark && state.useAmoledDarkTheme, settings.colorStyle, settings.colorSpec, settings.uiStyle,
        // No Android Monet service exists on Windows. Use the original non-S fallback seed.
        dynamicBaseScheme = null,
    )
    val lightBase = base(false); val darkBase = base(true)
    val roles = resolveEffectiveThemeRoleOverrides(settings.md3ColorSource, settings.themeRoleOverrides, settings.uiStyle)
    val light = applyThemeRoleOverrides(lightBase, roles, false)
    val dark = applyThemeRoleOverrides(darkBase, roles, true)
    val native = shouldUseNativeMiuixPalette(settings.uiStyle)
    val miuixLight = if (native) resolveNativeMiuixColors(light, false)
        else resolveMiuixColorsFromMaterialBridge(createMiuixMaterialBridge(light), false)
    val miuixDark = if (native) resolveNativeMiuixColors(dark, true)
        else resolveMiuixColorsFromMaterialBridge(createMiuixMaterialBridge(dark), true)
    val material = if (state.useDarkTheme) dark else light
    return DesktopAppearancePalette(
        if (native) alignMaterialSurfacesWithMiuix(material, if (state.useDarkTheme) miuixDark else miuixLight) else material,
        miuixLight, miuixDark, themeRoleOverridesFromSchemes(lightBase, darkBase), state.useDarkTheme, state.useAmoledDarkTheme,
    )
}

@Composable
fun DesktopAppearanceTheme(
    settings: DesktopThemeSettings,
    systemLanguageTags: List<String>? = null,
    windowSmallestWidthDp: Int = 0,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val palette = remember(settings, systemDark) { buildDesktopAppearancePalette(settings, systemDark) }
    val languages = systemLanguageTags ?: remember { WindowsUiLanguage.preferredTags() }
    val strings = remember(settings.appLanguage, languages) { DesktopStrings.forLanguage(settings.appLanguage, languages) }
    val density = LocalDensity.current
    val systemDensity = LocalDesktopWindowsSystemDensity.current ?: density
    val windowsMultiplier = density.density / systemDensity.density
    val metrics = remember(density.density, systemDensity.density, windowSmallestWidthDp, settings.appFontSizePreset) {
        // Android's persisted display preset/override remains archived; Windows uses exactly
        // its real system DPI multiplied by the user zoom in the outer Main scope.
        buildDisplayMetricsSnapshot((systemDensity.density * 160).toInt(),
            (windowSmallestWidthDp * windowsMultiplier).toInt(), AppUiScalePreset.STANDARD,
            settings.appFontSizePreset, null).copy(
                effectiveDensityMultiplier = windowsMultiplier,
                effectiveDensityDpi = (density.density * 160).toInt(),
                effectiveSmallestWidthDp = windowSmallestWidthDp,
                isNarrowWidth = windowSmallestWidthDp in 1 until 360)
    }
    val typography = remember(settings.uiStyle, settings.appFontSizePreset, settings.appFontWeightPreset) {
        resolveMaterialTypography(settings.uiStyle).scaled(settings.appFontSizePreset.multiplier)
            .withFontWeight(settings.appFontWeightPreset.fontWeight)
    }
    val textStyles = remember(typography) { typography.toMiuixTextStyles() }
    val controller = remember(settings.themeMode, palette) {
        ThemeController(resolveMiuixColorSchemeMode(settings.themeMode, false),
            lightColors = palette.miuixLight, darkColors = palette.miuixDark, isDark = palette.dark)
    }
    CompositionLocalProvider(
        LocalDesktopDarkTheme provides palette.dark,
        LocalAppUiStyle provides settings.uiStyle,
        LocalAppIconStyle provides settings.appIconStyle,
        LocalAppListItemStyle provides settings.appListItemStyle,
        LocalDynamicColorActive provides false,
        LocalBaseThemeRoleOverrides provides palette.baseRoles,
        LocalCornerRadiusScale provides resolveCornerRadiusScale(settings.uiStyle),
        LocalDesktopStrings provides strings,
        LocalDisplayMetricsSnapshot provides metrics,
        LocalDensity provides density,
    ) {
        MiuixTheme(controller = controller, textStyles = textStyles) {
            MaterialTheme(colorScheme = palette.material, typography = typography,
                shapes = resolveMaterialShapes(settings.uiStyle), motionScheme = resolveMaterialMotionScheme(settings.uiStyle)) {
                ProvideAppThemeConfig(buildDesktopAppThemeConfig(settings), content)
            }
        }
    }
}

internal fun buildDesktopAppThemeConfig(settings: DesktopThemeSettings) = AppThemeConfig(
    blurIntensity = settings.blurIntensity,
    headerBlurEnabled = settings.headerBlurEnabled,
    bottomBarBlurEnabled = settings.bottomBarBlurEnabled,
    progressiveTopBlurEnabled = settings.progressiveTopBlurEnabled,
    progressiveTopFadeEnabled = settings.progressiveTopFadeEnabled,
    liquidGlassEnabled = settings.liquidGlassEnabled,
    hapticFeedbackEnabled = settings.hapticFeedbackEnabled,
    globalTextTapCopyEnabled = settings.globalTextTapCopyEnabled,
    uiEntranceAnimationEnabled = settings.uiEntranceAnimationEnabled,
    runtimeVisualGuardEnabled = settings.runtimeVisualGuardEnabled,
)

/** Thin Windows public boundary; selection and rendering use the original App* components. */
@Composable
fun DesktopAppearanceText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge, maxLines: Int = Int.MAX_VALUE) {
    AppText(text, modifier, color = color, style = style, maxLines = maxLines)
}

@Composable
fun DesktopAppearanceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier = modifier, content = content)
}

@Composable
fun DesktopAppearanceButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    AppButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled) {
        DesktopAppearanceText(text)
    }
}

@Composable
fun DesktopAppearanceProgress(modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    AppCircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
}
