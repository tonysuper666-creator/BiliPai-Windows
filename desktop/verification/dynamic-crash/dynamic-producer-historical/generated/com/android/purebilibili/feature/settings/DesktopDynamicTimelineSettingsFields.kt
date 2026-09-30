// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt; do not edit.
// LF-normalized SHA-256: c8178e7a048512348678555b529180f7a969a658eca4019d484aff8603581a1b
package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopDynamicSettings as SettingsManager
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem

/** Original FeedApiSection controls backed by both actual settings and feed consumers. */
@Composable
internal fun DesktopDynamicTimelineSettingsFields(
    incrementalTimelineRefreshEnabled: Boolean,
    onIncrementalTimelineRefreshChange: (Boolean) -> Unit,
    dynamicFeedLayoutMode: SettingsManager.DynamicFeedLayoutMode,
    onDynamicFeedLayoutModeChange: (SettingsManager.DynamicFeedLayoutMode) -> Unit,
) {
    val siblingTints = remember { resolveSettingsSiblingIconTints(9, paletteOffset = 1) }
    val feedIcon = rememberSettingsSemanticIcon(SettingsIconRole.FEED_API)
    val refreshIcon = rememberSettingsSemanticIcon(SettingsIconRole.REFRESH_COUNT)
    SettingsCardGroup {
        SettingSwitchItem(
            icon = refreshIcon,
            title = "刷新时保留当前列表",
            subtitle = "下拉刷新只把新动态加到顶部，不重新排列已看到的内容",
            checked = incrementalTimelineRefreshEnabled,
            onCheckedChange = onIncrementalTimelineRefreshChange,
            iconTint = siblingTints[1]
        )
        SettingsAdaptiveDivider()
        SettingsSingleChoicePreference(
            title = "动态页面布局",
            subtitle = "瀑布流会按屏幕宽度显示多列；列表模式固定为单列",
            options = com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode.entries.map { mode ->
                com.android.purebilibili.core.ui.components.AppSegmentOption(
                    value = mode,
                    label = mode.label
                )
            },
            selectedValue = dynamicFeedLayoutMode,
            icon = feedIcon,
            iconTint = siblingTints[6],
            onSelectionChange = onDynamicFeedLayoutModeChange,
        )
    }
}
