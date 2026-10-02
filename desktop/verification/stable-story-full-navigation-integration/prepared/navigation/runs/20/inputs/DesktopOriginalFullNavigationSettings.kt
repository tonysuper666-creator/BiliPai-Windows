package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.settings.fullNavigationBooleanKey as booleanPreferencesKey
import com.bilipai.desktop.settings.fullNavigationIntKey as intPreferencesKey
import com.bilipai.desktop.settings.fullNavigationStringKey as stringPreferencesKey
import com.bilipai.desktop.settings.fullNavigationDataStore as settingsDataStore
import com.android.purebilibili.core.store.navigation.parseBottomBarItemLabels
import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode
import com.bilipai.desktop.ui.DesktopOriginalHomeSettingConstants.TopTabLabelMode
import com.android.purebilibili.core.ui.blur.BlurIntensity
import com.android.purebilibili.core.ui.transition.*
import kotlinx.coroutines.flow.*
internal object DesktopOriginalFullNavigationSettings {
    const val MAX_TOP_TABS=5
    private val KEY_BLUR_INTENSITY = stringPreferencesKey("blur_intensity")
    private val KEY_BOTTOM_BAR_BLUR_ENABLED = booleanPreferencesKey("bottom_bar_blur_enabled")
    private val KEY_BOTTOM_BAR_FLOATING = booleanPreferencesKey("bottom_bar_floating")
    private val KEY_BOTTOM_BAR_ITEM_COLORS = stringPreferencesKey("bottom_bar_item_colors")
    private val KEY_BOTTOM_BAR_LABEL_MODE = intPreferencesKey("bottom_bar_label_mode")
    private val KEY_BOTTOM_BAR_ORDER = stringPreferencesKey("bottom_bar_order")
    private val KEY_BOTTOM_BAR_SEARCH_ENABLED = booleanPreferencesKey("bottom_bar_search_enabled")
    private val KEY_BOTTOM_BAR_VISIBILITY_MODE = intPreferencesKey("bottom_bar_visibility_mode")
    private val KEY_BOTTOM_BAR_VISIBLE_TABS = stringPreferencesKey("bottom_bar_visible_tabs")
    private val KEY_CARD_ANIMATION_ENABLED = booleanPreferencesKey("card_animation_enabled")
    private val KEY_CARD_TRANSITION_ENABLED = booleanPreferencesKey("card_transition_enabled")
    private val KEY_GLOBAL_TEXT_TAP_COPY_ENABLED =
            booleanPreferencesKey("global_text_tap_copy_enabled")
    private val KEY_HEADER_BLUR_ENABLED = booleanPreferencesKey("header_blur_enabled")
    private val KEY_HEADER_COLLAPSE_ENABLED = booleanPreferencesKey("header_collapse_enabled")
    private val KEY_HIDE_TOP_TABS = booleanPreferencesKey("hide_top_tabs")
    private val KEY_HOME_HEADER_BLUR_MODE = intPreferencesKey("home_header_blur_mode")
    private val KEY_HOME_HEADER_COLLAPSE_MODE = intPreferencesKey("home_header_collapse_mode")
    private val KEY_HOME_TOP_LAYOUT_ORDER = intPreferencesKey("home_top_layout_order")
    private val KEY_HOME_TOP_RIGHT_ACTION = intPreferencesKey("home_top_right_action")
    private val KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED =
            booleanPreferencesKey("linked_dock_merge_on_scroll_enabled")
    private val KEY_LIST_SCOPED_SEARCH_ENABLED = booleanPreferencesKey("list_scoped_search_enabled")
    private val KEY_LIVE_SURFACE_CARD_TRANSITION_ENABLED =
            booleanPreferencesKey("live_surface_card_transition_enabled")
    private val KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED =
            booleanPreferencesKey("navigation_icon_cross_scale_enabled")
    private val KEY_PROGRESSIVE_TOP_BLUR_ENABLED =
            booleanPreferencesKey("progressive_top_blur_enabled")
    private val KEY_PROGRESSIVE_TOP_FADE_ENABLED =
            booleanPreferencesKey("progressive_top_fade_enabled")
    private val KEY_RELATED_VIDEO_TRANSITION_ENABLED =
            booleanPreferencesKey("related_video_transition_enabled")
    private val KEY_SEARCH_FILTER_TAB_ORDER = stringPreferencesKey("search_filter_tab_order")
    private val KEY_SIDEBAR_ACCOUNT_SWITCHER_ENABLED =
            booleanPreferencesKey("sidebar_account_switcher_enabled")
    private val KEY_TABLET_NAVIGATION_MODE = booleanPreferencesKey("tablet_use_sidebar")
    private val KEY_TOP_TAB_LABEL_MODE = intPreferencesKey("top_tab_label_mode")
    private val KEY_TOP_TAB_ORDER = stringPreferencesKey("top_tab_order")
    private val KEY_TOP_TAB_VISIBLE_TABS = stringPreferencesKey("top_tab_visible_tabs")
    private val KEY_UI_ENTRANCE_ANIMATION_ENABLED =
            booleanPreferencesKey("ui_entrance_animation_enabled")
    private val KEY_VIDEO_SHARED_TRANSITION_CUSTOM_DURATION_MILLIS =
            intPreferencesKey("video_shared_transition_custom_duration_millis")
    private val KEY_VIDEO_SHARED_TRANSITION_SPEED =
            intPreferencesKey("video_shared_transition_speed")
    private val KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED =
            booleanPreferencesKey("video_transition_realtime_blur_enabled")
    private const val DEFAULT_BOTTOM_BAR_ORDER = "HOME,DYNAMIC,HISTORY,LISTEN_VIDEO,PROFILE"
    private const val DEFAULT_BOTTOM_BAR_VISIBLE_TABS = "HOME,DYNAMIC,HISTORY,LISTEN_VIDEO,PROFILE"
    private const val DEFAULT_SEARCH_FILTER_TAB_ORDER =
            "video,media_bangumi,media_ft,live_room,live_user,bili_user,article,topic,photo"
    private const val DEFAULT_TOP_TAB_ORDER = "RECOMMEND,FOLLOW,POPULAR,LIVE,GAME"
    private const val DEFAULT_TOP_TAB_VISIBLE = "RECOMMEND,FOLLOW,POPULAR,LIVE,GAME"
    suspend fun clearBottomBarItemLabels(context: Context) =
        DesktopOriginalFullNavigationStore.clearBottomBarItemLabels(context)

    fun getBlurIntensity(context: Context): Flow<BlurIntensity> = context.settingsDataStore.data
        .map { preferences ->
            when (preferences[KEY_BLUR_INTENSITY]) {
                "THICK" -> BlurIntensity.THICK
                "APPLE_DOCK" -> BlurIntensity.APPLE_DOCK  //  修复：添加 APPLE_DOCK 支持
                else -> BlurIntensity.THIN  // 默认标准
            }
        }

    fun getBottomBarBlurEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_BLUR_ENABLED] ?: false }

    fun getBottomBarFloating(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_FLOATING] ?: true }

    fun getBottomBarItemColors(context: Context): Flow<Map<String, Int>> = context.settingsDataStore.data
        .map { preferences -> parseBottomBarItemColors(preferences[KEY_BOTTOM_BAR_ITEM_COLORS] ?: "") }

    fun getBottomBarItemLabels(context: Context): Flow<Map<String, String>> =
        DesktopOriginalFullNavigationStore.observeBottomBarItemLabels(context)

    fun getBottomBarLabelMode(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_LABEL_MODE] ?: 0 }

    fun getBottomBarOrder(context: Context): Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        val orderString = prefs[KEY_BOTTOM_BAR_ORDER] ?: DEFAULT_BOTTOM_BAR_ORDER
        orderString.split(",").filter { it.isNotBlank() }
    }

    fun getBottomBarSearchEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] ?: false }

    fun getBottomBarVisibilityMode(context: Context): Flow<BottomBarVisibilityMode> = context.settingsDataStore.data
        .map { preferences -> 
            BottomBarVisibilityMode.fromValue(preferences[KEY_BOTTOM_BAR_VISIBILITY_MODE] ?: BottomBarVisibilityMode.ALWAYS_VISIBLE.value)
        }

    fun getBottomBarVisibleTabs(context: Context): Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        val tabsString = prefs[KEY_BOTTOM_BAR_VISIBLE_TABS] ?: DEFAULT_BOTTOM_BAR_VISIBLE_TABS
        tabsString.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun getCardAnimationEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_CARD_ANIMATION_ENABLED] ?: false }

    fun getCardTransitionEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_CARD_TRANSITION_ENABLED] ?: true }

    fun getFullScreenSwipeBackEnabled(context: Context): Flow<Boolean> =
        DesktopOriginalFullNavigationStore.getFullScreenSwipeBackEnabled(context)

    fun getGlobalTextTapCopyEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_GLOBAL_TEXT_TAP_COPY_ENABLED] ?: false }

    fun getHeaderBlurEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            val mode = resolveHomeHeaderBlurModePreference(
                rawMode = preferences[KEY_HOME_HEADER_BLUR_MODE],
                legacyEnabled = preferences[KEY_HEADER_BLUR_ENABLED]
            )
            mode != HomeHeaderBlurMode.ALWAYS_OFF
        }

    fun getHideTopTabs(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_HIDE_TOP_TABS] ?: false }

    fun getHomeHeaderCollapseMode(context: Context): Flow<HomeHeaderCollapseMode> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_HOME_HEADER_COLLAPSE_MODE]
                ?.let(HomeHeaderCollapseMode::fromValue)
                ?: HomeHeaderCollapseMode.fromLegacyBoolean(
                    preferences[KEY_HEADER_COLLAPSE_ENABLED] ?: true
                )
        }

    fun getHomeTopLayoutOrder(context: Context): Flow<HomeTopLayoutOrder> = context.settingsDataStore.data
        .map { preferences ->
            HomeTopLayoutOrder.fromValue(
                preferences[KEY_HOME_TOP_LAYOUT_ORDER] ?: HomeTopLayoutOrder.SEARCH_THEN_TABS.value
            )
        }

    fun getHomeTopRightAction(context: Context): Flow<HomeTopRightAction> = context.settingsDataStore.data
        .map { preferences ->
            HomeTopRightAction.fromValue(
                preferences[KEY_HOME_TOP_RIGHT_ACTION] ?: HomeTopRightAction.SETTINGS.value
            )
        }

    fun getLinkedDockMergeOnScrollEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences ->
                preferences[KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED] ?: true
            }

    fun getListScopedSearchEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_LIST_SCOPED_SEARCH_ENABLED] ?: false }

    fun getLiveSurfaceCardTransitionEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_LIVE_SURFACE_CARD_TRANSITION_ENABLED] ?: false }

    fun getNavigationIconCrossScaleEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED] ?: false
        }

    fun getProgressiveTopBlurEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[KEY_PROGRESSIVE_TOP_BLUR_ENABLED] ?: false
        }

    fun getProgressiveTopFadeEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[KEY_PROGRESSIVE_TOP_FADE_ENABLED] ?: true
        }

    fun getRelatedVideoTransitionEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_RELATED_VIDEO_TRANSITION_ENABLED] ?: true
        }

    fun getSearchFilterTabOrder(context: Context): Flow<List<String>> =
        context.settingsDataStore.data.map { prefs ->
            val orderString = prefs[KEY_SEARCH_FILTER_TAB_ORDER] ?: DEFAULT_SEARCH_FILTER_TAB_ORDER
            orderString.split(",").filter { it.isNotBlank() }
        }

    fun getSidebarAccountSwitcherEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SIDEBAR_ACCOUNT_SWITCHER_ENABLED] ?: true }

    fun getTabletUseSidebar(context: Context, isLargeScreenCapable: Boolean): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[KEY_TABLET_NAVIGATION_MODE]
                ?: defaultTabletUseSidebar(isLargeScreenCapable)
        }

    fun getTopTabLabelMode(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_TOP_TAB_LABEL_MODE] ?: TopTabLabelMode.TEXT_ONLY }

    fun getTopTabOrder(context: Context): Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        val orderString = prefs[KEY_TOP_TAB_ORDER] ?: DEFAULT_TOP_TAB_ORDER
        orderString.split(",").filter { it.isNotBlank() }
    }

    fun getTopTabVisibleTabs(context: Context): Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        val tabsString = prefs[KEY_TOP_TAB_VISIBLE_TABS] ?: DEFAULT_TOP_TAB_VISIBLE
        tabsString.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun getUiEntranceAnimationEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_UI_ENTRANCE_ANIMATION_ENABLED] ?: true }

    fun getVideoTransitionRealtimeBlurEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED] ?: false }

    suspend fun setBlurIntensity(context: Context, intensity: BlurIntensity) {
        context.settingsDataStore.edit { preferences -> 
            preferences[KEY_BLUR_INTENSITY] = intensity.name
        }
    }

    suspend fun setBottomBarBlurEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_BOTTOM_BAR_BLUR_ENABLED] = value }
    }

    suspend fun setBottomBarFloating(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_BOTTOM_BAR_FLOATING] = value }
    }

    suspend fun setBottomBarItemColor(context: Context, itemId: String, colorIndex: Int) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_BOTTOM_BAR_ITEM_COLORS] ?: ""
            val colorMap = if (current.isBlank()) {
                mutableMapOf()
            } else {
                current.split(",")
                    .filter { it.contains(":") }
                    .associate { pair ->
                        val (id, index) = pair.split(":")
                        id to (index.toIntOrNull() ?: 0)
                    }.toMutableMap()
            }
            val normalizedItemId = normalizeBottomBarColorItemId(itemId)
            if (normalizedItemId.isBlank()) return@edit
            colorMap[normalizedItemId] = colorIndex
            prefs[KEY_BOTTOM_BAR_ITEM_COLORS] = colorMap.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
    }

    suspend fun setBottomBarItemLabel(context: Context, itemId: String, label: String) =
        DesktopOriginalFullNavigationStore.setBottomBarItemLabel(context, itemId, label)

    suspend fun setBottomBarLabelMode(context: Context, value: Int) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_BOTTOM_BAR_LABEL_MODE] = value }
    }

    suspend fun setBottomBarOrder(context: Context, order: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_BOTTOM_BAR_ORDER] = order.joinToString(",")
        }
    }

    suspend fun setBottomBarSearchEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] = value
        }
    }

    suspend fun setBottomBarVisibilityMode(context: Context, mode: BottomBarVisibilityMode) {
        context.settingsDataStore.edit { preferences -> 
            preferences[KEY_BOTTOM_BAR_VISIBILITY_MODE] = mode.value 
        }
    }

    suspend fun setBottomBarVisibleTabs(context: Context, tabs: Set<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_BOTTOM_BAR_VISIBLE_TABS] = tabs.joinToString(",")
        }
    }

    suspend fun setCardAnimationEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_CARD_ANIMATION_ENABLED] = value }
    }

    suspend fun setCardTransitionEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_CARD_TRANSITION_ENABLED] = value }
    }

    suspend fun setFullScreenSwipeBackEnabled(context: Context, enabled: Boolean) {
        DesktopOriginalFullNavigationStore.setFullScreenSwipeBackEnabled(context, enabled)
    }

    suspend fun setGlobalTextTapCopyEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_GLOBAL_TEXT_TAP_COPY_ENABLED] = value
        }
    }

    suspend fun setHeaderBlurEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_HEADER_BLUR_ENABLED] = value
            preferences[KEY_HOME_HEADER_BLUR_MODE] = if (value) {
                HomeHeaderBlurMode.FOLLOW_PRESET.value
            } else {
                HomeHeaderBlurMode.ALWAYS_OFF.value
            }
            if (value) {
                preferences[KEY_PROGRESSIVE_TOP_BLUR_ENABLED] = false
            }
        }
    }

    suspend fun setHideTopTabs(context: Context, hide: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_HIDE_TOP_TABS] = hide }
    }

    suspend fun setHomeHeaderCollapseMode(context: Context, mode: HomeHeaderCollapseMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_HOME_HEADER_COLLAPSE_MODE] = mode.value
            preferences[KEY_HEADER_COLLAPSE_ENABLED] = mode.hasAnyCollapse
        }
    }

    suspend fun setHomeTopLayoutOrder(context: Context, order: HomeTopLayoutOrder) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_HOME_TOP_LAYOUT_ORDER] = order.value
        }
    }

    suspend fun setHomeTopRightAction(context: Context, action: HomeTopRightAction) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_HOME_TOP_RIGHT_ACTION] = action.value
        }
    }

    suspend fun setLinkedDockMergeOnScrollEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED] = value
        }
    }

    suspend fun setListScopedSearchEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LIST_SCOPED_SEARCH_ENABLED] = value
        }
    }

    suspend fun setLiveSurfaceCardTransitionEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LIVE_SURFACE_CARD_TRANSITION_ENABLED] = value
        }
    }

    suspend fun setMiuixPredictiveBackMaxProgressPercent(context: Context, percent: Int) {
        DesktopOriginalFullNavigationStore.setMiuixPredictiveBackMaxProgressPercent(context, percent)
    }

    suspend fun setMiuixTransitionBlurEnabled(context: Context, enabled: Boolean) {
        DesktopOriginalFullNavigationStore.setMiuixTransitionBlurEnabled(context, enabled)
    }

    suspend fun setNavigationIconCrossScaleEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED] = value
        }
    }

    suspend fun setPredictiveBackAnimationStyle(context: Context, style: String) {
        DesktopOriginalFullNavigationStore.setPredictiveBackAnimationStyle(context, style)
    }

    suspend fun setPredictiveBackEnabled(context: Context, enabled: Boolean) {
        DesktopOriginalFullNavigationStore.setPredictiveBackEnabled(context, enabled)
    }

    suspend fun setPredictiveBackExitDirection(context: Context, direction: String) {
        DesktopOriginalFullNavigationStore.setPredictiveBackExitDirection(context, direction)
    }

    suspend fun setProgressiveTopBlurEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_PROGRESSIVE_TOP_BLUR_ENABLED] = value
            if (value) {
                preferences[KEY_HEADER_BLUR_ENABLED] = false
                preferences[KEY_HOME_HEADER_BLUR_MODE] = HomeHeaderBlurMode.ALWAYS_OFF.value
            }
        }
    }

    suspend fun setProgressiveTopFadeEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_PROGRESSIVE_TOP_FADE_ENABLED] = value
        }
    }

    suspend fun setRelatedVideoTransitionEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_RELATED_VIDEO_TRANSITION_ENABLED] = value
        }
    }

    suspend fun setSearchFilterTabOrder(context: Context, order: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SEARCH_FILTER_TAB_ORDER] = order.joinToString(",")
        }
    }

    suspend fun setSidebarAccountSwitcherEnabled(context: Context, enabled: Boolean) {
        DesktopOriginalFullNavigationStore.setSidebarAccountSwitcherEnabled(context, enabled)
    }

    suspend fun setTabletUseSidebar(context: Context, useSidebar: Boolean) {
        DesktopOriginalFullNavigationStore.setTabletUseSidebar(context, useSidebar)
    }

    suspend fun setTopTabLabelMode(context: Context, value: Int) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_TOP_TAB_LABEL_MODE] = value }
    }

    suspend fun setTopTabOrder(context: Context, order: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_TOP_TAB_ORDER] = order.joinToString(",")
        }
    }

    suspend fun setTopTabVisibleTabs(context: Context, tabs: Set<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_TOP_TAB_VISIBLE_TABS] = tabs.joinToString(",")
        }
    }

    suspend fun setUiEntranceAnimationEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_UI_ENTRANCE_ANIMATION_ENABLED] = value }
    }

    suspend fun setVideoSharedReturnGestureFollowEnabled(context: Context, enabled: Boolean) {
        DesktopOriginalFullNavigationStore.setVideoSharedReturnGestureFollowEnabled(context, enabled)
    }

    suspend fun setVideoSharedTransitionCustomDurationMillis(
        context: Context,
        durationMillis: Int
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_VIDEO_SHARED_TRANSITION_CUSTOM_DURATION_MILLIS] =
                normalizeVideoSharedTransitionCustomDurationMillis(durationMillis)
        }
    }

    suspend fun setVideoSharedTransitionSpeed(
        context: Context,
        speed: VideoSharedTransitionSpeed
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_VIDEO_SHARED_TRANSITION_SPEED] = speed.value
        }
    }

    suspend fun setVideoTransitionRealtimeBlurEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED] = value
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

    internal fun defaultTabletUseSidebar(isTabletDevice: Boolean): Boolean = isTabletDevice

    fun getAppNavigationSettings(context:Context):Flow<AppNavigationSettings> = context.settingsDataStore.data.map { com.bilipai.desktop.ui.decodeDesktopOriginalHomeNavigation(it,false) }

}

private object DesktopOriginalFullNavigationStore {
    private val bottomBarItemLabelsPreferencesKey = stringPreferencesKey("bottom_bar_item_labels")
    private val keyFullScreenSwipeBackEnabled =
            booleanPreferencesKey("full_screen_swipe_back_enabled")
    private val keyMiuixTransitionBlurEnabled =
            booleanPreferencesKey("miuix_transition_blur_enabled")
    private val keyPredictiveBackAnimationStyle = stringPreferencesKey("predictive_back_animation_style")
    private val keyPredictiveBackEnabled = booleanPreferencesKey("predictive_back_enabled")
    private val keyPredictiveBackExitDirection = stringPreferencesKey("predictive_back_exit_direction")
    private val keySidebarAccountSwitcherEnabled =
            booleanPreferencesKey("sidebar_account_switcher_enabled")
    private val keyTabletUseSidebar = booleanPreferencesKey("tablet_use_sidebar")
    private val keyVideoSharedReturnGestureFollowEnabled =
            booleanPreferencesKey("video_shared_return_gesture_follow_enabled")
    private val miuixPredictiveBackMaxProgressPercentPreferencesKey =
        intPreferencesKey("miuix_predictive_back_max_progress_percent")
    suspend fun clearBottomBarItemLabels(context: Context) {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(bottomBarItemLabelsPreferencesKey)
        }
    }

    fun getFullScreenSwipeBackEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[keyFullScreenSwipeBackEnabled] ?: false }

    fun observeBottomBarItemLabels(context: Context): Flow<Map<String, String>> =
        context.settingsDataStore.data
            .map { preferences ->
                parseBottomBarItemLabels(preferences[bottomBarItemLabelsPreferencesKey].orEmpty())
            }

    suspend fun setBottomBarItemLabel(context: Context, itemId: String, label: String) {
        context.settingsDataStore.edit { preferences ->
            val labels = parseBottomBarItemLabels(
                preferences[bottomBarItemLabelsPreferencesKey].orEmpty()
            ).toMutableMap()
            val normalizedItemId = normalizeBottomBarLabelItemId(itemId)
            if (normalizedItemId.isBlank()) return@edit
            val normalizedLabel = normalizeBottomBarCustomLabel(label)
            if (normalizedLabel.isBlank()) {
                labels.remove(normalizedItemId)
            } else {
                labels[normalizedItemId] = normalizedLabel
            }
            preferences[bottomBarItemLabelsPreferencesKey] = labels.entries
                .joinToString(",") { (id, value) ->
                    val encoded = java.net.URLEncoder.encode(
                        value,
                        java.nio.charset.StandardCharsets.UTF_8.name()
                    )
                    "$id=$encoded"
                }
        }
    }

    suspend fun setFullScreenSwipeBackEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyFullScreenSwipeBackEnabled] = enabled
        }
    }

    suspend fun setMiuixPredictiveBackMaxProgressPercent(context: Context, percent: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[miuixPredictiveBackMaxProgressPercentPreferencesKey] =
                percent.coerceIn(0, 100)
        }
    }

    suspend fun setMiuixTransitionBlurEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyMiuixTransitionBlurEnabled] = enabled
        }
    }

    suspend fun setPredictiveBackAnimationStyle(context: Context, style: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyPredictiveBackAnimationStyle] = style
        }
    }

    suspend fun setPredictiveBackEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyPredictiveBackEnabled] = enabled
        }
    }

    suspend fun setPredictiveBackExitDirection(context: Context, direction: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyPredictiveBackExitDirection] = direction
        }
    }

    suspend fun setSidebarAccountSwitcherEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[keySidebarAccountSwitcherEnabled] = enabled
        }
    }

    suspend fun setTabletUseSidebar(context: Context, useSidebar: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyTabletUseSidebar] = useSidebar
        }
    }

    suspend fun setVideoSharedReturnGestureFollowEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyVideoSharedReturnGestureFollowEnabled] = enabled
        }
    }

    private fun normalizeBottomBarLabelItemId(rawId: String): String {
        val id = rawId.trim()
        if (id.isBlank()) return ""
        return when (id.lowercase()) {
            "home" -> "HOME"
            "dynamic" -> "DYNAMIC"
            "story", "shortvideo", "short_video" -> "STORY"
            "history" -> "HISTORY"
            "listen_video" -> "LISTEN_VIDEO"
            "profile", "mine", "my" -> "PROFILE"
            "favorite", "favourite" -> "FAVORITE"
            "live" -> "LIVE"
            "watchlater", "watch_later" -> "WATCHLATER"
            "settings" -> "SETTINGS"
            "plugins", "plugin", "plugin_center" -> "PLUGINS"
            else -> id.uppercase()
        }
    }

    internal fun normalizeBottomBarCustomLabel(rawLabel: String): String = rawLabel
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(12)
}
