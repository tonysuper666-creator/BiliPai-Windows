package com.bilipai.desktop.appearance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.renderer.miuix.AppMiuixCircularProgressIndicator
import com.android.purebilibili.core.ui.renderer.miuix.AppMiuixText
import com.android.purebilibili.feature.settings.resolveThemePreferenceState
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

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
    val metrics = remember(density.density, windowSmallestWidthDp, settings.appUiScalePreset, settings.appFontSizePreset, settings.appDpiOverridePercent) {
        buildDisplayMetricsSnapshot((density.density * 160).toInt(), windowSmallestWidthDp,
            settings.appUiScalePreset, settings.appFontSizePreset, settings.appDpiOverridePercent.takeIf { it != 0 })
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
        LocalAppUiStyle provides settings.uiStyle,
        LocalDynamicColorActive provides false,
        LocalBaseThemeRoleOverrides provides palette.baseRoles,
        LocalCornerRadiusScale provides resolveCornerRadiusScale(settings.uiStyle),
        LocalDesktopStrings provides strings,
        LocalDisplayMetricsSnapshot provides metrics,
        LocalDensity provides Density(density.density * metrics.effectiveDensityMultiplier, density.fontScale),
    ) {
        MiuixTheme(controller = controller, textStyles = textStyles) {
            MaterialTheme(colorScheme = palette.material, typography = typography,
                shapes = resolveMaterialShapes(settings.uiStyle), motionScheme = resolveMaterialMotionScheme(settings.uiStyle), content = content)
        }
    }
}

/** Thin Windows public boundary; the Miuix text/progress renderers are exact upstream files. */
@Composable
fun DesktopAppearanceText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge, maxLines: Int = Int.MAX_VALUE) {
    if (LocalAppUiStyle.current == AppUiStyle.MIUIX) AppMiuixText(
        text, modifier, color, null, TextUnit.Unspecified, null, null, null, TextUnit.Unspecified,
        null, null, TextUnit.Unspecified, TextOverflow.Clip, true, maxLines, 1, null, style,
    ) else androidx.compose.material3.Text(text, modifier, color = color, style = style, maxLines = maxLines)
}

@Composable
fun DesktopAppearanceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (LocalAppUiStyle.current == AppUiStyle.MIUIX) MiuixCard(modifier = modifier, content = content)
    else androidx.compose.material3.Card(modifier = modifier, content = content)
}

@Composable
fun DesktopAppearanceButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (LocalAppUiStyle.current == AppUiStyle.MIUIX) MiuixButton(onClick = onClick, modifier = modifier, enabled = enabled, minHeight = 48.dp) {
        DesktopAppearanceText(text)
    } else androidx.compose.material3.Button(onClick, modifier = modifier, enabled = enabled) { DesktopAppearanceText(text) }
}

@Composable
fun DesktopAppearanceProgress(modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    if (LocalAppUiStyle.current == AppUiStyle.MIUIX) AppMiuixCircularProgressIndicator(modifier, Color.Unspecified, strokeWidth, Color.Unspecified)
    else androidx.compose.material3.CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
}
