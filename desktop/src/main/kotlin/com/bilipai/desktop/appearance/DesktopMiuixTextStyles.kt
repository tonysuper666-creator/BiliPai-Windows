package com.bilipai.desktop.appearance

import com.android.purebilibili.core.theme.AppFontSizePreset
import com.android.purebilibili.core.theme.AppFontWeightPreset
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.resolveMaterialTypography
import com.android.purebilibili.core.theme.scaled
import com.android.purebilibili.core.theme.toMiuixTextStyles
import com.android.purebilibili.core.theme.withFontWeight
import top.yukonga.miuix.kmp.theme.TextStyles

/**
 * Native Miuix text styles for the Windows theme, in the order the original Theme.kt uses:
 * convert the Material baseline first, then apply the user's font size and weight presets to the
 * resulting [TextStyles]. The v0.4.6 conversion pins title1..title4 to a fixed 32/24/20/18sp
 * Medium scale, so scaling or re-weighting the Material typography BEFORE the conversion would be
 * silently overwritten for those four roles. There is no font family on Windows.
 */
internal fun resolveDesktopMiuixTextStyles(
    uiStyle: AppUiStyle,
    fontSize: AppFontSizePreset,
    fontWeight: AppFontWeightPreset,
): TextStyles = resolveMaterialTypography(uiStyle)
    .toMiuixTextStyles()
    .scaled(fontSize.multiplier)
    .withFontWeight(fontWeight.fontWeight)
