// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/PlaybackSettingsSelectionPolicy.kt; do not edit.
// LF-normalized SHA-256: d44af5c39cfc095dcb369461781d098d4e69aa2d466afb0b2b63eb5083c1a77a
package com.android.purebilibili.feature.settings
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.core.ui.components.AppSegmentOption

internal fun resolveHomeFeedCardWidthPresetSegmentOptions(): List<AppSegmentOption<HomeFeedCardWidthPreset>> {
    return listOf(
        AppSegmentOption(HomeFeedCardWidthPreset.AUTO, "自动"),
        AppSegmentOption(HomeFeedCardWidthPreset.COMPACT, "紧凑"),
        AppSegmentOption(HomeFeedCardWidthPreset.BALANCED, "均衡"),
        AppSegmentOption(HomeFeedCardWidthPreset.WIDE, "宽"),
        AppSegmentOption(HomeFeedCardWidthPreset.ULTRA_WIDE, "超宽")
    )
}
