// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicLayoutPolicy.kt; do not edit.
// LF-normalized SHA-256: 220eebe0235247bef47940cb6192652edeb19031ff89503c6bc72465d96f80f9
package com.android.purebilibili.feature.dynamic
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopDynamicSettings as SettingsManager

internal fun resolveDynamicTimelineMaxWidth(): Dp = 1840.dp

internal fun resolveDynamicTimelineMinColumnWidth(): Dp = 360.dp

internal fun resolveDynamicTimelineHorizontalSpacing(): Dp = 18.dp

internal fun resolveDynamicTimelineVerticalSpacing(): Dp = 10.dp

internal fun shouldUseDynamicManualPrependAnchor(
    feedLayoutMode: SettingsManager.DynamicFeedLayoutMode,
): Boolean = feedLayoutMode == SettingsManager.DynamicFeedLayoutMode.LIST
