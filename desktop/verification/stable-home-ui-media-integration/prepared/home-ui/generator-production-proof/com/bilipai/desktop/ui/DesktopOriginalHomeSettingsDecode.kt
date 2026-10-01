package com.bilipai.desktop.ui
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.core.store.navigation.parseBottomBarItemLabels
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.plugins.stringPreferencesKey
private val intPreferencesKey=::favoriteIntKey
private val floatPreferencesKey=::favoriteFloatKey
private val KEY_ANDROID_NATIVE_LIQUID_GLASS_ENABLED = booleanPreferencesKey("android_native_liquid_glass_enabled")
private val KEY_BOTTOM_BAR_BLUR_ENABLED = booleanPreferencesKey("bottom_bar_blur_enabled")
private val KEY_BOTTOM_BAR_FLOATING = booleanPreferencesKey("bottom_bar_floating")
private val KEY_BOTTOM_BAR_ITEM_COLORS = stringPreferencesKey("bottom_bar_item_colors")
private val KEY_BOTTOM_BAR_LABEL_MODE = intPreferencesKey("bottom_bar_label_mode")
private val KEY_BOTTOM_BAR_LIQUID_GLASS_ENABLED = booleanPreferencesKey("bottom_bar_liquid_glass_enabled")
private val KEY_BOTTOM_BAR_ORDER = stringPreferencesKey("bottom_bar_order")
private val KEY_BOTTOM_BAR_SEARCH_AUTO_EXPAND_MODE = intPreferencesKey("bottom_bar_search_auto_expand_mode")
private val KEY_BOTTOM_BAR_SEARCH_ENABLED = booleanPreferencesKey("bottom_bar_search_enabled")
private val KEY_BOTTOM_BAR_SEARCH_LAYOUT_MODE = intPreferencesKey("bottom_bar_search_layout_mode")
private val KEY_BOTTOM_BAR_VISIBILITY_MODE = intPreferencesKey("bottom_bar_visibility_mode")
private val KEY_BOTTOM_BAR_VISIBLE_TABS = stringPreferencesKey("bottom_bar_visible_tabs")
private val KEY_CARD_ANIMATION_ENABLED = booleanPreferencesKey("card_animation_enabled")
private val KEY_CARD_TRANSITION_ENABLED = booleanPreferencesKey("card_transition_enabled")
private val KEY_COMMON_LIST_HEADER_COLLAPSE_MODE = intPreferencesKey("common_list_header_collapse_mode")
private val KEY_COMPACT_VIDEO_STATS_ON_COVER = booleanPreferencesKey("compact_video_stats_on_cover")
private val KEY_CRASH_TRACKING_CONSENT_SHOWN = booleanPreferencesKey("crash_tracking_consent_shown")
private val KEY_DISPLAY_MODE = intPreferencesKey("display_mode")
private val KEY_EASTER_EGG_ENABLED = booleanPreferencesKey("easter_egg_enabled")
private val KEY_FULL_VIDEO_CARD_CONTENT_VISIBLE = booleanPreferencesKey("full_video_card_content_visible")
private val KEY_GRID_COLUMN_COUNT = intPreferencesKey("grid_column_count")
private val KEY_GRID_COLUMN_COUNT_COMPACT = intPreferencesKey("grid_column_count_compact")
private val KEY_HEADER_BLUR_ENABLED = booleanPreferencesKey("header_blur_enabled")
private val KEY_HEADER_COLLAPSE_ENABLED = booleanPreferencesKey("header_collapse_enabled")
private val KEY_HIDE_TOP_TABS = booleanPreferencesKey("hide_top_tabs")
private val KEY_HOME_BAR_HIDE_TYPE = intPreferencesKey("home_bar_hide_type")
private val KEY_HOME_CARD_DYNAMIC_TINT_ENABLED = booleanPreferencesKey("home_card_dynamic_tint_enabled")
private val KEY_HOME_CARD_FROSTED_GLASS_ENABLED = booleanPreferencesKey("home_card_frosted_glass_enabled")
private val KEY_HOME_DURATION_STYLE = intPreferencesKey("home_duration_style")
private val KEY_HOME_FEED_CARD_STYLE = intPreferencesKey("home_feed_card_style")
private val KEY_HOME_FEED_CARD_WIDTH_PRESET = intPreferencesKey("home_feed_card_width_preset")
private val KEY_HOME_HEADER_BLUR_MODE = intPreferencesKey("home_header_blur_mode")
private val KEY_HOME_HEADER_COLLAPSE_MODE = intPreferencesKey("home_header_collapse_mode")
private val KEY_HOME_HERO_CAROUSEL_AUTOPLAY_ENABLED = booleanPreferencesKey("home_hero_carousel_autoplay_enabled")
private val KEY_HOME_HERO_CAROUSEL_ENABLED = booleanPreferencesKey("home_hero_carousel_enabled")
private val KEY_HOME_PUBLISH_TIME_VISIBLE = booleanPreferencesKey("home_publish_time_visible")
private val KEY_HOME_REFRESH_TIP_VISIBLE = booleanPreferencesKey("home_refresh_tip_visible")
private val KEY_HOME_SEARCH_LIQUID_GLASS_ENABLED = booleanPreferencesKey("home_search_liquid_glass_enabled")
private val KEY_HOME_TOP_LAYOUT_ORDER = intPreferencesKey("home_top_layout_order")
private val KEY_HOME_TOP_RIGHT_ACTION = intPreferencesKey("home_top_right_action")
private val KEY_HOME_UP_AVATARS_VISIBLE = booleanPreferencesKey("home_up_avatars_visible")
private val KEY_HOME_UP_BADGES_VISIBLE = booleanPreferencesKey("home_up_badges_visible")
private val KEY_HOME_VIDEO_DURATION_BADGES_VISIBLE = booleanPreferencesKey("home_video_duration_badges_visible")
private val KEY_HOME_WALLPAPER_EFFECT_MODE = intPreferencesKey("home_wallpaper_effect_mode")
private val KEY_HOME_WALLPAPER_EFFECT_SCOPE = intPreferencesKey("home_wallpaper_effect_scope")
private val KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED = booleanPreferencesKey("linked_dock_merge_on_scroll_enabled")
private val KEY_LIQUID_GLASS_ADVANCED_PRESET = intPreferencesKey("liquid_glass_advanced_preset")
private val KEY_LIQUID_GLASS_CHROMATIC_ABERRATION = floatPreferencesKey("liquid_glass_chromatic_aberration")
private val KEY_LIQUID_GLASS_CONTENT_DISTORTION = floatPreferencesKey("liquid_glass_content_distortion")
private val KEY_LIQUID_GLASS_CONTENT_READABILITY = floatPreferencesKey("liquid_glass_content_readability")
private val KEY_LIQUID_GLASS_ENABLED = booleanPreferencesKey("liquid_glass_enabled")
private val KEY_LIQUID_GLASS_MODE = intPreferencesKey("liquid_glass_mode")
private val KEY_LIQUID_GLASS_PROGRESS = floatPreferencesKey("liquid_glass_material_progress_v2")
private val KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_CURVE = floatPreferencesKey("liquid_glass_progressive_blur_curve")
private val KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_EXTENT = floatPreferencesKey("liquid_glass_progressive_blur_extent")
private val KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_RADIUS = floatPreferencesKey("liquid_glass_progressive_blur_radius")
private val KEY_LIQUID_GLASS_STRENGTH = floatPreferencesKey("liquid_glass_strength")
private val KEY_LIQUID_GLASS_STYLE = intPreferencesKey("liquid_glass_style")
private val KEY_LIST_SCOPED_SEARCH_ENABLED = booleanPreferencesKey("list_scoped_search_enabled")
private val KEY_LOW_QUALITY_HOME_COVER_IN_DATA_SAVER = booleanPreferencesKey("low_quality_home_cover_in_data_saver")
private val KEY_MIUIX_TRANSITION_BLUR_ENABLED = booleanPreferencesKey("miuix_transition_blur_enabled")
private val KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED = booleanPreferencesKey("navigation_icon_cross_scale_enabled")
private val KEY_PINCH_TO_CHANGE_GRID_COLUMNS_ENABLED = booleanPreferencesKey("pinch_to_change_grid_columns_enabled")
private val KEY_PREDICTIVE_BACK_ANIMATION_STYLE = stringPreferencesKey("predictive_back_animation_style")
private val KEY_PREDICTIVE_BACK_ENABLED = booleanPreferencesKey("predictive_back_enabled")
private val KEY_PREDICTIVE_BACK_EXIT_DIRECTION = stringPreferencesKey("predictive_back_exit_direction")
private val KEY_RUNTIME_VISUAL_GUARD_ENABLED = booleanPreferencesKey("runtime_visual_guard_enabled")
private val KEY_SHOW_PGC_TIMELINE = booleanPreferencesKey("show_pgc_timeline")
private val KEY_SIDEBAR_ACCOUNT_SWITCHER_ENABLED = booleanPreferencesKey("sidebar_account_switcher_enabled")
private val KEY_SIDEBAR_EXPANDED = booleanPreferencesKey("sidebar_expanded")
private val KEY_TABLET_NAVIGATION_MODE = booleanPreferencesKey("tablet_use_sidebar")
private val KEY_TOP_BAR_LIQUID_GLASS_ENABLED = booleanPreferencesKey("top_bar_liquid_glass_enabled")
private val KEY_TOP_TAB_LABEL_MODE = intPreferencesKey("top_tab_label_mode")
private val KEY_TOP_TAB_ORDER = stringPreferencesKey("top_tab_order")
private val KEY_TOP_TAB_VISIBLE_TABS = stringPreferencesKey("top_tab_visible_tabs")
private val KEY_VIDEO_CARD_LONG_PRESS_ACTION_ENABLED = booleanPreferencesKey("video_card_long_press_action_enabled")
private val KEY_VIDEO_SHARED_RETURN_GESTURE_FOLLOW_ENABLED = booleanPreferencesKey("video_shared_return_gesture_follow_enabled")
private val KEY_VIDEO_SHARED_TRANSITION_CUSTOM_DURATION_MILLIS = intPreferencesKey("video_shared_transition_custom_duration_millis")
private val KEY_VIDEO_SHARED_TRANSITION_SPEED = intPreferencesKey("video_shared_transition_speed")
private val bottomBarItemLabelsPreferencesKey=stringPreferencesKey("bottom_bar_item_labels")
private val liquidGlassReadabilityModePreferencesKey=intPreferencesKey("liquid_glass_readability_mode")
private val miuixPredictiveBackMaxProgressPercentPreferencesKey=intPreferencesKey("miuix_predictive_back_max_progress_percent")

internal fun decodeDesktopOriginalHomeSettings(preferences: DesktopPreferenceSnapshot): HomeSettings {
    val headerBlurMode = resolveHomeHeaderBlurModePreference(
        rawMode = preferences[KEY_HOME_HEADER_BLUR_MODE],
        legacyEnabled = preferences[KEY_HEADER_BLUR_ENABLED]
    )
    val headerCollapseMode = preferences[KEY_HOME_HEADER_COLLAPSE_MODE]
        ?.let(HomeHeaderCollapseMode::fromValue)
        ?: HomeHeaderCollapseMode.fromLegacyBoolean(
            preferences[KEY_HEADER_COLLAPSE_ENABLED] ?: true
        )
    val legacyLiquidGlassEnabled = preferences[KEY_LIQUID_GLASS_ENABLED] ?: false
    val legacyLiquidGlassStyle = LiquidGlassStyle.fromValue(
        preferences[KEY_LIQUID_GLASS_STYLE] ?: LiquidGlassStyle.SUKISU.value
    )
    val liquidGlassMode = preferences[KEY_LIQUID_GLASS_MODE]
        ?.let(LiquidGlassMode::fromValue)
        ?: resolveLegacyLiquidGlassMode(legacyLiquidGlassStyle)
    val liquidGlassStrength = normalizeLiquidGlassStrength(
        preferences[KEY_LIQUID_GLASS_STRENGTH]
            ?: resolveDefaultLiquidGlassStrength(liquidGlassMode)
    )
    val liquidGlassProgress = resolveStoredLiquidGlassProgress(
        progress = preferences[KEY_LIQUID_GLASS_PROGRESS],
        legacyModeValue = preferences[KEY_LIQUID_GLASS_MODE],
        legacyStrength = preferences[KEY_LIQUID_GLASS_STRENGTH],
        legacyStyleValue = preferences[KEY_LIQUID_GLASS_STYLE],
    )
    val liquidGlassReadabilityMode = LiquidGlassReadabilityMode.fromValue(
        preferences[liquidGlassReadabilityModePreferencesKey]
            ?: LiquidGlassReadabilityMode.STABLE.value
    )
    val liquidGlassAdvancedSettings = resolveLiquidGlassAdvancedSettings(
        presetValue = preferences[KEY_LIQUID_GLASS_ADVANCED_PRESET],
        progressiveBlurRadius = preferences[KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_RADIUS],
        progressiveBlurExtent = preferences[KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_EXTENT],
        progressiveBlurCurve = preferences[KEY_LIQUID_GLASS_PROGRESSIVE_BLUR_CURVE],
        contentReadability = preferences[KEY_LIQUID_GLASS_CONTENT_READABILITY],
        chromaticAberration = preferences[KEY_LIQUID_GLASS_CHROMATIC_ABERRATION],
        contentDistortion = preferences[KEY_LIQUID_GLASS_CONTENT_DISTORTION],
    )
    return HomeSettings(
        displayMode = preferences[KEY_DISPLAY_MODE] ?: 0,
        isBottomBarFloating = preferences[KEY_BOTTOM_BAR_FLOATING] ?: true,
        navigationIconCrossScaleEnabled =
            preferences[KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED] ?: true,
        bottomBarLabelMode = preferences[KEY_BOTTOM_BAR_LABEL_MODE] ?: DesktopOriginalHomeSettingConstants.BottomBarLabelMode.ICON_AND_TEXT,
        topTabLabelMode = preferences[KEY_TOP_TAB_LABEL_MODE] ?: DesktopOriginalHomeSettingConstants.TopTabLabelMode.TEXT_ONLY,
        hideTopTabs = preferences[KEY_HIDE_TOP_TABS] ?: false,
        homeTopRightAction = HomeTopRightAction.fromValue(
            preferences[KEY_HOME_TOP_RIGHT_ACTION] ?: HomeTopRightAction.SETTINGS.value
        ),
        homeTopLayoutOrder = HomeTopLayoutOrder.fromValue(
            preferences[KEY_HOME_TOP_LAYOUT_ORDER] ?: HomeTopLayoutOrder.SEARCH_THEN_TABS.value
        ),
        isHeaderBlurEnabled = headerBlurMode != HomeHeaderBlurMode.ALWAYS_OFF,
        headerBlurMode = headerBlurMode,
        isBottomBarBlurEnabled = preferences[KEY_BOTTOM_BAR_BLUR_ENABLED] ?: false,
        isTopBarLiquidGlassEnabled = preferences[KEY_TOP_BAR_LIQUID_GLASS_ENABLED] ?: false,
        isHomeSearchLiquidGlassEnabled =
            preferences[KEY_HOME_SEARCH_LIQUID_GLASS_ENABLED]
                ?: (preferences[KEY_TOP_BAR_LIQUID_GLASS_ENABLED] ?: false),
        isBottomBarLiquidGlassEnabled = preferences[KEY_BOTTOM_BAR_LIQUID_GLASS_ENABLED] ?: legacyLiquidGlassEnabled,
        isBottomBarSearchEnabled = preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] ?: false,
        linkedDockMergeOnScrollEnabled = preferences[KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED] ?: true,
        listScopedSearchEnabled = preferences[KEY_LIST_SCOPED_SEARCH_ENABLED] ?: false,
        bottomBarSearchAutoExpandMode = BottomBarSearchAutoExpandMode.fromValue(
            preferences[KEY_BOTTOM_BAR_SEARCH_AUTO_EXPAND_MODE]
                ?: BottomBarSearchAutoExpandMode.EXPAND_AT_HOME_TOP.value
        ),
        bottomBarSearchLayoutMode = BottomBarSearchLayoutMode.fromValue(
            preferences[KEY_BOTTOM_BAR_SEARCH_LAYOUT_MODE]
                ?: BottomBarSearchLayoutMode.FULL_DOCK.value
        ),
        androidNativeLiquidGlassEnabled =
            preferences[KEY_ANDROID_NATIVE_LIQUID_GLASS_ENABLED]
                ?: false,
        liquidGlassStyle = legacyLiquidGlassStyle,
        liquidGlassMode = liquidGlassMode,
        liquidGlassStrength = liquidGlassStrength,
        liquidGlassProgress = liquidGlassProgress,
        liquidGlassReadabilityMode = liquidGlassReadabilityMode,
        liquidGlassAdvancedSettings = liquidGlassAdvancedSettings,
        homeHeaderCollapseMode = headerCollapseMode,
        homeBarHideType = HomeBarHideType.fromValue(
            preferences[KEY_HOME_BAR_HIDE_TYPE] ?: HomeBarHideType.SYNC.value
        ),
        commonListHeaderCollapseMode = CommonListHeaderCollapseMode.fromValue(
            preferences[KEY_COMMON_LIST_HEADER_COLLAPSE_MODE]
                ?: CommonListHeaderCollapseMode.SHOW_ON_REVERSE_SCROLL.value
        ),
        isHeaderCollapseEnabled = headerCollapseMode.hasAnyCollapse,
        showPgcTimeline = preferences[KEY_SHOW_PGC_TIMELINE] ?: true,
        gridColumnCount = preferences[KEY_GRID_COLUMN_COUNT] ?: 0,
        gridColumnCountCompact = preferences[KEY_GRID_COLUMN_COUNT_COMPACT] ?: 0,
        pinchToChangeGridColumnsEnabled =
            preferences[KEY_PINCH_TO_CHANGE_GRID_COLUMNS_ENABLED] ?: true,
        homeFeedCardWidthPreset = HomeFeedCardWidthPreset.fromValue(
            preferences[KEY_HOME_FEED_CARD_WIDTH_PRESET] ?: HomeFeedCardWidthPreset.AUTO.value
        ),
        homeFeedCardStyle = HomeFeedCardStyle.fromValue(
            preferences[KEY_HOME_FEED_CARD_STYLE] ?: HomeFeedCardStyle.BILIPAI.value
        ),
        homeHeroCarouselEnabled = preferences[KEY_HOME_HERO_CAROUSEL_ENABLED] ?: false,
        homeHeroCarouselAutoplayEnabled =
            preferences[KEY_HOME_HERO_CAROUSEL_AUTOPLAY_ENABLED] ?: false,
        homeRefreshTipVisible = preferences[KEY_HOME_REFRESH_TIP_VISIBLE] ?: true,
        cardAnimationEnabled = preferences[KEY_CARD_ANIMATION_ENABLED] ?: false,
        cardTransitionEnabled = preferences[KEY_CARD_TRANSITION_ENABLED] ?: true,
        videoSharedTransitionSpeed = VideoSharedTransitionSpeed.fromValue(
            preferences[KEY_VIDEO_SHARED_TRANSITION_SPEED]
                ?: VideoSharedTransitionSpeed.STANDARD.value
        ),
        videoSharedTransitionCustomDurationMillis =
            normalizeVideoSharedTransitionCustomDurationMillis(
                preferences[KEY_VIDEO_SHARED_TRANSITION_CUSTOM_DURATION_MILLIS]
                    ?: VIDEO_SHARED_TRANSITION_CUSTOM_DEFAULT_MILLIS
            ),
        smartVisualGuardEnabled = false,
        runtimeVisualGuardEnabled =
            preferences[KEY_RUNTIME_VISUAL_GUARD_ENABLED] ?: true,
        compactVideoStatsOnCover = preferences[KEY_COMPACT_VIDEO_STATS_ON_COVER] ?: false,
        lowQualityHomeCoverInDataSaver =
            preferences[KEY_LOW_QUALITY_HOME_COVER_IN_DATA_SAVER] ?: false,
        // 已下线：忽略旧数据，确保历史上开启过实时模糊/液态玻璃的用户不会继续走该路径。
        showHomeCoverGlassBadges = false,
        showHomeInfoGlassBadges = false,
        homeCardBadgeEffectMode = HomeCardBadgeEffectMode.OFF,
        homeCardInfoGlassMode = HomeCardInfoGlassMode.OFF,
        homeWallpaperEffectMode = HomeWallpaperEffectMode.fromValue(
            preferences[KEY_HOME_WALLPAPER_EFFECT_MODE] ?: HomeWallpaperEffectMode.SOFT_BLUR.value
        ),
        homeWallpaperEffectScope = HomeWallpaperEffectScope.fromValue(
            preferences[KEY_HOME_WALLPAPER_EFFECT_SCOPE] ?: HomeWallpaperEffectScope.HOME_ONLY.value
        ),
        showHomeUpBadges = preferences[KEY_HOME_UP_BADGES_VISIBLE] ?: false,
        showHomeUpAvatars = preferences[KEY_HOME_UP_AVATARS_VISIBLE] ?: false,
        showHomePublishTime = preferences[KEY_HOME_PUBLISH_TIME_VISIBLE] ?: true,
        showFullVideoCardContent = preferences[KEY_FULL_VIDEO_CARD_CONTENT_VISIBLE] ?: false,
        videoCardLongPressActionEnabled = preferences[KEY_VIDEO_CARD_LONG_PRESS_ACTION_ENABLED] ?: false,
        homeCardDynamicTintEnabled = preferences[KEY_HOME_CARD_DYNAMIC_TINT_ENABLED] ?: false,
        homeCardFrostedGlassEnabled = resolveHomeCardFrostedGlassEnabled(
            storedValue = preferences[KEY_HOME_CARD_FROSTED_GLASS_ENABLED],
            legacyCombinedValue = preferences[KEY_HOME_CARD_DYNAMIC_TINT_ENABLED],
        ),
        homeDurationStyle = preferences[KEY_HOME_DURATION_STYLE]
            ?.let(HomeDurationStyle::fromValue)
            ?: if (preferences[KEY_HOME_VIDEO_DURATION_BADGES_VISIBLE] ?: true) {
                HomeDurationStyle.OUTSIDE_COVER
            } else {
                HomeDurationStyle.HIDDEN
            },
        easterEggEnabled = preferences[KEY_EASTER_EGG_ENABLED] ?: false,
        // 保持现有运行时行为：首次未配置时按 false 返回
        crashTrackingConsentShown = preferences[KEY_CRASH_TRACKING_CONSENT_SHOWN] ?: false
    )
}

internal fun decodeDesktopOriginalHomeTopTabs(preferences: DesktopPreferenceSnapshot): HomeTopTabSettings {
    val orderIds = (preferences[KEY_TOP_TAB_ORDER] ?: DesktopOriginalHomeSettingConstants.DEFAULT_TOP_TAB_ORDER)
        .split(",")
        .filter { it.isNotBlank() }
    val visibleIds = (preferences[KEY_TOP_TAB_VISIBLE_TABS] ?: DesktopOriginalHomeSettingConstants.DEFAULT_TOP_TAB_VISIBLE)
        .split(",")
        .filter { it.isNotBlank() }
        .toSet()
    // 旧版本可能保存超过上限的可见标签：按用户顺序裁剪到 DesktopOriginalHomeSettingConstants.MAX_TOP_TABS，
    // 保证运行时展示与设置界面上限一致。
    val cappedVisibleIds = if (visibleIds.size <= DesktopOriginalHomeSettingConstants.MAX_TOP_TABS) {
        visibleIds
    } else {
        orderIds.filter { it in visibleIds }.take(DesktopOriginalHomeSettingConstants.MAX_TOP_TABS).toSet()
    }
    val hideTopTabs = preferences[KEY_HIDE_TOP_TABS] ?: false
    return HomeTopTabSettings(
        orderIds = orderIds,
        visibleIds = cappedVisibleIds,
        hideTopTabs = hideTopTabs
    )
}

internal fun decodeDesktopOriginalHomeNavigation(
    preferences: DesktopPreferenceSnapshot,
    defaultTabletUseSidebar: Boolean = false
): AppNavigationSettings {
    val orderString = preferences[KEY_BOTTOM_BAR_ORDER] ?: DesktopOriginalHomeSettingConstants.DEFAULT_BOTTOM_BAR_ORDER
    val tabsString = preferences[KEY_BOTTOM_BAR_VISIBLE_TABS] ?: DesktopOriginalHomeSettingConstants.DEFAULT_BOTTOM_BAR_VISIBLE_TABS
    val order = orderString.split(",").filter { it.isNotBlank() }
    val visible = tabsString.split(",").filter { it.isNotBlank() }
    return AppNavigationSettings(
        bottomBarVisibilityMode = DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.fromValue(
            preferences[KEY_BOTTOM_BAR_VISIBILITY_MODE] ?: DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_VISIBLE.value
        ),
        orderedVisibleTabIds = resolveOrderedVisibleBottomTabs(order, visible),
        bottomBarItemColors = parseBottomBarItemColors(preferences[KEY_BOTTOM_BAR_ITEM_COLORS] ?: ""),
        bottomBarItemLabels = parseBottomBarItemLabels(
            preferences[bottomBarItemLabelsPreferencesKey].orEmpty()
        ),
        tabletUseSidebar = preferences[KEY_TABLET_NAVIGATION_MODE] ?: defaultTabletUseSidebar,
        sidebarExpanded = preferences[KEY_SIDEBAR_EXPANDED] ?: true,
        sidebarAccountSwitcherEnabled =
            preferences[KEY_SIDEBAR_ACCOUNT_SWITCHER_ENABLED] ?: true,
        predictiveBackEnabled = preferences[KEY_PREDICTIVE_BACK_ENABLED] ?: true,
        predictiveBackAnimationStyle = preferences[KEY_PREDICTIVE_BACK_ANIMATION_STYLE] ?: "miuix",
        predictiveBackExitDirection =
            preferences[KEY_PREDICTIVE_BACK_EXIT_DIRECTION] ?: "always_right",
        miuixTransitionBlurEnabled = preferences[KEY_MIUIX_TRANSITION_BLUR_ENABLED] ?: true,
        miuixPredictiveBackMaxProgressPercent =
            (preferences[miuixPredictiveBackMaxProgressPercentPreferencesKey] ?: 100)
                .coerceIn(0, 100),
        videoSharedReturnGestureFollowEnabled =
            preferences[KEY_VIDEO_SHARED_RETURN_GESTURE_FOLLOW_ENABLED] ?: true,
    )
}

private fun normalizeBottomBarColorItemId(rawId: String): String {
    val id = rawId.trim()
    if (id.isBlank()) return ""
    return when (id.lowercase()) {
        "home" -> "HOME"
        "dynamic" -> "DYNAMIC"
        "story", "shortvideo", "short_video" -> "STORY"
        "history" -> "HISTORY"
        "profile", "mine", "my" -> "PROFILE"
        "favorite", "favourite" -> "FAVORITE"
        "live" -> "LIVE"
        "watchlater", "watch_later" -> "WATCHLATER"
        "settings" -> "SETTINGS"
        "plugins", "plugin", "plugin_center" -> "PLUGINS"
        else -> id.uppercase()
    }
}

private fun parseBottomBarItemColors(colorString: String): Map<String, Int> {
    if (colorString.isBlank()) return emptyMap()
    return colorString.split(",").mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size != 2) return@mapNotNull null
        val itemId = normalizeBottomBarColorItemId(parts[0])
        if (itemId.isBlank()) return@mapNotNull null
        itemId to (parts[1].trim().toIntOrNull() ?: 0)
    }.toMap()
}

private fun resolveOrderedVisibleBottomTabs(
    order: List<String>,
    visible: List<String>
): List<String> {
    val visibleSet = visible.toSet()
    val orderedVisible = order.filter { it in visibleSet }
    val missingVisible = visible.filterNot { it in orderedVisible }
    return orderedVisible + missingVisible
}
