package com.bilipai.desktop.ui
import com.android.purebilibili.core.store.*
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
internal fun decodeDesktopFavoriteHomeAppearance(preferences:DesktopPreferenceSnapshot):DesktopFavoriteHomeAppearance {
        val headerBlurMode = resolveHomeHeaderBlurModePreference(
            rawMode = preferences[favoriteIntKey("home_header_blur_mode")],
            legacyEnabled = preferences[favoriteBooleanKey("header_blur_enabled")]
        )
        return DesktopFavoriteHomeAppearance(
headerBlurMode = headerBlurMode,
isBottomBarBlurEnabled = preferences[favoriteBooleanKey("bottom_bar_blur_enabled")] ?: false,
isBottomBarSearchEnabled = preferences[favoriteBooleanKey("bottom_bar_search_enabled")] ?: false,
listScopedSearchEnabled = preferences[favoriteBooleanKey("list_scoped_search_enabled")] ?: false,
androidNativeLiquidGlassEnabled =
                preferences[favoriteBooleanKey("android_native_liquid_glass_enabled")]
                    ?: false,
commonListHeaderCollapseMode = CommonListHeaderCollapseMode.fromValue(
                preferences[favoriteIntKey("common_list_header_collapse_mode")]
                    ?: CommonListHeaderCollapseMode.SHOW_ON_REVERSE_SCROLL.value
            ),
pinchToChangeGridColumnsEnabled =
                preferences[favoriteBooleanKey("pinch_to_change_grid_columns_enabled")] ?: true,
cardAnimationEnabled = preferences[favoriteBooleanKey("card_animation_enabled")] ?: false,
cardTransitionEnabled = preferences[favoriteBooleanKey("card_transition_enabled")] ?: true,
homeDurationStyle = preferences[favoriteIntKey("home_duration_style")]
                ?.let(HomeDurationStyle::fromValue)
                ?: if (preferences[favoriteBooleanKey("home_video_duration_badges_visible")] ?: true) {
                    HomeDurationStyle.OUTSIDE_COVER
                } else {
                    HomeDurationStyle.HIDDEN
                }
        )
}
