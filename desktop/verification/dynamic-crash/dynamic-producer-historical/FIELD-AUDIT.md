# Original Home / Dynamic settings and actual consumers

This is a two-field implementation slice. There is no original function named `SettingsHomeSection` or `SettingsDynamicSection` in the pinned source. The original implementation is `SettingsSections.FeedApiSection`, with additional card and navigation fields in `AppearanceSettingsScreen` and `HomeSettings`. This inventory does not equate two controls with full feature completion.

## Dynamic and recommendation fields

| Original field / key | Persisted default | Actual Windows status and source semantics |
|---|---|---|
| incrementalTimelineRefreshEnabled / `incremental_timeline_refresh` | `false` | prepared actual consumer; original multi-page baseline fetch + overlap/de-duplicate/sort/divider/tail pagination |
| dynamicFeedLayoutMode / `dynamic_feed_layout_mode` | `0` | prepared actual consumer; 0 WATERFALL / 1 LIST; unknown value WATERFALL; original masonry layout and list prepend anchor |
| dynamicImagePreviewTextVisible / `dynamic_image_preview_text_visible` | `true` | missing; image viewer text overlay + temporary eye toggle; current CommunityImageViewer has no matching text consumer |
| dynamicDetailImageLayout / `dynamic_detail_image_layout` | `0` | missing; EXPANDED 0 / THUMBNAIL 1; unknown EXPANDED; Android first-frame cache dynamic_detail_image_layout_cache/layout + memory, set cache before settings write |
| dynamicAllTabHorizontalUserListVisible / `dynamic_all_tab_horizontal_user_list_visible` | `false` | missing; all-tab horizontal following-user rail; UP tab still supports user selection |
| dynamicTopBarCollapseOnScroll / `dynamic_top_bar_collapse_on_scroll` | `false` | missing; tab bar scroll-collapse independently of the horizontal following rail |
| dynamicVisibleTabIds / `dynamic_tab_visible_tabs` | `all,video,pgc,article,up` | missing; CSV set; original UI preserves at least one valid tab; current Windows has fixed all/video/pgc |
| dynamicTabOrder / `dynamic_tab_order` | `all,video,pgc,article,up` | missing; CSV list; source UI orders by provided index then logical index for absent IDs |
| dynamicTopActionsCollapsed / `dynamic_top_actions_collapsed` | `false` | missing; original publish/layout/fold dock persistent collapsed state; not part of FeedApiSection |
| dynamicLayoutDirection / `dynamic_page_layout_direction` | `0` | missing; LEFT 0 / RIGHT 1, unknown LEFT; original side-user/operation docking, not part of FeedApiSection |
| feedApiType / `feed_api_type` | `0` | existing effective Windows consumer; WEB 0 / MOBILE 1 / MERGED 2, unknown WEB; Android settings DataStore and sync feed_api/type mirror. Existing Windows consumer authority remains discovery/plugin-settings.json feed_api/type, not rewritten here. |
| homeRefreshCount / `home_refresh_count` | `20` | existing effective Windows consumer; normalize 10..30, original slider 19 intermediate steps; Android settings DataStore and feed_api/home_refresh_count mirror. Existing Windows authority remains discovery/feed_api namespace, not rewritten here. |

## Home display / layout gaps

Current `DiscoveryVideoGrid` uses fixed `Adaptive(260.dp)`, 18dp outer padding and 16dp gaps. `DiscoveryVideoTile` uses a fixed 16:9 cover, two title lines, author name, play count and outside duration, and publish time whenever the payload supplies it. Those similarities to original defaults are not configurable consumer bindings. Its visible preview/feedback buttons do not implement the original disabled-by-default long-press setting.

Original grid policy caps content at 1280dp, uses AUTO 180dp minimum or presets COMPACT160/BALANCED200/WIDE260/ULTRA_WIDE320, separate compact/wide remembered fixed-column keys, and display-mode-dependent column bounds. Original card layout uses 6dp gaps and width-class/density policies: CURRENT16:9, OFFICIAL4:3, BILIPAI16:10 (default), with original single-column / Expanded+ exceptions. None of those Home layout consumers is installed by this Dynamic slice.

The JSON inventory records **every named field passed by the actual persisted `mapHomeSettingsFromPreferences`** below, including exact source read expressions and legacy migration fallback. Constructor defaults alone are not used as the effective default. In particular, persisted `homeHeroCarouselEnabled` defaults false although the data-class constructor says true; missing `home_duration_style` falls back to OUTSIDE_COVER when the legacy duration-badge key is absent/true and HIDDEN when false; frosted-glass falls back to the old combined tint key only if its separate key is missing. Old card-badge/info glass and smart feed guard fields are retired, fixed off upstream; they are not missing active toggles.

| HomeSettings field | Original persisted key(s) | Exact source default / migration expression |
|---|---|---|
| `displayMode` | `display_mode` | `preferences[KEY_DISPLAY_MODE] ?: 0` |
| `isBottomBarFloating` | `bottom_bar_floating` | `preferences[KEY_BOTTOM_BAR_FLOATING] ?: true` |
| `navigationIconCrossScaleEnabled` | `navigation_icon_cross_scale_enabled` | `preferences[KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED] ?: true` |
| `bottomBarLabelMode` | `bottom_bar_label_mode` | `preferences[KEY_BOTTOM_BAR_LABEL_MODE] ?: BottomBarLabelMode.ICON_AND_TEXT` |
| `topTabLabelMode` | `top_tab_label_mode` | `preferences[KEY_TOP_TAB_LABEL_MODE] ?: TopTabLabelMode.TEXT_ONLY` |
| `hideTopTabs` | `hide_top_tabs` | `preferences[KEY_HIDE_TOP_TABS] ?: false` |
| `homeTopRightAction` | `home_top_right_action` | `HomeTopRightAction.fromValue( preferences[KEY_HOME_TOP_RIGHT_ACTION] ?: HomeTopRightAction.SETTINGS.value )` |
| `homeTopLayoutOrder` | `home_top_layout_order` | `HomeTopLayoutOrder.fromValue( preferences[KEY_HOME_TOP_LAYOUT_ORDER] ?: HomeTopLayoutOrder.SEARCH_THEN_TABS.value )` |
| `isHeaderBlurEnabled` | `home_header_blur_mode`, `header_blur_enabled` | `headerBlurMode != HomeHeaderBlurMode.ALWAYS_OFF` |
| `headerBlurMode` | `home_header_blur_mode`, `header_blur_enabled` | `headerBlurMode` |
| `isBottomBarBlurEnabled` | `bottom_bar_blur_enabled` | `preferences[KEY_BOTTOM_BAR_BLUR_ENABLED] ?: false` |
| `isTopBarLiquidGlassEnabled` | `top_bar_liquid_glass_enabled` | `preferences[KEY_TOP_BAR_LIQUID_GLASS_ENABLED] ?: false` |
| `isHomeSearchLiquidGlassEnabled` | `home_search_liquid_glass_enabled`, `top_bar_liquid_glass_enabled` | `preferences[KEY_HOME_SEARCH_LIQUID_GLASS_ENABLED] ?: (preferences[KEY_TOP_BAR_LIQUID_GLASS_ENABLED] ?: false)` |
| `isBottomBarLiquidGlassEnabled` | `bottom_bar_liquid_glass_enabled`, `liquid_glass_enabled` | `preferences[KEY_BOTTOM_BAR_LIQUID_GLASS_ENABLED] ?: legacyLiquidGlassEnabled` |
| `isBottomBarSearchEnabled` | `bottom_bar_search_enabled` | `preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] ?: false` |
| `listScopedSearchEnabled` | `list_scoped_search_enabled` | `preferences[KEY_LIST_SCOPED_SEARCH_ENABLED] ?: false` |
| `bottomBarSearchAutoExpandMode` | `bottom_bar_search_auto_expand_mode` | `BottomBarSearchAutoExpandMode.fromValue( preferences[KEY_BOTTOM_BAR_SEARCH_AUTO_EXPAND_MODE] ?: BottomBarSearchAutoExpandMode.EXPAND_AT_HOME_TOP.value )` |
| `bottomBarSearchLayoutMode` | `bottom_bar_search_layout_mode` | `BottomBarSearchLayoutMode.fromValue( preferences[KEY_BOTTOM_BAR_SEARCH_LAYOUT_MODE] ?: BottomBarSearchLayoutMode.FULL_DOCK.value )` |
| `androidNativeLiquidGlassEnabled` | `android_native_liquid_glass_enabled` | `preferences[KEY_ANDROID_NATIVE_LIQUID_GLASS_ENABLED] ?: false` |
| `liquidGlassStyle` | `liquid_glass_style` | `legacyLiquidGlassStyle` |
| `liquidGlassMode` | `liquid_glass_mode` | `liquidGlassMode` |
| `liquidGlassStrength` | `liquid_glass_strength` | `liquidGlassStrength` |
| `liquidGlassProgress` | `liquid_glass_material_progress_v2`, `liquid_glass_mode`, `liquid_glass_strength`, `liquid_glass_style` | `liquidGlassProgress` |
| `liquidGlassReadabilityMode` |  | `liquidGlassReadabilityMode` |
| `liquidGlassAdvancedSettings` | `liquid_glass_advanced_preset`, `liquid_glass_progressive_blur_radius`, `liquid_glass_progressive_blur_extent`, `liquid_glass_progressive_blur_curve`, `liquid_glass_content_readability`, `liquid_glass_chromatic_aberration`, `liquid_glass_content_distortion` | `liquidGlassAdvancedSettings` |
| `homeHeaderCollapseMode` | `home_header_collapse_mode`, `header_collapse_enabled` | `headerCollapseMode` |
| `homeBarHideType` | `home_bar_hide_type` | `HomeBarHideType.fromValue( preferences[KEY_HOME_BAR_HIDE_TYPE] ?: HomeBarHideType.SYNC.value )` |
| `commonListHeaderCollapseMode` | `common_list_header_collapse_mode` | `CommonListHeaderCollapseMode.fromValue( preferences[KEY_COMMON_LIST_HEADER_COLLAPSE_MODE] ?: CommonListHeaderCollapseMode.SHOW_ON_REVERSE_SCROLL.value )` |
| `isHeaderCollapseEnabled` | `home_header_collapse_mode`, `header_collapse_enabled` | `headerCollapseMode.hasAnyCollapse` |
| `showPgcTimeline` | `show_pgc_timeline` | `preferences[KEY_SHOW_PGC_TIMELINE] ?: true` |
| `gridColumnCount` | `grid_column_count` | `preferences[KEY_GRID_COLUMN_COUNT] ?: 0` |
| `gridColumnCountCompact` | `grid_column_count_compact` | `preferences[KEY_GRID_COLUMN_COUNT_COMPACT] ?: 0` |
| `pinchToChangeGridColumnsEnabled` | `pinch_to_change_grid_columns_enabled` | `preferences[KEY_PINCH_TO_CHANGE_GRID_COLUMNS_ENABLED] ?: true` |
| `homeFeedCardWidthPreset` | `home_feed_card_width_preset` | `HomeFeedCardWidthPreset.fromValue( preferences[KEY_HOME_FEED_CARD_WIDTH_PRESET] ?: HomeFeedCardWidthPreset.AUTO.value )` |
| `homeFeedCardStyle` | `home_feed_card_style` | `HomeFeedCardStyle.fromValue( preferences[KEY_HOME_FEED_CARD_STYLE] ?: HomeFeedCardStyle.BILIPAI.value )` |
| `homeHeroCarouselEnabled` | `home_hero_carousel_enabled` | `preferences[KEY_HOME_HERO_CAROUSEL_ENABLED] ?: false` |
| `homeHeroCarouselAutoplayEnabled` | `home_hero_carousel_autoplay_enabled` | `preferences[KEY_HOME_HERO_CAROUSEL_AUTOPLAY_ENABLED] ?: false` |
| `homeRefreshTipVisible` | `home_refresh_tip_visible` | `preferences[KEY_HOME_REFRESH_TIP_VISIBLE] ?: true` |
| `cardAnimationEnabled` | `card_animation_enabled` | `preferences[KEY_CARD_ANIMATION_ENABLED] ?: false` |
| `cardTransitionEnabled` | `card_transition_enabled` | `preferences[KEY_CARD_TRANSITION_ENABLED] ?: true` |
| `videoSharedTransitionSpeed` | `video_shared_transition_speed` | `VideoSharedTransitionSpeed.fromValue( preferences[KEY_VIDEO_SHARED_TRANSITION_SPEED] ?: VideoSharedTransitionSpeed.STANDARD.value )` |
| `videoSharedTransitionCustomDurationMillis` | `video_shared_transition_custom_duration_millis` | `normalizeVideoSharedTransitionCustomDurationMillis( preferences[KEY_VIDEO_SHARED_TRANSITION_CUSTOM_DURATION_MILLIS] ?: VIDEO_SHARED_TRANSITION_CUSTOM_DEFAULT_MILLIS )` |
| `smartVisualGuardEnabled` |  | `false` (retired) |
| `runtimeVisualGuardEnabled` | `runtime_visual_guard_enabled` | `preferences[KEY_RUNTIME_VISUAL_GUARD_ENABLED] ?: true` |
| `compactVideoStatsOnCover` | `compact_video_stats_on_cover` | `preferences[KEY_COMPACT_VIDEO_STATS_ON_COVER] ?: false` |
| `lowQualityHomeCoverInDataSaver` | `low_quality_home_cover_in_data_saver` | `preferences[KEY_LOW_QUALITY_HOME_COVER_IN_DATA_SAVER] ?: false` |
| `showHomeCoverGlassBadges` |  | `false` (retired) |
| `showHomeInfoGlassBadges` |  | `false` (retired) |
| `homeCardBadgeEffectMode` |  | `HomeCardBadgeEffectMode.OFF` (retired) |
| `homeCardInfoGlassMode` |  | `HomeCardInfoGlassMode.OFF` (retired) |
| `homeWallpaperEffectMode` | `home_wallpaper_effect_mode` | `HomeWallpaperEffectMode.fromValue( preferences[KEY_HOME_WALLPAPER_EFFECT_MODE] ?: HomeWallpaperEffectMode.SOFT_BLUR.value )` |
| `homeWallpaperEffectScope` | `home_wallpaper_effect_scope` | `HomeWallpaperEffectScope.fromValue( preferences[KEY_HOME_WALLPAPER_EFFECT_SCOPE] ?: HomeWallpaperEffectScope.HOME_ONLY.value )` |
| `showHomeUpBadges` | `home_up_badges_visible` | `preferences[KEY_HOME_UP_BADGES_VISIBLE] ?: false` |
| `showHomeUpAvatars` | `home_up_avatars_visible` | `preferences[KEY_HOME_UP_AVATARS_VISIBLE] ?: false` |
| `showHomePublishTime` | `home_publish_time_visible` | `preferences[KEY_HOME_PUBLISH_TIME_VISIBLE] ?: true` |
| `showFullVideoCardContent` | `full_video_card_content_visible` | `preferences[KEY_FULL_VIDEO_CARD_CONTENT_VISIBLE] ?: false` |
| `videoCardLongPressActionEnabled` | `video_card_long_press_action_enabled` | `preferences[KEY_VIDEO_CARD_LONG_PRESS_ACTION_ENABLED] ?: false` |
| `homeCardDynamicTintEnabled` | `home_card_dynamic_tint_enabled` | `preferences[KEY_HOME_CARD_DYNAMIC_TINT_ENABLED] ?: false` |
| `homeCardFrostedGlassEnabled` | `home_card_frosted_glass_enabled`, `home_card_dynamic_tint_enabled` | `resolveHomeCardFrostedGlassEnabled( storedValue = preferences[KEY_HOME_CARD_FROSTED_GLASS_ENABLED], legacyCombinedValue = preferences[KEY_HOME_CARD_DYNAMIC_TINT_ENABLED], )` |
| `homeDurationStyle` | `home_duration_style`, `home_video_duration_badges_visible` | `preferences[KEY_HOME_DURATION_STYLE] ?.let(HomeDurationStyle::fromValue) ?: if (preferences[KEY_HOME_VIDEO_DURATION_BADGES_VISIBLE] ?: true) { HomeDurationStyle.OUTSIDE_COVER } else { HomeDurationStyle.HIDDEN }` |
| `easterEggEnabled` | `easter_egg_enabled` | `preferences[KEY_EASTER_EGG_ENABLED] ?: false` |
| `crashTrackingConsentShown` | `crash_tracking_consent_shown` | `preferences[KEY_CRASH_TRACKING_CONSENT_SHOWN] ?: false` |

## Filters and additional state

The current Windows Home already consumes its real feed-source/count preferences, original recommendation feedback, the global full-model blocked-UP store, and plugin feed-filter configuration. These remain unchanged. Original HomeSettings display values above are not silently mirrored into that existing discovery preference authority.

Dynamic prepared transport retains `visible=false` folded items; display filtering uses existing `desktopVisibleDynamicItems` only for blocked author IDs, and current Windows cards retain their existing manual unfold behavior. Original rich DynamicCard/image-preview/header/user-rail rendering remains a separate missing UI block. Article/UP tabs, pin/hidden users, unread following metadata, local dynamic not-interested IDs, cold-start feed cache, and persisted selected user/tab/display state remain incomplete. Pure `DynamicTabPolicy` is included for shared original identity and labels, not as evidence that tab visibility/order/UP routing is implemented.

## Fetch / ownership boundary

Incremental refresh sends the retained `update_baseline` only on its first empty-offset request. The original multi-page fetch uses first-page `update_num`, stops on unchanged/empty cursor, retains folded payloads, and preserves the old tail cursor when the original preservation predicate allows it. Original page policy requires overlap and excludes cache placeholders, preserves old duplicate payload objects, prepends new items, then performs the original stable publish-time sort. Missing overlap causes replacement and the original ViewModel fresh-pagination synchronization. Original append de-duplicates by original key.

Windows binds those functions to the actual shared CommunityRepository callback and captures immutable MID + epoch for pre/post request ownership checks. Coroutine cancellation propagates and never becomes a fake API error. One request mutex protects page publication. As in the original repository, pagination state is updated per returned page: a later failed/cancelled multi-page request is **not a transactional pagination rollback**. The cancellation fixture proves retained page publication and mutex release, not a broader guarantee that every internal cursor mutation is undone.

No actual Bilibili account, API socket, native window or shared Gradle was used. The actual original controls and actual consumer geometry were tested in ImageComposeScene; actual product Retrofit/API query fields were tested through an application interceptor which never proceeds to a socket.
