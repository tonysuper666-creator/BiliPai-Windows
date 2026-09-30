// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/SettingsSemanticIconPolicy.kt; do not edit.
// LF-normalized SHA-256: b208983b36fe392708836eb45e7712ff72862454e84f40fba708a17cfc097d54
package com.android.purebilibili.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.bilipai.desktop.settings.DesktopSettingsSymbols
import com.bilipai.desktop.settings.DesktopSettingsVectors
import com.android.purebilibili.core.ui.AppSemanticIconFamily
import com.android.purebilibili.core.ui.rememberAppSemanticVisualPolicy

internal enum class SettingsIconRole {
    INTERFACE_THEME,
    HOME_FEED,
    NAVIGATION,
    PLAYBACK_QUALITY,
    FULLSCREEN_GESTURE,
    COPY_TEXT,
    INTERACTION_COMMENT,
    DATA_BACKUP,
    PRIVACY_PERMISSION,
    DIAGNOSTICS,
    ABOUT_SUPPORT,
    APPEARANCE,
    ANIMATION,
    PLAYBACK,
    BOTTOM_BAR,
    PERMISSION,
    MESSAGE_NOTIFICATION,
    BLOCKED_LIST,
    SETTINGS_SHARE,
    WEBDAV_BACKUP,
    DOWNLOAD_PATH,
    IMAGE_SAVE_PATH,
    CLEAR_CACHE,
    PLUGINS,
    EXPORT_LOGS,
    OPEN_SOURCE_LICENSES,
    OPEN_SOURCE_HOME,
    CHECK_UPDATE,
    VIEW_RELEASE_NOTES,
    REPLAY_ONBOARDING,
    TIPS,
    OPEN_LINKS,
    DONATE,
    DISCLAIMER,
    RELEASE_CHANNEL,
    CRASH_TRACKING,
    ANALYTICS,
    FEED_API,
    REFRESH_COUNT,
    DYNAMIC_PREVIEW_TEXT,
    DYNAMIC_TAB_VISIBILITY,
    EASTER_EGG,
    AUTO_CHECK_UPDATE,
    BUILD_SOURCE,
    BUILD_FINGERPRINT,
    BUILD_VERIFICATION,
    ANDROID_LIQUID_GLASS,
    DYNAMIC_COLOR,
    THEME_COLOR_PICKER,
    COLOR_STYLE,
    COLOR_SPEC,
    APP_LANGUAGE,
    FONT_FILE,
    SPLASH_WALLPAPER,
    RANDOM_WALLPAPER,
    DISPLAY_STYLE,
    HOME_COVER_GLASS,
    VIDEO_DURATION_BADGES,
    HOME_INFO_GLASS,
    HOME_WALLPAPER,
    WALLPAPER_EFFECT,
    HOME_UP_BADGES,
    HOME_UP_AVATAR,
    FULL_VIDEO_CARD_CONTENT,
    ONLINE_COUNT,
    GRID_COLUMNS,
    HOME_CARD_WIDTH,
    CARD_ENTRANCE_ANIMATION,
    CARD_TRANSITION_ANIMATION,
    LIVE_SURFACE_TRANSITION,
    PREDICTIVE_BACK,
    MIUIX_TRANSITION_BLUR,
    TOP_DOCK_GLASS,
    HOME_SEARCH_GLASS,
    BOTTOM_BAR_GLASS,
    TOP_BAR_BLUR,
    HEADER_COLLAPSE,
    BOTTOM_BAR_BLUR,
    FLOATING_BOTTOM_BAR,
    HARDWARE_DECODER,
    PLAYBACK_SPEED,
    NATIVE_MIUIX_DIALOG,
    LONG_PRESS_SPEED_HINT,
    RESUME_PLAYBACK_PROMPT,
    STOP_ON_EXIT,
    BACKGROUND_PLAYBACK,
    PLAYLIST_AUTO_CONTINUE,
    AUDIO_FOCUS,
    SLIDE_VOLUME_BRIGHTNESS,
    PIP_DANMAKU,
    DANMAKU_CLOUD_SYNC,
    AUDIO_MODE_PIP,
    PLAYER_DIAGNOSTICS,
    QUALITY_WARNING,
    SUBTITLE,
    COMMENT_DECORATION,
    AI_SUMMARY,
    VIDEO_NOTE,
    LIKE_INTERACTION,
    FAVORITE_TAP_MODE,
    VIDEO_DESCRIPTION,
    FULLSCREEN_ORIENTATION,
    HORIZONTAL_ADAPTATION,
    FULLSCREEN_GESTURE_REVERSE,
    IMMERSIVE_STATUS_BAR,
    AUTO_ENTER_FULLSCREEN,
    AUTO_EXIT_FULLSCREEN,
    FULLSCREEN_LOCK,
    FULLSCREEN_SCREENSHOT,
    CLEAN_SCREENSHOT,
    BATTERY_STATUS,
    TIME_STATUS,
    PLAYER_ACTIONS,
    PRIVACY_CONTENT_AUTHENTICATION,
    PLAYER_STATS,
    PLAYER_DIAGNOSTIC_LOGS,
    QUALITY_WARNING_ONCE,
    DIRECTED_TRAFFIC,
    AUTO_HIGHEST_QUALITY,
    AUTO_PLAY_ON_OPEN,
    STARTUP_PORTRAIT_FEED,
    HOME_HERO_AUTOPLAY,
    AUTO_PLAY_NEXT,
    VIDEO_NOTE_COLLAPSE,
    INTERACTIVE_COMMANDS,
    PORTRAIT_SWIPE_FULLSCREEN,
    CENTER_SWIPE_FULLSCREEN,
    SYSTEM_BRIGHTNESS,
    APP_ICON,
    HOME_CARD_STATS_COMPACT,
    HOME_HERO_CAROUSEL,
    HOME_ONLINE_COUNT,
    PORTRAIT_STORY_ENTRY,
    DISPLAY_SCALE,
    UI_ENTRANCE_ANIMATION,
    FULLSCREEN_SWIPE_BACK,
    FOLLOW_BUTTON,
    PRIVACY_HISTORY,
    CUSTOM_MD3_COLOR,
    THEME_LIGHT_BACKGROUND,
    THEME_LIGHT_PRIMARY_TEXT,
    THEME_LIGHT_SECONDARY_TEXT,
    THEME_LIGHT_CONTROL,
    THEME_DARK_BACKGROUND,
    THEME_DARK_PRIMARY_TEXT,
    THEME_DARK_SECONDARY_TEXT,
    THEME_DARK_CONTROL,
    DEVELOPER_CRASH_TRACKING,
    DEVELOPER_ANALYTICS,
    APP_VERSION,
    BOTTOM_BAR_GLASS_PREVIEW,
    ADVANCED_COLOR,
    CAST_BUTTON,
    PROGRESS_PEAK_DANMAKU,
    IMAGE_3D_PAGE,
    SPLASH_ICON_ANIMATION,
    NAV_ICON_CROSS_SCALE,
    SUB_REPLY_LOADED_COUNT,
    COMMENT_VISIBILITY_CHECK,
    PORTRAIT_AMBIENT_HAZE,
    BACK_TO_TOP,
    HOME_HEADER_COLLAPSE,
    PGC_TIMELINE,
    RELATED_VIDEO_TRANSITION,
    RETURN_GESTURE_POSE,
    AUTO_SKIP_OP_ED,
    BLUR_INTENSITY,
    HAPTIC_FEEDBACK,
    REMEMBER_PLAYBACK_SPEED,
    SPACE_PLAYED_VIDEO_LOCATE,
    IMAGE_LONG_PRESS_ACTION,
    PLAYER_COLLAPSE_PAUSE,
    BOTTOM_BAR_SEARCH,
    DATA_SAVER_COVER_QUALITY,
    SEGMENT_LOADING_COMPATIBILITY,
    NOTIFICATION_SCOPE_MESSAGE,
    NOTIFICATION_SCOPE_REPLY,
    NOTIFICATION_SCOPE_AT_ME,
    NOTIFICATION_SCOPE_LIKE,
    NOTIFICATION_SCOPE_SYSTEM,
    NOTIFICATION_SCOPE_DYNAMIC_UP,
    NOTIFICATION_SCOPE_LIVE,
}

@Composable
internal fun rememberSettingsSemanticIcon(
    role: SettingsIconRole,
): ImageVector {
    // The component skin owns the colored container and control styling. The glyph itself
    // always comes from the role-specific local vector so missing MIUIX icons are never
    // substituted with unrelated phone/settings/contact symbols.
    return DesktopSettingsVectors.vector(resolveSettingsMaterialSymbolResource(role))
}

/**
 * 设置语义与组件皮肤解耦：所有外观都使用一一对应的本地 VectorDrawable，Miuix 仅负责
 * 外层容器、着色和控件形态，避免用无关 glyph 填补其图标库缺项。
 */
internal fun resolveSettingsMaterialSymbolResource(role: SettingsIconRole): String = when (role) {
    SettingsIconRole.INTERFACE_THEME -> DesktopSettingsSymbols.ms_color_lens_24
    SettingsIconRole.HOME_FEED -> DesktopSettingsSymbols.ms_home_24
    SettingsIconRole.NAVIGATION -> DesktopSettingsSymbols.ms_dashboard_24
    SettingsIconRole.PLAYBACK_QUALITY -> DesktopSettingsSymbols.ms_high_quality_24
    SettingsIconRole.FULLSCREEN_GESTURE -> DesktopSettingsSymbols.ms_touch_app_24
    SettingsIconRole.COPY_TEXT -> DesktopSettingsSymbols.ms_content_copy_24
    SettingsIconRole.INTERACTION_COMMENT -> DesktopSettingsSymbols.ms_chat_bubble_outline_24
    SettingsIconRole.DATA_BACKUP -> DesktopSettingsSymbols.ms_backup_24
    SettingsIconRole.PRIVACY_PERMISSION -> DesktopSettingsSymbols.ms_lock_24
    SettingsIconRole.DIAGNOSTICS -> DesktopSettingsSymbols.ms_terminal_24
    SettingsIconRole.ABOUT_SUPPORT -> DesktopSettingsSymbols.ms_info_24
    SettingsIconRole.APPEARANCE -> DesktopSettingsSymbols.ms_palette_24
    SettingsIconRole.ANIMATION -> DesktopSettingsSymbols.ms_animation_24
    SettingsIconRole.PLAYBACK -> DesktopSettingsSymbols.ms_play_circle_24
    SettingsIconRole.BOTTOM_BAR -> DesktopSettingsSymbols.ms_widgets_24
    SettingsIconRole.PERMISSION -> DesktopSettingsSymbols.ms_security_24
    SettingsIconRole.MESSAGE_NOTIFICATION -> DesktopSettingsSymbols.ms_notifications_24
    SettingsIconRole.BLOCKED_LIST -> DesktopSettingsSymbols.ms_block_24
    SettingsIconRole.SETTINGS_SHARE -> DesktopSettingsSymbols.ms_share_24
    SettingsIconRole.WEBDAV_BACKUP -> DesktopSettingsSymbols.ms_cloud_upload_24
    SettingsIconRole.DOWNLOAD_PATH -> DesktopSettingsSymbols.ms_folder_24
    SettingsIconRole.IMAGE_SAVE_PATH -> DesktopSettingsSymbols.ms_photo_24
    SettingsIconRole.CLEAR_CACHE -> DesktopSettingsSymbols.ms_delete_outline_24
    SettingsIconRole.PLUGINS -> DesktopSettingsSymbols.ms_extension_24
    SettingsIconRole.EXPORT_LOGS -> DesktopSettingsSymbols.ms_article_24
    SettingsIconRole.OPEN_SOURCE_LICENSES -> DesktopSettingsSymbols.ms_gavel_24
    SettingsIconRole.OPEN_SOURCE_HOME -> DesktopSettingsSymbols.ms_open_in_new_24
    SettingsIconRole.CHECK_UPDATE -> DesktopSettingsSymbols.ms_system_update_24
    SettingsIconRole.VIEW_RELEASE_NOTES -> DesktopSettingsSymbols.ms_newspaper_24
    SettingsIconRole.REPLAY_ONBOARDING -> DesktopSettingsSymbols.ms_replay_24
    SettingsIconRole.TIPS -> DesktopSettingsSymbols.ms_lightbulb_24
    SettingsIconRole.OPEN_LINKS -> DesktopSettingsSymbols.ms_link_24
    SettingsIconRole.DONATE -> DesktopSettingsSymbols.ms_card_giftcard_24
    SettingsIconRole.DISCLAIMER -> DesktopSettingsSymbols.ms_warning_amber_24
    SettingsIconRole.RELEASE_CHANNEL -> DesktopSettingsSymbols.ms_rocket_24
    SettingsIconRole.CRASH_TRACKING -> DesktopSettingsSymbols.ms_bug_report_24
    SettingsIconRole.ANALYTICS -> DesktopSettingsSymbols.ms_analytics_24
    SettingsIconRole.FEED_API -> DesktopSettingsSymbols.ms_rss_feed_24
    SettingsIconRole.REFRESH_COUNT -> DesktopSettingsSymbols.ms_refresh_24
    SettingsIconRole.DYNAMIC_PREVIEW_TEXT -> DesktopSettingsSymbols.ms_text_snippet_24
    SettingsIconRole.DYNAMIC_TAB_VISIBILITY -> DesktopSettingsSymbols.ms_visibility_24
    SettingsIconRole.EASTER_EGG -> DesktopSettingsSymbols.ms_auto_awesome_24
    SettingsIconRole.AUTO_CHECK_UPDATE -> DesktopSettingsSymbols.ms_update_24
    SettingsIconRole.BUILD_SOURCE -> DesktopSettingsSymbols.ms_tag_24
    SettingsIconRole.BUILD_FINGERPRINT -> DesktopSettingsSymbols.ms_fingerprint_24
    SettingsIconRole.BUILD_VERIFICATION -> DesktopSettingsSymbols.ms_verified_user_24
    SettingsIconRole.ANDROID_LIQUID_GLASS -> DesktopSettingsSymbols.ms_water_drop_24
    SettingsIconRole.DYNAMIC_COLOR -> DesktopSettingsSymbols.ms_format_color_text_24
    SettingsIconRole.THEME_COLOR_PICKER -> DesktopSettingsSymbols.ms_colorize_24
    SettingsIconRole.COLOR_STYLE -> DesktopSettingsSymbols.ms_brush_24
    SettingsIconRole.COLOR_SPEC -> DesktopSettingsSymbols.ms_auto_fix_high_24
    SettingsIconRole.APP_LANGUAGE -> DesktopSettingsSymbols.ms_language_24
    SettingsIconRole.FONT_FILE -> DesktopSettingsSymbols.ms_font_download_24
    SettingsIconRole.SPLASH_WALLPAPER -> DesktopSettingsSymbols.ms_wallpaper_24
    SettingsIconRole.RANDOM_WALLPAPER -> DesktopSettingsSymbols.ms_shuffle_24
    SettingsIconRole.DISPLAY_STYLE -> DesktopSettingsSymbols.ms_view_carousel_24
    SettingsIconRole.HOME_COVER_GLASS -> DesktopSettingsSymbols.ms_opacity_24
    SettingsIconRole.VIDEO_DURATION_BADGES -> DesktopSettingsSymbols.ms_timer_24
    SettingsIconRole.HOME_INFO_GLASS -> DesktopSettingsSymbols.ms_badge_24
    SettingsIconRole.HOME_WALLPAPER -> DesktopSettingsSymbols.ms_image_24
    SettingsIconRole.WALLPAPER_EFFECT -> DesktopSettingsSymbols.ms_blur_on_24
    SettingsIconRole.HOME_UP_BADGES -> DesktopSettingsSymbols.ms_workspace_premium_24
    SettingsIconRole.HOME_UP_AVATAR -> DesktopSettingsSymbols.ms_account_circle_24
    SettingsIconRole.FULL_VIDEO_CARD_CONTENT -> DesktopSettingsSymbols.ms_notes_24
    SettingsIconRole.ONLINE_COUNT -> DesktopSettingsSymbols.ms_online_prediction_24
    SettingsIconRole.GRID_COLUMNS -> DesktopSettingsSymbols.ms_grid_view_24
    SettingsIconRole.HOME_CARD_WIDTH -> DesktopSettingsSymbols.ms_width_normal_24
    SettingsIconRole.CARD_ENTRANCE_ANIMATION -> DesktopSettingsSymbols.ms_auto_awesome_motion_24
    SettingsIconRole.CARD_TRANSITION_ANIMATION -> DesktopSettingsSymbols.ms_sync_alt_24
    SettingsIconRole.LIVE_SURFACE_TRANSITION -> DesktopSettingsSymbols.ms_movie_24
    SettingsIconRole.PREDICTIVE_BACK -> DesktopSettingsSymbols.ms_arrow_back_24
    SettingsIconRole.MIUIX_TRANSITION_BLUR -> DesktopSettingsSymbols.ms_gradient_24
    SettingsIconRole.TOP_DOCK_GLASS -> DesktopSettingsSymbols.ms_layers_24
    SettingsIconRole.HOME_SEARCH_GLASS -> DesktopSettingsSymbols.ms_manage_search_24
    SettingsIconRole.BOTTOM_BAR_GLASS -> DesktopSettingsSymbols.ms_blur_circular_24
    SettingsIconRole.TOP_BAR_BLUR -> DesktopSettingsSymbols.ms_view_headline_24
    SettingsIconRole.HEADER_COLLAPSE -> DesktopSettingsSymbols.ms_keyboard_arrow_up_24
    SettingsIconRole.BOTTOM_BAR_BLUR -> DesktopSettingsSymbols.ms_blur_linear_24
    SettingsIconRole.FLOATING_BOTTOM_BAR -> DesktopSettingsSymbols.ms_view_agenda_24
    SettingsIconRole.HARDWARE_DECODER -> DesktopSettingsSymbols.ms_memory_24
    SettingsIconRole.PLAYBACK_SPEED -> DesktopSettingsSymbols.ms_speed_24
    SettingsIconRole.NATIVE_MIUIX_DIALOG -> DesktopSettingsSymbols.ms_chat_bubble_outline_24
    SettingsIconRole.LONG_PRESS_SPEED_HINT -> DesktopSettingsSymbols.ms_visibility_off_24
    SettingsIconRole.RESUME_PLAYBACK_PROMPT -> DesktopSettingsSymbols.ms_restore_24
    SettingsIconRole.STOP_ON_EXIT -> DesktopSettingsSymbols.ms_stop_circle_24
    SettingsIconRole.BACKGROUND_PLAYBACK -> DesktopSettingsSymbols.ms_music_note_24
    SettingsIconRole.PLAYLIST_AUTO_CONTINUE -> DesktopSettingsSymbols.ms_queue_play_next_24
    SettingsIconRole.AUDIO_FOCUS -> DesktopSettingsSymbols.ms_headphones_24
    SettingsIconRole.SLIDE_VOLUME_BRIGHTNESS -> DesktopSettingsSymbols.ms_swap_vert_24
    SettingsIconRole.PIP_DANMAKU -> DesktopSettingsSymbols.ms_textsms_24
    SettingsIconRole.DANMAKU_CLOUD_SYNC -> DesktopSettingsSymbols.ms_cloud_sync_24
    SettingsIconRole.AUDIO_MODE_PIP -> DesktopSettingsSymbols.ms_picture_in_picture_24
    SettingsIconRole.PLAYER_DIAGNOSTICS -> DesktopSettingsSymbols.ms_query_stats_24
    SettingsIconRole.QUALITY_WARNING -> DesktopSettingsSymbols.ms_report_problem_24
    SettingsIconRole.SUBTITLE -> DesktopSettingsSymbols.ms_subtitles_24
    SettingsIconRole.COMMENT_DECORATION -> DesktopSettingsSymbols.ms_mode_comment_24
    SettingsIconRole.AI_SUMMARY -> DesktopSettingsSymbols.ms_smart_toy_24
    SettingsIconRole.VIDEO_NOTE -> DesktopSettingsSymbols.ms_edit_note_24
    SettingsIconRole.LIKE_INTERACTION -> DesktopSettingsSymbols.ms_thumb_up_off_alt_24
    SettingsIconRole.FAVORITE_TAP_MODE -> DesktopSettingsSymbols.ms_collections_bookmark_24
    SettingsIconRole.VIDEO_DESCRIPTION -> DesktopSettingsSymbols.ms_subject_24
    SettingsIconRole.FULLSCREEN_ORIENTATION -> DesktopSettingsSymbols.ms_screen_rotation_24
    SettingsIconRole.HORIZONTAL_ADAPTATION -> DesktopSettingsSymbols.ms_aspect_ratio_24
    SettingsIconRole.FULLSCREEN_GESTURE_REVERSE -> DesktopSettingsSymbols.ms_swipe_vertical_24
    SettingsIconRole.IMMERSIVE_STATUS_BAR -> DesktopSettingsSymbols.ms_fullscreen_24
    SettingsIconRole.AUTO_ENTER_FULLSCREEN -> DesktopSettingsSymbols.ms_open_in_full_24
    SettingsIconRole.AUTO_EXIT_FULLSCREEN -> DesktopSettingsSymbols.ms_fullscreen_exit_24
    SettingsIconRole.FULLSCREEN_LOCK -> DesktopSettingsSymbols.ms_screen_lock_rotation_24
    SettingsIconRole.FULLSCREEN_SCREENSHOT -> DesktopSettingsSymbols.ms_screenshot_24
    SettingsIconRole.CLEAN_SCREENSHOT -> DesktopSettingsSymbols.ms_screenshot_monitor_24
    SettingsIconRole.BATTERY_STATUS -> DesktopSettingsSymbols.ms_battery_full_24
    SettingsIconRole.TIME_STATUS -> DesktopSettingsSymbols.ms_access_time_24
    SettingsIconRole.PLAYER_ACTIONS -> DesktopSettingsSymbols.ms_more_horiz_24
    SettingsIconRole.PRIVACY_CONTENT_AUTHENTICATION -> DesktopSettingsSymbols.ms_verified_24
    SettingsIconRole.PLAYER_STATS -> DesktopSettingsSymbols.ms_insert_chart_outlined_24
    SettingsIconRole.PLAYER_DIAGNOSTIC_LOGS -> DesktopSettingsSymbols.ms_report_gmailerrorred_24
    SettingsIconRole.QUALITY_WARNING_ONCE -> DesktopSettingsSymbols.ms_notification_important_24
    SettingsIconRole.DIRECTED_TRAFFIC -> DesktopSettingsSymbols.ms_network_locked_24
    SettingsIconRole.AUTO_HIGHEST_QUALITY -> DesktopSettingsSymbols.ms_settings_suggest_24
    SettingsIconRole.AUTO_PLAY_ON_OPEN -> DesktopSettingsSymbols.ms_play_arrow_24
    SettingsIconRole.STARTUP_PORTRAIT_FEED -> DesktopSettingsSymbols.ms_vertical_align_top_24
    SettingsIconRole.HOME_HERO_AUTOPLAY -> DesktopSettingsSymbols.ms_smart_display_24
    SettingsIconRole.AUTO_PLAY_NEXT -> DesktopSettingsSymbols.ms_playlist_play_24
    SettingsIconRole.VIDEO_NOTE_COLLAPSE -> DesktopSettingsSymbols.ms_short_text_24
    SettingsIconRole.INTERACTIVE_COMMANDS -> DesktopSettingsSymbols.ms_comments_disabled_24
    SettingsIconRole.PORTRAIT_SWIPE_FULLSCREEN -> DesktopSettingsSymbols.ms_swipe_up_24
    SettingsIconRole.CENTER_SWIPE_FULLSCREEN -> DesktopSettingsSymbols.ms_swipe_24
    SettingsIconRole.SYSTEM_BRIGHTNESS -> DesktopSettingsSymbols.ms_brightness_medium_24
    SettingsIconRole.APP_ICON -> DesktopSettingsSymbols.ms_apps_24
    SettingsIconRole.HOME_CARD_STATS_COMPACT -> DesktopSettingsSymbols.ms_stacked_bar_chart_24
    SettingsIconRole.HOME_HERO_CAROUSEL -> DesktopSettingsSymbols.ms_view_day_24
    SettingsIconRole.HOME_ONLINE_COUNT -> DesktopSettingsSymbols.ms_groups_24
    SettingsIconRole.PORTRAIT_STORY_ENTRY -> DesktopSettingsSymbols.ms_stay_current_portrait_24
    SettingsIconRole.DISPLAY_SCALE -> DesktopSettingsSymbols.ms_zoom_out_map_24
    SettingsIconRole.UI_ENTRANCE_ANIMATION -> DesktopSettingsSymbols.ms_motion_photos_on_24
    SettingsIconRole.FULLSCREEN_SWIPE_BACK -> DesktopSettingsSymbols.ms_swipe_right_24
    SettingsIconRole.FOLLOW_BUTTON -> DesktopSettingsSymbols.ms_person_add_24
    SettingsIconRole.PRIVACY_HISTORY -> DesktopSettingsSymbols.ms_history_toggle_off_24
    SettingsIconRole.CUSTOM_MD3_COLOR -> DesktopSettingsSymbols.ms_format_paint_24
    SettingsIconRole.THEME_LIGHT_BACKGROUND -> DesktopSettingsSymbols.ms_light_mode_24
    SettingsIconRole.THEME_LIGHT_PRIMARY_TEXT -> DesktopSettingsSymbols.ms_text_fields_24
    SettingsIconRole.THEME_LIGHT_SECONDARY_TEXT -> DesktopSettingsSymbols.ms_format_size_24
    SettingsIconRole.THEME_LIGHT_CONTROL -> DesktopSettingsSymbols.ms_tune_24
    SettingsIconRole.THEME_DARK_BACKGROUND -> DesktopSettingsSymbols.ms_dark_mode_24
    SettingsIconRole.THEME_DARK_PRIMARY_TEXT -> DesktopSettingsSymbols.ms_text_format_24
    SettingsIconRole.THEME_DARK_SECONDARY_TEXT -> DesktopSettingsSymbols.ms_subtitles_off_24
    SettingsIconRole.THEME_DARK_CONTROL -> DesktopSettingsSymbols.ms_control_point_24
    SettingsIconRole.DEVELOPER_CRASH_TRACKING -> DesktopSettingsSymbols.ms_health_and_safety_24
    SettingsIconRole.DEVELOPER_ANALYTICS -> DesktopSettingsSymbols.ms_data_usage_24
    SettingsIconRole.APP_VERSION -> DesktopSettingsSymbols.ms_new_releases_24
    SettingsIconRole.BOTTOM_BAR_GLASS_PREVIEW -> DesktopSettingsSymbols.ms_lens_blur_24
    SettingsIconRole.ADVANCED_COLOR -> DesktopSettingsSymbols.ms_invert_colors_24
    SettingsIconRole.CAST_BUTTON -> DesktopSettingsSymbols.ms_cast_24
    SettingsIconRole.PROGRESS_PEAK_DANMAKU -> DesktopSettingsSymbols.ms_graphic_eq_24
    SettingsIconRole.IMAGE_3D_PAGE -> DesktopSettingsSymbols.ms_3d_rotation_24
    SettingsIconRole.SPLASH_ICON_ANIMATION -> DesktopSettingsSymbols.ms_filter_frames_24
    SettingsIconRole.NAV_ICON_CROSS_SCALE -> DesktopSettingsSymbols.ms_compare_arrows_24
    SettingsIconRole.SUB_REPLY_LOADED_COUNT -> DesktopSettingsSymbols.ms_numbers_24
    SettingsIconRole.COMMENT_VISIBILITY_CHECK -> DesktopSettingsSymbols.ms_fact_check_24
    SettingsIconRole.PORTRAIT_AMBIENT_HAZE -> DesktopSettingsSymbols.ms_filter_hdr_24
    SettingsIconRole.BACK_TO_TOP -> DesktopSettingsSymbols.ms_keyboard_double_arrow_up_24
    SettingsIconRole.HOME_HEADER_COLLAPSE -> DesktopSettingsSymbols.ms_compress_24
    SettingsIconRole.PGC_TIMELINE -> DesktopSettingsSymbols.ms_calendar_month_24
    SettingsIconRole.RELATED_VIDEO_TRANSITION -> DesktopSettingsSymbols.ms_video_library_24
    SettingsIconRole.RETURN_GESTURE_POSE -> DesktopSettingsSymbols.ms_rotate90_degrees_ccw_24
    SettingsIconRole.AUTO_SKIP_OP_ED -> DesktopSettingsSymbols.ms_skip_next_24
    SettingsIconRole.BLUR_INTENSITY -> DesktopSettingsSymbols.ms_flare_24
    SettingsIconRole.HAPTIC_FEEDBACK -> DesktopSettingsSymbols.ms_touch_app_fill_24
    SettingsIconRole.REMEMBER_PLAYBACK_SPEED -> DesktopSettingsSymbols.ms_history_fill_24
    SettingsIconRole.SPACE_PLAYED_VIDEO_LOCATE -> DesktopSettingsSymbols.ms_search_24
    SettingsIconRole.IMAGE_LONG_PRESS_ACTION -> DesktopSettingsSymbols.ms_photo_library_24
    SettingsIconRole.PLAYER_COLLAPSE_PAUSE -> DesktopSettingsSymbols.ms_pause_24
    SettingsIconRole.BOTTOM_BAR_SEARCH -> DesktopSettingsSymbols.ms_search_fill_24
    SettingsIconRole.DATA_SAVER_COVER_QUALITY -> DesktopSettingsSymbols.ms_wifi_24
    SettingsIconRole.SEGMENT_LOADING_COMPATIBILITY -> DesktopSettingsSymbols.ms_cloud_download_24
    SettingsIconRole.NOTIFICATION_SCOPE_MESSAGE -> DesktopSettingsSymbols.ms_mail_24
    SettingsIconRole.NOTIFICATION_SCOPE_REPLY -> DesktopSettingsSymbols.ms_reply_24
    SettingsIconRole.NOTIFICATION_SCOPE_AT_ME -> DesktopSettingsSymbols.ms_alternate_email_24
    SettingsIconRole.NOTIFICATION_SCOPE_LIKE -> DesktopSettingsSymbols.ms_thumb_up_fill_24
    SettingsIconRole.NOTIFICATION_SCOPE_SYSTEM -> DesktopSettingsSymbols.ms_campaign_24
    SettingsIconRole.NOTIFICATION_SCOPE_DYNAMIC_UP -> DesktopSettingsSymbols.ms_person_24
    SettingsIconRole.NOTIFICATION_SCOPE_LIVE -> DesktopSettingsSymbols.ms_live_tv_24
}

@Composable
internal fun rememberThemeAwareSettingsIcon(
    materialSymbolResource: String,
    miuixIcon: ImageVector,
): ImageVector = when (rememberAppSemanticVisualPolicy().effectiveIconFamily) {
    AppSemanticIconFamily.MATERIAL -> DesktopSettingsVectors.vector(materialSymbolResource)
    AppSemanticIconFamily.MIUIX -> miuixIcon
}

/** Material 设置界面的统一 VectorDrawable → ImageVector 入口。 */
@Composable
internal fun rememberMaterialSymbol(
    materialSymbolResource: String,
): ImageVector = DesktopSettingsVectors.vector(materialSymbolResource)

internal fun resolveSettingsSearchTargetIconRole(
    target: SettingsSearchTarget
): SettingsIconRole = when (target) {
    SettingsSearchTarget.INTERFACE_THEME -> SettingsIconRole.INTERFACE_THEME
    SettingsSearchTarget.HOME_FEED -> SettingsIconRole.HOME_FEED
    SettingsSearchTarget.NAVIGATION -> SettingsIconRole.NAVIGATION
    SettingsSearchTarget.PLAYBACK_QUALITY -> SettingsIconRole.PLAYBACK_QUALITY
    SettingsSearchTarget.FULLSCREEN_GESTURE -> SettingsIconRole.FULLSCREEN_GESTURE
    SettingsSearchTarget.INTERACTION_COMMENT -> SettingsIconRole.INTERACTION_COMMENT
    SettingsSearchTarget.DATA_BACKUP -> SettingsIconRole.DATA_BACKUP
    SettingsSearchTarget.PRIVACY_PERMISSION -> SettingsIconRole.PRIVACY_PERMISSION
    SettingsSearchTarget.DIAGNOSTICS -> SettingsIconRole.DIAGNOSTICS
    SettingsSearchTarget.ABOUT_SUPPORT -> SettingsIconRole.ABOUT_SUPPORT
    SettingsSearchTarget.APPEARANCE -> SettingsIconRole.APPEARANCE
    SettingsSearchTarget.ANIMATION -> SettingsIconRole.ANIMATION
    SettingsSearchTarget.PLAYBACK -> SettingsIconRole.PLAYBACK
    SettingsSearchTarget.BOTTOM_BAR -> SettingsIconRole.BOTTOM_BAR
    SettingsSearchTarget.PERMISSION -> SettingsIconRole.PERMISSION
    SettingsSearchTarget.MESSAGE_NOTIFICATION -> SettingsIconRole.MESSAGE_NOTIFICATION
    SettingsSearchTarget.BLOCKED_LIST -> SettingsIconRole.BLOCKED_LIST
    SettingsSearchTarget.SETTINGS_SHARE -> SettingsIconRole.SETTINGS_SHARE
    SettingsSearchTarget.WEBDAV_BACKUP -> SettingsIconRole.WEBDAV_BACKUP
    SettingsSearchTarget.DOWNLOAD_PATH -> SettingsIconRole.DOWNLOAD_PATH
    SettingsSearchTarget.IMAGE_SAVE_PATH -> SettingsIconRole.IMAGE_SAVE_PATH
    SettingsSearchTarget.CLEAR_CACHE -> SettingsIconRole.CLEAR_CACHE
    SettingsSearchTarget.PLUGINS -> SettingsIconRole.PLUGINS
    SettingsSearchTarget.EXPORT_LOGS -> SettingsIconRole.EXPORT_LOGS
    SettingsSearchTarget.OPEN_SOURCE_LICENSES -> SettingsIconRole.OPEN_SOURCE_LICENSES
    SettingsSearchTarget.OPEN_SOURCE_HOME -> SettingsIconRole.OPEN_SOURCE_HOME
    SettingsSearchTarget.CHECK_UPDATE -> SettingsIconRole.CHECK_UPDATE
    SettingsSearchTarget.VIEW_RELEASE_NOTES -> SettingsIconRole.VIEW_RELEASE_NOTES
    SettingsSearchTarget.REPLAY_ONBOARDING -> SettingsIconRole.REPLAY_ONBOARDING
    SettingsSearchTarget.TIPS -> SettingsIconRole.TIPS
    SettingsSearchTarget.OPEN_LINKS -> SettingsIconRole.OPEN_LINKS
    SettingsSearchTarget.DONATE -> SettingsIconRole.DONATE
    SettingsSearchTarget.TELEGRAM -> SettingsIconRole.OPEN_LINKS
    SettingsSearchTarget.TWITTER -> SettingsIconRole.OPEN_LINKS
    SettingsSearchTarget.DISCLAIMER -> SettingsIconRole.DISCLAIMER
}

internal fun resolveSettingsSemanticIconSizeDp(
    role: SettingsIconRole,
    iconFamily: AppSemanticIconFamily,
): Int {
    if (iconFamily != AppSemanticIconFamily.MIUIX) return 20
    return when (role) {
        SettingsIconRole.HOME_FEED,
        SettingsIconRole.NAVIGATION,
        SettingsIconRole.DIAGNOSTICS,
        SettingsIconRole.ANIMATION,
        SettingsIconRole.BOTTOM_BAR,
        SettingsIconRole.DISPLAY_STYLE,
        SettingsIconRole.GRID_COLUMNS,
        SettingsIconRole.HOME_HERO_CAROUSEL,
        SettingsIconRole.APP_ICON -> 19

        SettingsIconRole.PLAYBACK_QUALITY,
        SettingsIconRole.FOLLOW_BUTTON,
        SettingsIconRole.BUILD_VERIFICATION,
        SettingsIconRole.AUTO_EXIT_FULLSCREEN,
        SettingsIconRole.HEADER_COLLAPSE -> 21

        else -> 20
    }
}
