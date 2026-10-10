package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.ThemeColors
import com.android.purebilibili.core.ui.AppIconStyle
import com.android.purebilibili.core.ui.AppListItemStyle
import com.android.purebilibili.core.ui.blur.BlurIntensity
import com.android.purebilibili.core.ui.components.AppTagChipSize
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.android.purebilibili.feature.video.subtitle.normalizeSubtitleVerticalOffsetFraction
import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.feature.settings.share.*
import com.android.purebilibili.feature.video.danmaku.normalizeDanmakuOpacity
import com.android.purebilibili.feature.video.playback.audio.AUDIO_QUALITY_AUTO
import com.android.purebilibili.feature.video.playback.audio.AUDIO_QUALITY_HI_RES
import com.android.purebilibili.feature.video.playback.audio.AUDIO_QUALITY_DOLBY
import com.bilipai.desktop.ui.DesktopOriginalHomeSettingConstants
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.serialization.json.*

/** A format allowlist over existing Windows consumers, never a second preference store.
 * Section/type identities are the fixed v0.3.3 SettingsManager share definitions.
 * Legacy danmaku keys remain baseline values; explicit portrait/landscape overrides win.
 */
internal object DesktopSettingsShareWindowsCatalog {
    internal data class Field(
        val definition: SettingsShareEntryDefinition,
        val normalize: (JsonElement) -> JsonElement?,
    )
    private class Builder {
        val fields = linkedMapOf<String, Field>()
        fun field(key: String, section: SettingsShareSection, normalize: (JsonElement) -> JsonElement?) {
            check(fields.put(key, Field(SettingsShareEntryDefinition(key, section), normalize)) == null)
        }
        fun booleans(section: SettingsShareSection, vararg keys: String) = keys.forEach { key ->
            field(key, section) { value -> primitive(value)?.takeUnless { it.isString }
                ?.booleanOrNull?.let(::JsonPrimitive) }
        }
        fun ints(section: SettingsShareSection, key: String, allowed: Set<Int>) = field(key, section) { value ->
            primitive(value)?.takeUnless { it.isString }?.intOrNull?.takeIf(allowed::contains)?.let(::JsonPrimitive)
        }
        fun intRange(section: SettingsShareSection, key: String, range: IntRange) = ints(section, key, range.toSet())
        fun floats(section: SettingsShareSection, key: String, normalize: (Float) -> Float) = field(key, section) { value ->
            primitive(value)?.takeUnless { it.isString }?.floatOrNull?.takeIf(Float::isFinite)
                ?.let(normalize)?.takeIf(Float::isFinite)?.let(::JsonPrimitive)
        }
        fun names(section: SettingsShareSection, key: String, allowed: Set<String>) = field(key, section) { value ->
            string(value)?.takeIf(allowed::contains)?.let(::JsonPrimitive)
        }
        fun color(section: SettingsShareSection, key: String) = field(key, section) { value ->
            string(value)?.takeIf(::isValidMd3CustomColorHex)?.let { normalizeMd3CustomColorHex(it) }?.let(::JsonPrimitive)
        }
        fun ids(section: SettingsShareSection, key: String, allowed: Set<String>, emptyAllowed: Boolean) = field(key, section) { value ->
            string(value)?.takeIf { raw ->
                val ids = if (raw.isBlank()) emptyList() else raw.split(",")
                (emptyAllowed || ids.isNotEmpty()) && ids.distinct().size == ids.size && ids.all(allowed::contains)
            }?.let(::JsonPrimitive)
        }
    }
    private fun primitive(value: JsonElement) = (value as? JsonPrimitive)?.takeUnless { it === JsonNull }
    private fun string(value: JsonElement) = primitive(value)?.takeIf { it.isString }?.content
        ?.takeIf { it.length <= 16_384 && '\u0000' !in it }

    val fields: Map<String, Field> = Builder().apply {
        val appearance = SettingsShareSection.APPEARANCE
        booleans(appearance,
            "dynamic_color", "theme_role_overrides_enabled", "bottom_bar_floating",
            "navigation_icon_cross_scale_enabled", "hide_top_tabs", "header_blur_enabled",
            "bottom_bar_blur_enabled", "top_bar_liquid_glass_enabled", "home_search_liquid_glass_enabled",
            "bottom_bar_liquid_glass_enabled", "android_native_liquid_glass_enabled", "liquid_glass_enabled",
            "pinch_to_change_grid_columns_enabled", "home_hero_carousel_enabled", "home_hero_carousel_autoplay_enabled",
            "card_animation_enabled", "ui_entrance_animation_enabled", "card_transition_enabled",
            "miuix_transition_blur_enabled", "video_shared_return_gesture_follow_enabled",
            "video_shared_return_gesture_translation_enabled", "compact_video_stats_on_cover",
            "home_up_badges_visible", "home_refresh_tip_visible", "home_refresh_undo_visible",
            "home_up_avatars_visible", "home_publish_time_visible", "full_video_card_content_visible",
            "video_card_long_press_action_enabled", "home_video_duration_badges_visible", "show_profile_edit_button")
        names(appearance, "theme_selection_v1", AppUiStyle.entries.map { it.name }.toSet())
        // 3 is the original AMOLED theme-mode encoding, still consumed by the existing resolver.
        ints(appearance, "theme_mode_v2", AppThemeMode.entries.map { it.value }.toSet() + 3)
        ints(appearance, "dark_theme_style_v1", DarkThemeStyle.entries.map { it.value }.toSet())
        ints(appearance, "app_language_v1", AppLanguage.entries.map { it.value }.toSet())
        names(appearance, "md3_color_source", Md3ColorSource.entries.map { it.name }.toSet())
        color(appearance, "md3_custom_color_hex")
        for (mode in listOf("light", "dark")) for (role in listOf("background", "primary_text", "secondary_text", "control_accent")) {
            color(appearance, "theme_${mode}_$role")
        }
        names(appearance, "theme_color_style", PaletteStyle.entries.map { it.name }.toSet())
        names(appearance, "theme_color_spec", ColorSpec.SpecVersion.values().map { it.name }.toSet())
        intRange(appearance, "theme_color_index", ThemeColors.indices)
        names(appearance, "app_icon_style", AppIconStyle.entries.map { it.name }.toSet())
        names(appearance, "app_list_item_style", AppListItemStyle.entries.map { it.name }.toSet())
        names(appearance, "blur_intensity", BlurIntensity.entries.map { it.name }.toSet())
        intRange(appearance, "bottom_bar_label_mode", 0..2)
        intRange(appearance, "top_tab_label_mode", 0..2)
        ints(appearance, "home_top_right_action", HomeTopRightAction.entries.map { it.value }.toSet())
        ints(appearance, "bottom_bar_search_layout_mode", BottomBarSearchLayoutMode.entries.map { it.value }.toSet())
        ints(appearance, "liquid_glass_style", LiquidGlassStyle.entries.map { it.value }.toSet())
        ints(appearance, "liquid_glass_mode", LiquidGlassMode.entries.map { it.value }.toSet())
        ints(appearance, "liquid_glass_advanced_preset", LiquidGlassAdvancedPreset.entries.map { it.value }.toSet())
        floats(appearance, "liquid_glass_strength", ::normalizeLiquidGlassStrength)
        floats(appearance, "liquid_glass_material_progress_v2", ::normalizeLiquidGlassProgress)
        for (key in listOf("liquid_glass_progressive_blur_radius", "liquid_glass_progressive_blur_extent",
            "liquid_glass_progressive_blur_curve", "liquid_glass_content_readability",
            "liquid_glass_chromatic_aberration", "liquid_glass_content_distortion")) {
            floats(appearance, key) { it.coerceIn(0f, 1f) }
        }
        intRange(appearance, "display_mode", 0..1)
        intRange(appearance, "grid_column_count", 0..8)
        intRange(appearance, "grid_column_count_compact", 0..8)
        ints(appearance, "home_feed_card_width_preset", HomeFeedCardWidthPreset.entries.map { it.value }.toSet())
        ints(appearance, "home_feed_card_style", HomeFeedCardStyle.entries.map { it.value }.toSet())
        ints(appearance, "home_wallpaper_effect_mode", HomeWallpaperEffectMode.entries.map { it.value }.toSet())
        ints(appearance, "home_duration_style", HomeDurationStyle.entries.map { it.value }.toSet())
        val topIds = DesktopOriginalHomeSettingConstants.DEFAULT_TOP_TAB_ORDER.split(",").toSet()
        ids(appearance, "top_tab_order", topIds, emptyAllowed = false)
        ids(appearance, "top_tab_visible_tabs", topIds, emptyAllowed = true)

        val playback = SettingsShareSection.PLAYBACK
        booleans(playback, "auto_play", "hw_decode", "remember_last_playback_speed",
            "comment_fraud_detection_enabled", "comment_member_decorations_enabled", "detailed_comment_time_enabled",
            "image_preview_long_press_save_enabled", "image_preview_3d_page_enabled", "stop_playback_on_exit",
            "background_playback_enabled", "audio_now_playing_bar_opens_audio_mode", "video_ai_summary_entry_enabled",
            "video_note_enabled", "video_note_default_collapsed", "video_info_default_expanded", "video_argue_msg_shown",
            "click_to_play", "resume_playback_prompt_enabled", "space_played_video_locate_prompt_enabled",
            "auto_highest_quality", "sponsor_block_enabled", "sponsor_block_auto_skip", "progress_peak_danmaku_enabled",
            "show_online_count", "show_video_detail_comment_count")
        ints(playback, "playback_completion_behavior", PlaybackCompletionBehavior.entries.map { it.value }.toSet())
        floats(playback, "default_playback_speed", ::normalizePlaybackSpeed)
        ints(playback, "comment_default_sort_mode", setOf(2, 3))
        ints(playback, "audio_quality_preference", setOf(AUDIO_QUALITY_AUTO, AUDIO_QUALITY_HI_RES, AUDIO_QUALITY_DOLBY))
        ints(playback, "wifi_default_quality", setOf(6, 16, 32, 64, 74, 80, 112, 116, 120, 125, 126, 127))
        names(playback, "video_codec_preference", setOf("avc1", "hev1", "av01"))
        names(playback, "video_second_codec_preference", setOf("avc1", "hev1", "av01"))
        ints(playback, "data_saver_mode", DesktopOriginalPlaybackSettingsPreferences.DataSaverMode.entries.map { it.value }.toSet())
        ints(playback, "fullscreen_aspect_ratio", FullscreenAspectRatio.entries.map { it.value }.toSet())
        ints(playback, "bottom_progress_behavior", BottomProgressBehavior.entries.map { it.value }.toSet())
        ints(playback, "video_tag_size_preset", AppTagChipSize.entries.map { it.value }.toSet())
        ints(playback, "subtitle_auto_preference", SubtitleAutoPreference.entries.map { it.ordinal }.toSet())
        intRange(playback, "comment_collapsed_reply_preview_limit", 1..10)

        val gesture = SettingsShareSection.GESTURE
        // These work with Windows pointer/touch/keyboard playback; Android system controls are excluded.
        booleans(gesture, "global_text_tap_copy_enabled", "double_tap_seek_enabled", "long_press_speed_lock_enabled",
            "long_press_speed_hint_hidden", "subtitle_position_locked", "exp_double_tap_like",
            "show_fullscreen_screenshot_button", "show_fullscreen_time", "show_fullscreen_action_items")
        intRange(gesture, "seek_forward_seconds", 1..60)
        intRange(gesture, "seek_backward_seconds", 1..60)
        floats(gesture, "gesture_sensitivity") { it.coerceIn(0.5f, 2f) }
        floats(gesture, "subtitle_vertical_offset_fraction", ::normalizeSubtitleVerticalOffsetFraction)
        floats(gesture, "subtitle_portrait_vertical_offset_fraction", ::normalizeSubtitleVerticalOffsetFraction)

        val danmaku = SettingsShareSection.DANMAKU
        booleans(danmaku, "danmaku_enabled", "danmaku_scroll_fixed_velocity", "danmaku_static_to_scroll",
            "hot_danmaku_expanded_mode", "danmaku_massive_mode", "danmaku_allow_scroll", "danmaku_allow_top",
            "danmaku_allow_bottom", "danmaku_allow_colorful", "danmaku_allow_special", "danmaku_block_attention_commands",
            "danmaku_smart_occlusion", "danmaku_merge_duplicates")
        floats(danmaku, "danmaku_opacity", ::normalizeDanmakuOpacity)
        floats(danmaku, "danmaku_font_scale", ::normalizeDanmakuFontScale)
        floats(danmaku, "danmaku_speed") { it.coerceIn(0.5f, 3f) }
        floats(danmaku, "danmaku_area", ::normalizeDanmakuDisplayArea)
        intRange(danmaku, "danmaku_font_weight", 1..9)
        floats(danmaku, "danmaku_stroke_width") { it.coerceIn(0f, 5f) }
        floats(danmaku, "danmaku_line_height") { it.coerceIn(1f, 3f) }
        floats(danmaku, "danmaku_scroll_duration_seconds") { it.coerceIn(1f, 50f) }
        floats(danmaku, "danmaku_static_duration_seconds") { it.coerceIn(1f, 50f) }
        intRange(danmaku, "danmaku_duplicate_merge_window_ms", 100..3000)
        intRange(danmaku, "danmaku_duplicate_merge_count_threshold", 2..10)
        field("danmaku_block_rules", danmaku) { string(it)?.let(::JsonPrimitive) }

        val navigation = SettingsShareSection.NAVIGATION
        booleans(navigation, "header_collapse_enabled", "show_pgc_timeline", "tablet_use_sidebar", "sidebar_expanded",
            "sidebar_account_switcher_enabled", "incremental_timeline_refresh", "dynamic_image_preview_text_visible",
            "dynamic_all_tab_horizontal_user_list_visible", "dynamic_top_bar_collapse_on_scroll")
        ints(navigation, "home_top_layout_order", HomeTopLayoutOrder.entries.map { it.value }.toSet())
        ints(navigation, "home_header_collapse_mode", HomeHeaderCollapseMode.entries.map { it.value }.toSet())
        ints(navigation, "common_list_header_collapse_mode", CommonListHeaderCollapseMode.entries.map { it.value }.toSet())
        ints(navigation, "bottom_bar_visibility_mode", DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.entries.map { it.value }.toSet())
        val bottomIds = DesktopOriginalHomeSettingConstants.DEFAULT_BOTTOM_BAR_ORDER.split(",").toSet() +
            setOf("STORY", "FAVORITE", "LIVE", "WATCHLATER", "SETTINGS", "PLUGINS")
        ids(navigation, "bottom_bar_order", bottomIds, emptyAllowed = false)
        ids(navigation, "bottom_bar_visible_tabs", bottomIds, emptyAllowed = false)
    }.fields.toMap()

    val definitions: List<SettingsShareEntryDefinition> = fields.values.map { it.definition }
    val baselineDanmakuKeys: Set<String> = definitions.filter { it.section == SettingsShareSection.DANMAKU }
        .map { it.storageKey }.toSet() - setOf("hot_danmaku_expanded_mode", "danmaku_block_attention_commands")

    fun normalize(values: Map<String, JsonElement>): Map<String, JsonElement> = buildMap {
        values.forEach { (key, value) -> fields[key]?.normalize?.invoke(value)?.let { put(key, it) } }
    }
}
