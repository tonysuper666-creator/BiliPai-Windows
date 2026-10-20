package com.bilipai.desktop.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.SeedPaletteStyle
import com.android.purebilibili.core.theme.SeedPresetColorNames
import com.android.purebilibili.core.theme.SeedPresetColors
import com.android.purebilibili.core.theme.ThemeColorNames
import com.android.purebilibili.core.theme.ThemeColors
import com.android.purebilibili.core.theme.ThemePresetPaletteSwatch
import com.android.purebilibili.core.theme.rememberThemePresetPaletteSwatches
import com.android.purebilibili.core.theme.resolveColorSchemeIsDark
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.settings.Md3ColorSource
import com.android.purebilibili.feature.settings.ThemeStyleSwatch
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/** The colour list one palette grid shows, with the display name of every colour. */
internal data class DesktopThemePresetList(val colors: List<Color>, val names: List<String>)

/**
 * The extended seed palette styles use their own 19 preset colours (independent of the persisted ThemeColors indices);
 * the other styles keep the original ThemeColors list. Same selection as the original appearance screen.
 */
internal fun resolveDesktopThemePresetList(seedStyleEnabled: Boolean): DesktopThemePresetList =
    if (seedStyleEnabled) DesktopThemePresetList(SeedPresetColors, SeedPresetColorNames)
    else DesktopThemePresetList(ThemeColors, ThemeColorNames)

/**
 * Preset colours of the Windows appearance page as swatch cells: the upper half of every circle is the primary role,
 * the lower half the tertiary role and the primary container, computed for the current style, spec, contrast and
 * light or dark theme, so the cell shows what the colour will look like. [onPick] gets the index inside the list
 * returned by [resolveDesktopThemePresetList] and the colour itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopThemePresetPalette(
    seedStyle: SeedPaletteStyle,
    paletteStyle: PaletteStyle,
    colorSpec: ColorSpec.SpecVersion,
    colorContrast: Contrast,
    selectedSeed: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPick: (Int, Color) -> Unit,
) {
    val presets = remember(seedStyle.enabled) { resolveDesktopThemePresetList(seedStyle.enabled) }
    // One computation per style change: the whole list is generated together, not once per recomposed cell.
    val swatches = rememberThemePresetPaletteSwatches(
        seedColors = presets.colors,
        isDark = resolveColorSchemeIsDark(MaterialTheme.colorScheme),
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
        colorContrast = colorContrast,
        seedPaletteStyle = seedStyle,
    )
    val accent = AppContentColors.accent()
    val secondary = AppContentColors.secondaryText()
    val cellShape = AppShapes.container(ContainerLevel.Card)
    FlowRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        presets.colors.forEachIndexed { index, color ->
            val selected = color == selectedSeed
            // A missing preview falls back to the seed colour itself, so a cell never turns blank.
            val swatch = swatches.getOrElse(index) { ThemePresetPaletteSwatch(color, color, color) }
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(cellShape)
                        .background(if (seedStyle.enabled) swatch.inverseOnSurface else AppSurfaceTokens.cardContainer())
                        .then(if (selected) Modifier.border(2.dp, accent, cellShape) else Modifier)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onPick(index, color) },
                    contentAlignment = Alignment.Center,
                ) {
                    ThemeStyleSwatch(swatch, size = 48.dp)
                    if (selected) {
                        Box(
                            Modifier.size(22.dp).background(
                                if (seedStyle.enabled) swatch.surfaceContainer else AppSurfaceTokens.cardContainer(), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(Icons.Filled.Check, contentDescription = null, tint = swatch.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                AppText(
                    text = presets.names.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) accent else secondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** What choosing a preset writes: the original colour index while no seed style is on in preset mode, else a custom colour. */
internal enum class DesktopPresetPick { THEME_COLOR_INDEX, CUSTOM_COLOR }

internal fun resolveDesktopPresetPick(seedStyleEnabled: Boolean, source: Md3ColorSource): DesktopPresetPick =
    if (!seedStyleEnabled && source == Md3ColorSource.FOLLOW_WALLPAPER) DesktopPresetPick.THEME_COLOR_INDEX
    else DesktopPresetPick.CUSTOM_COLOR
