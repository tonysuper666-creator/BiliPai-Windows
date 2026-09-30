// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/SettingsVisualSpec.kt; do not edit.
// LF-normalized SHA-256: 6112e0f7bae983128f14c56a312ae280add61c567ce93ca1c08ae193db65fc42
package com.android.purebilibili.feature.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class SettingsVisualSpec(
    val screenHorizontalPadding: Dp = 16.dp,
    val sectionTopSpacing: Dp = 20.dp,
    val sectionBottomSpacing: Dp = 8.dp,
    val groupCornerRadius: Dp = 14.dp,
    val categoryIconBubbleSize: Dp = 36.dp,
    val categoryIconSize: Dp = 20.dp,
    val categoryRowVerticalPadding: Dp = 12.dp,
    val searchBarVerticalPadding: Dp = 10.dp,
)

internal fun resolveSettingsVisualSpec(): SettingsVisualSpec = SettingsVisualSpec()

internal enum class SettingsPageScrollHost {
    LazyColumn,
    External,
}
