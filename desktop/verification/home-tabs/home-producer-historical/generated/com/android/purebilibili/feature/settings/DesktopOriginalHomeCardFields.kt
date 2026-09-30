// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/screen/AppearanceSettingsScreen.kt; do not edit.
// LF-normalized SHA-256: c18aa754ff40d18d6f706c73d8be5ab9a26eda63b4b987be7ae609fe3d372836
package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.ui.components.AppSegmentOption

/** Three real original controls, bound to the actual four-key desktop grid consumer. */
@Composable
internal fun DesktopOriginalHomeCardFields(state:DesktopHomeCardSettings,isTablet:Boolean,
 onGridColumnCount:(Int)->Unit,onWidthPreset:(HomeFeedCardWidthPreset)->Unit,onStyle:(HomeFeedCardStyle)->Unit) {
 val homeFeedCardStyle=state.homeFeedCardStyle
 SettingsCardGroup {
    if(isTablet) {
        SettingsSingleChoicePreference(
                                            icon = rememberSettingsSemanticIcon(SettingsIconRole.HOME_FEED),
                                            iconTint = com.android.purebilibili.core.theme.iOSBlue,
                                            title = "网格列数",
                                            subtitle = if (state.gridColumnCount == 0) {
                                                "自适应（默认）；窄屏独立记忆"
                                            } else {
                                                "宽屏固定 ${state.gridColumnCount} 列；窄屏独立记忆"
                                            },
                                            options = (0..6).map { count ->
                                                AppSegmentOption(
                                                    value = count,
                                                    label = if (count == 0) "自动" else "$count 列",
                                                )
                                            },
                                            selectedValue = state.gridColumnCount,
                                            onSelectionChange = onGridColumnCount,
                                        )
        SettingsAdaptiveDivider()
        SettingsSingleChoicePreference(
                                            title = "推荐流卡片宽度",
                                            subtitle = if (state.gridColumnCount > 0) {
                                                "当前固定 ${state.gridColumnCount} 列优先生效，自动列数时使用该宽度"
                                            } else {
                                                "自动列数时控制首页推荐卡片的最小宽度"
                                            },
                                            options = resolveHomeFeedCardWidthPresetSegmentOptions(),
                                            selectedValue = state.homeFeedCardWidthPreset,
                                            onSelectionChange = onWidthPreset,
                                        )
        SettingsAdaptiveDivider()
    }
    SettingsSingleChoicePreference(
                                title = "卡片封面比例：${homeFeedCardStyle.label}",
                                subtitle = homeFeedCardStyle.subtitle + "（推荐、热门、分区等视频列表）",
                                options = HomeFeedCardStyle.entries.map {
                                    AppSegmentOption(it, it.label)
                                },
                                selectedValue = homeFeedCardStyle,
                                onSelectionChange = {
                                    onStyle(it)
                                }
                            )
 }
}
