package com.android.purebilibili.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

internal fun resolveAppTabLabelFontSize(
    requested: TextUnit,
    nativeDefault: TextUnit,
    liquidGlassEnabled: Boolean,
): TextUnit {
    val resolved = if (requested.isSpecified) requested else nativeDefault
    // Glass labels need readable text independently of capsule/slot size. Explicit larger
    // labels (for example 15sp channel docks) and relative units retain their caller intent.
    // Never apply this floor to disabled MD3/Miuix typography.
    return if (liquidGlassEnabled && resolved.type == TextUnitType.Sp && resolved.value < 14f) {
        14.sp
    } else resolved
}

/** Glass tabs use at least 14sp; disabled materials retain the active theme/caller typography. */
@Composable
fun appTabLabelFontSize(requested: TextUnit = TextUnit.Unspecified): TextUnit = resolveAppTabLabelFontSize(
    requested = requested,
    nativeDefault = MaterialTheme.typography.labelMedium.fontSize,
    liquidGlassEnabled = LocalAppThemeConfig.current.liquidGlassEnabled,
)
