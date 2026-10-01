package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as Preferences
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.android.purebilibili.feature.video.subtitle.normalizeSubtitleVerticalOffsetFraction
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal val longPressSpeedPreferenceKey = floatPreferencesKey("long_press_speed")

object DesktopOriginalPlayerSectionSettings {
    private val KEY_GESTURE_SENSITIVITY = floatPreferencesKey("gesture_sensitivity")

    private val KEY_SLIDE_VOLUME_BRIGHTNESS_ENABLED = booleanPreferencesKey("slide_volume_brightness_enabled")

    private val KEY_SET_SYSTEM_BRIGHTNESS = booleanPreferencesKey("set_system_brightness")

    private val KEY_PIP_NO_DANMAKU = booleanPreferencesKey("pip_no_danmaku")

    private val KEY_DOUBLE_TAP_SEEK_ENABLED = booleanPreferencesKey("double_tap_seek_enabled")

    private val KEY_SEEK_FORWARD_SECONDS = intPreferencesKey("seek_forward_seconds")

    private val KEY_SEEK_BACKWARD_SECONDS = intPreferencesKey("seek_backward_seconds")

    private val KEY_LONG_PRESS_SPEED_HINT_CLOSE_ENABLED =
        booleanPreferencesKey("long_press_speed_hint_close_enabled")

    private val KEY_LONG_PRESS_SPEED_HINT_HIDDEN =
        booleanPreferencesKey("long_press_speed_hint_hidden")

    private val KEY_LONG_PRESS_SPEED_LOCK_ENABLED =
        booleanPreferencesKey("long_press_speed_lock_enabled")

    private val KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN =
        booleanPreferencesKey("long_press_speed_lock_hint_shown")

    private val KEY_LONG_PRESS_SPEED_HINT_SCALE =
        floatPreferencesKey("long_press_speed_hint_scale")

    private val KEY_LONG_PRESS_SPEED_HINT_ALPHA =
        floatPreferencesKey("long_press_speed_hint_alpha")

    private val KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED =
        booleanPreferencesKey("two_finger_vertical_speed_enabled")

    private val KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED =
        booleanPreferencesKey("two_finger_horizontal_speed_enabled")

    private val KEY_HI_RES_LONG_PRESS_COMPAT_HINT_SHOWN =
        booleanPreferencesKey("hi_res_long_press_compat_hint_shown")

    private val KEY_SUBTITLE_VERTICAL_OFFSET_FRACTION =
        floatPreferencesKey("subtitle_vertical_offset_fraction")

    private val KEY_SUBTITLE_PORTRAIT_VERTICAL_OFFSET_FRACTION =
        floatPreferencesKey("subtitle_portrait_vertical_offset_fraction")

    private val KEY_SUBTITLE_POSITION_LOCKED =
        booleanPreferencesKey("subtitle_position_locked")
    //  [新增] 默认播放速度/记忆上次播放速度

    private val KEY_LIVE_SURFACE_CARD_TRANSITION_ENABLED =
        booleanPreferencesKey("live_surface_card_transition_enabled")
    // 直播间 SC 醒目留言浮层：默认开启；关闭弹幕时一律隐藏，此处提供独立开关

    private val KEY_HOME_UP_BADGES_VISIBLE = booleanPreferencesKey("home_up_badges_visible")

    private val KEY_VIDEO_NOTE_DEFAULT_COLLAPSED = booleanPreferencesKey("video_note_default_collapsed")

    internal fun mapPlayerInteractionSettingsFromPreferences(
        preferences: Preferences
    ): PlayerInteractionSettings {
        return PlayerInteractionSettings(
            gestureSensitivity = (preferences[KEY_GESTURE_SENSITIVITY] ?: 1.0f).coerceIn(0.5f, 2.0f),
            doubleTapLikeEnabled = preferences[KEY_DOUBLE_TAP_LIKE] ?: true,
            doubleTapSeekEnabled = preferences[KEY_DOUBLE_TAP_SEEK_ENABLED] ?: false,
            portraitSwipeToFullscreenEnabled = preferences[KEY_PORTRAIT_SWIPE_TO_FULLSCREEN] ?: true,
            centerSwipeToFullscreenEnabled = preferences[KEY_CENTER_SWIPE_TO_FULLSCREEN] ?: true,
            slideVolumeBrightnessEnabled = preferences[KEY_SLIDE_VOLUME_BRIGHTNESS_ENABLED] ?: true,
            setSystemBrightnessEnabled = preferences[KEY_SET_SYSTEM_BRIGHTNESS] ?: false,
            pipNoDanmakuEnabled = preferences[KEY_PIP_NO_DANMAKU] ?: false,
            seekForwardSeconds = (preferences[KEY_SEEK_FORWARD_SECONDS] ?: 10).coerceIn(1, 60),
            seekBackwardSeconds = (preferences[KEY_SEEK_BACKWARD_SECONDS] ?: 10).coerceIn(1, 60),
            inlineSwipeSeekSeconds = normalizeInlineSwipeSeekSeconds(
                preferences[KEY_INLINE_SWIPE_SEEK_SECONDS] ?: 30
            ),
            fullscreenSwipeSeekSeconds = normalizeFullscreenSwipeSeekSeconds(
                preferences[KEY_FULLSCREEN_SWIPE_SEEK_SECONDS] ?: 15
            ),
            fullscreenSwipeSeekEnabled = preferences[KEY_FULLSCREEN_SWIPE_SEEK_ENABLED] ?: true,
            fullscreenGestureReverse = preferences[KEY_FULLSCREEN_GESTURE_REVERSE] ?: false,
            hideVideoPageStatusBar = preferences[KEY_HIDE_VIDEO_PAGE_STATUS_BAR] ?: false,
            portraitLetterboxAmbientHaze =
                preferences[KEY_PORTRAIT_LETTERBOX_AMBIENT_HAZE] ?: true,
            tabletCommentPanelWidthPreset = TabletCommentPanelWidthPreset.fromValue(
                preferences[KEY_TABLET_COMMENT_PANEL_WIDTH_PRESET]
                    ?: TabletCommentPanelWidthPreset.STANDARD.value
            ),
            autoEnterFullscreenEnabled = preferences[KEY_AUTO_ENTER_FULLSCREEN] ?: false,
            autoExitFullscreenEnabled = preferences[KEY_AUTO_EXIT_FULLSCREEN] ?: true,
            autoExitFullscreenMode = resolveAutoExitFullscreenMode(
                modeValue = preferences[KEY_AUTO_EXIT_FULLSCREEN_MODE],
                legacyEnabled = preferences[KEY_AUTO_EXIT_FULLSCREEN],
            ),
            fixedFullscreenAspectRatio = FullscreenAspectRatio.fromValue(
                preferences[KEY_FULLSCREEN_ASPECT_RATIO] ?: FullscreenAspectRatio.FIT.value
            ),
            subtitleAutoPreference = SubtitleAutoPreference.entries.getOrElse(
                preferences[KEY_SUBTITLE_AUTO_PREFERENCE] ?: SubtitleAutoPreference.OFF.ordinal
            ) { SubtitleAutoPreference.OFF },
            longPressSpeed = normalizeLongPressSpeed(
                preferences[longPressSpeedPreferenceKey] ?: DEFAULT_LONG_PRESS_SPEED
            ),
            longPressSpeedLockEnabled = preferences[KEY_LONG_PRESS_SPEED_LOCK_ENABLED] ?: false,
            longPressSpeedLockHintShown = preferences[KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN] ?: false,
            longPressSpeedHintCloseEnabled =
                preferences[KEY_LONG_PRESS_SPEED_HINT_CLOSE_ENABLED] ?: false,
            longPressSpeedHintHidden = preferences[KEY_LONG_PRESS_SPEED_HINT_HIDDEN] ?: false,
            longPressSpeedHintScale = normalizeLongPressSpeedHintScale(
                preferences[KEY_LONG_PRESS_SPEED_HINT_SCALE] ?: LONG_PRESS_SPEED_HINT_DEFAULT_SCALE
            ),
            longPressSpeedHintAlpha = normalizeLongPressSpeedHintAlpha(
                preferences[KEY_LONG_PRESS_SPEED_HINT_ALPHA] ?: LONG_PRESS_SPEED_HINT_DEFAULT_ALPHA
            ),
            subtitleVerticalOffsetFraction = normalizeSubtitleVerticalOffsetFraction(
                preferences[KEY_SUBTITLE_VERTICAL_OFFSET_FRACTION] ?: 0.0f
            ),
            subtitlePortraitVerticalOffsetFraction = normalizeSubtitleVerticalOffsetFraction(
                preferences[KEY_SUBTITLE_PORTRAIT_VERTICAL_OFFSET_FRACTION] ?: 0.0f
            ),
            subtitlePositionLocked = preferences[KEY_SUBTITLE_POSITION_LOCKED] ?: true,
            twoFingerVerticalSpeedEnabled = preferences[KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED] ?: false,
            twoFingerHorizontalSpeedEnabled = preferences[KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED] ?: false,
            hiResLongPressCompatHintShown = preferences[KEY_HI_RES_LONG_PRESS_COMPAT_HINT_SHOWN] ?: false,
            directPortraitStoryEntry = preferences[KEY_AUTO_PORTRAIT_FULLSCREEN] ?: false,
            launchToPortraitFeedOnStartup = preferences[KEY_LAUNCH_TO_PORTRAIT_FEED_ON_STARTUP] ?: false
        )
    }


    fun getPlayerInteractionSettings(context: Context): Flow<PlayerInteractionSettings> =
        context.settingsDataStore.data
            .map(::mapPlayerInteractionSettingsFromPreferences)
            .distinctUntilChanged()


    private val KEY_CLICK_TO_PLAY = booleanPreferencesKey("click_to_play")

    private const val HI_RES_LONG_PRESS_HINT_CACHE_PREFS = "hi_res_long_press_hint_cache"

    private const val CACHE_KEY_HI_RES_LONG_PRESS_HINT_SHOWN = "hi_res_long_press_hint_shown"

    private const val LONG_PRESS_SPEED_LOCK_CACHE_PREFS = "long_press_speed_lock_cache"

    private const val CACHE_KEY_LONG_PRESS_SPEED_LOCK_ENABLED = "long_press_speed_lock_enabled"

    private const val CACHE_KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN = "long_press_speed_lock_hint_shown"

    private const val CACHE_KEY_LONG_PRESS_SPEED_HINT_CLOSE_ENABLED =
        "long_press_speed_hint_close_enabled"

    private const val CACHE_KEY_LONG_PRESS_SPEED_HINT_HIDDEN = "long_press_speed_hint_hidden"

    private const val CACHE_KEY_LONG_PRESS_SPEED_HINT_SCALE = "long_press_speed_hint_scale"

    private const val CACHE_KEY_LONG_PRESS_SPEED_HINT_ALPHA = "long_press_speed_hint_alpha"

    fun getClickToPlay(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_CLICK_TO_PLAY] ?: true }


    fun getClickToPlaySync(context: Context): Boolean {
        return context.getSharedPreferences("auto_play_cache", Context.MODE_PRIVATE)
            .getBoolean("click_to_play_enabled", true)
    }


    fun getLongPressSpeedLockEnabledSync(context: Context): Boolean {
        return context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_LONG_PRESS_SPEED_LOCK_ENABLED, false)
    }


    fun getLongPressSpeedLockHintShownSync(context: Context): Boolean {
        return context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN, false)
    }

    /** 隐藏开关：是否在长按倍速浮层显示 ×。默认 false。不进入设置 UI。 */

    fun getLongPressSpeedHintCloseEnabledSync(context: Context): Boolean {
        return context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_LONG_PRESS_SPEED_HINT_CLOSE_ENABLED, false)
    }


    fun getLongPressSpeedHintHiddenSync(context: Context): Boolean =
        context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_LONG_PRESS_SPEED_HINT_HIDDEN, false)


    fun getLongPressSpeedHintScaleSync(context: Context): Float =
        context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .getFloat(CACHE_KEY_LONG_PRESS_SPEED_HINT_SCALE, LONG_PRESS_SPEED_HINT_DEFAULT_SCALE)
            .let(::normalizeLongPressSpeedHintScale)


    fun getLongPressSpeedHintAlphaSync(context: Context): Float =
        context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .getFloat(CACHE_KEY_LONG_PRESS_SPEED_HINT_ALPHA, LONG_PRESS_SPEED_HINT_DEFAULT_ALPHA)
            .let(::normalizeLongPressSpeedHintAlpha)


    suspend fun setSubtitleVerticalOffsetFraction(context: Context, value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_SUBTITLE_VERTICAL_OFFSET_FRACTION] =
                normalizeSubtitleVerticalOffsetFraction(value)
        }
    }


    suspend fun setSubtitlePositionLocked(context: Context, locked: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_SUBTITLE_POSITION_LOCKED] = locked
        }
    }


    suspend fun setHiResLongPressCompatHintShown(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_HI_RES_LONG_PRESS_COMPAT_HINT_SHOWN] = value
        }
        context.getSharedPreferences(HI_RES_LONG_PRESS_HINT_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CACHE_KEY_HI_RES_LONG_PRESS_HINT_SHOWN, value)
            .apply()
    }


    fun getHiResLongPressCompatHintShownSync(context: Context): Boolean {
        return context.getSharedPreferences(HI_RES_LONG_PRESS_HINT_CACHE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_HI_RES_LONG_PRESS_HINT_SHOWN, false)
    }

    //  播放器菜单、双指调速与默认速度共用倍速列表；长按速度独立设置

    fun getPlayerInsightMode(context: Context): Flow<PlayerSettingsStore.PlayerInsightMode> =
        PlayerSettingsStore.getPlayerInsightMode(context)


    fun getPlayerInsightModeSync(context: Context): PlayerSettingsStore.PlayerInsightMode =
        PlayerSettingsStore.getPlayerInsightModeSync(context)

    //  [新增] --- 主题色索引 (默认 0 = 经典蓝) ---

    fun getLiveSurfaceCardTransitionEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_LIVE_SURFACE_CARD_TRANSITION_ENABLED] ?: false }


    fun getHomeUpBadgesVisible(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_HOME_UP_BADGES_VISIBLE] ?: false }


    private val KEY_DOUBLE_TAP_LIKE = booleanPreferencesKey("exp_double_tap_like")
    
    // --- 已登录用户默认 1080P ---

    private val KEY_AUTO_PORTRAIT_FULLSCREEN = booleanPreferencesKey("auto_portrait_fullscreen")

    private val KEY_LAUNCH_TO_PORTRAIT_FEED_ON_STARTUP = booleanPreferencesKey("launch_to_portrait_feed_on_startup")

    fun getAutoPortraitFullscreen(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_AUTO_PORTRAIT_FULLSCREEN] ?: false }


    private val KEY_AUTO_ROTATE_ENABLED = booleanPreferencesKey("auto_rotate_enabled")
    
    // --- 自动横竖屏切换 (跟随手机传感器方向，默认关闭) ---

    fun getAutoRotateEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_AUTO_ROTATE_ENABLED] ?: false }
    

    enum class MiniPlayerMode(val value: Int, val label: String, val description: String) {
        OFF(0, "默认", "切到桌面后台播放，返回主页停止"),
        IN_APP_ONLY(1, "应用内小窗", "返回主页时显示悬浮小窗"),
        SYSTEM_PIP(2, "画中画", "切到桌面进入系统画中画"),
        IN_APP_AND_SYSTEM_PIP(3, "小窗+画中画", "返回主页显示小窗，切到桌面进入画中画");

        val supportsInAppMiniPlayer: Boolean
            get() = this == IN_APP_ONLY || this == IN_APP_AND_SYSTEM_PIP

        val supportsSystemPip: Boolean
            get() = this == SYSTEM_PIP || this == IN_APP_AND_SYSTEM_PIP
        
        companion object {
            fun fromValue(value: Int): MiniPlayerMode = when(value) {
                1 -> IN_APP_ONLY
                2 -> SYSTEM_PIP
                3 -> IN_APP_AND_SYSTEM_PIP
                else -> OFF
            }
        }
    }
    
    // --- 小窗模式设置 ---

    fun getMiniPlayerModeSync(context: Context): MiniPlayerMode {
        val value = context.getSharedPreferences("mini_player", Context.MODE_PRIVATE)
            .getInt("mode", MiniPlayerMode.OFF.value)
        return MiniPlayerMode.fromValue(value)
    }

    /**
     * 离开播放页后停止播放（优先级高于后台播放模式）
     * - true: 离开播放页立即停止，不进入小窗/画中画/后台播放
     * - false: 按后台播放模式执行（默认）
     */

    fun getStopPlaybackOnExitSync(context: Context): Boolean {
        return context.getSharedPreferences("mini_player", Context.MODE_PRIVATE)
            .getBoolean("stop_playback_on_exit", false)
    }


    fun getVideoNoteDefaultCollapsed(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_VIDEO_NOTE_DEFAULT_COLLAPSED] ?: true }


    private val KEY_SWIPE_HIDE_PLAYER = booleanPreferencesKey("swipe_hide_player")

    private val KEY_PORTRAIT_PLAYER_COLLAPSE_MODE = intPreferencesKey("portrait_player_collapse_mode")
    /** Auto-pause when the detail player is swipe-collapsed; default on. */

    private val KEY_PAUSE_ON_PLAYER_COLLAPSE = booleanPreferencesKey("pause_on_player_collapse")

    private val KEY_PORTRAIT_SWIPE_TO_FULLSCREEN = booleanPreferencesKey("portrait_swipe_to_fullscreen")

    private val KEY_CENTER_SWIPE_TO_FULLSCREEN = booleanPreferencesKey("center_swipe_to_fullscreen")

    private val KEY_INLINE_SWIPE_SEEK_SECONDS = intPreferencesKey("inline_swipe_seek_seconds")

    private val KEY_FULLSCREEN_SWIPE_SEEK_ENABLED = booleanPreferencesKey("fullscreen_swipe_seek_enabled")

    private val KEY_FULLSCREEN_SWIPE_SEEK_SECONDS = intPreferencesKey("fullscreen_swipe_seek_seconds")

    private val KEY_FULLSCREEN_GESTURE_REVERSE = booleanPreferencesKey("fullscreen_gesture_reverse")

    private val KEY_HIDE_VIDEO_PAGE_STATUS_BAR = booleanPreferencesKey("hide_video_page_status_bar")

    private val KEY_PORTRAIT_LETTERBOX_AMBIENT_HAZE =
        booleanPreferencesKey("portrait_letterbox_ambient_haze")

    private val KEY_TABLET_COMMENT_PANEL_WIDTH_PRESET =
        intPreferencesKey("tablet_comment_panel_width_preset")

    private val KEY_AUTO_ENTER_FULLSCREEN = booleanPreferencesKey("auto_enter_fullscreen")

    private val KEY_AUTO_EXIT_FULLSCREEN = booleanPreferencesKey("auto_exit_fullscreen")

    private val KEY_AUTO_EXIT_FULLSCREEN_MODE = intPreferencesKey("auto_exit_fullscreen_mode")

    private val KEY_PLAYER_DIAGNOSTIC_LOGGING_ENABLED =
        booleanPreferencesKey("player_diagnostic_logging_enabled")

    private val KEY_SUBTITLE_AUTO_PREFERENCE = intPreferencesKey("subtitle_auto_preference")

    private val KEY_HORIZONTAL_ADAPTATION = booleanPreferencesKey("horizontal_adaptation_enabled")

    private val KEY_FULLSCREEN_MODE = intPreferencesKey("fullscreen_mode")

    private val KEY_FULLSCREEN_ASPECT_RATIO = intPreferencesKey("fullscreen_aspect_ratio")

    private val INLINE_SWIPE_SEEK_OPTIONS = listOf(5, 10, 15, 30, 60)

    private val FULLSCREEN_SWIPE_SEEK_OPTIONS = listOf(10, 15, 20, 30)

    internal fun resolvePortraitPlayerCollapseModePreference(
        rawMode: Int?,
        legacySwipeHide: Boolean?
    ): PortraitPlayerCollapseMode {
        return rawMode?.let(PortraitPlayerCollapseMode::fromValue)
            ?: legacySwipeHide?.let(PortraitPlayerCollapseMode::fromLegacySwipeHide)
            ?: PortraitPlayerCollapseMode.INTRO_ONLY
    }


    fun getPortraitPlayerCollapseMode(context: Context): Flow<PortraitPlayerCollapseMode> =
        context.settingsDataStore.data.map { preferences ->
            resolvePortraitPlayerCollapseModePreference(
                rawMode = preferences[KEY_PORTRAIT_PLAYER_COLLAPSE_MODE],
                legacySwipeHide = preferences[KEY_SWIPE_HIDE_PLAYER]
            )
        }


    fun getPauseOnPlayerCollapseEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_PAUSE_ON_PLAYER_COLLAPSE] ?: true
        }


    private fun normalizeInlineSwipeSeekSeconds(seconds: Int): Int {
        return INLINE_SWIPE_SEEK_OPTIONS.minByOrNull { option -> abs(option - seconds) } ?: 30
    }

    // --- 横屏左右滑动精细调进度开关（默认开启） ---

    private fun normalizeFullscreenSwipeSeekSeconds(seconds: Int): Int {
        return FULLSCREEN_SWIPE_SEEK_OPTIONS.minByOrNull { option -> abs(option - seconds) } ?: 15
    }


    private fun isLargeScreenOrFoldableConfiguration(context: Context): Boolean {
        return context.isLargeScreenOrFoldableConfiguration()
    }


    fun getTabletCommentPanelWidthPreset(context: Context): Flow<TabletCommentPanelWidthPreset> =
        context.settingsDataStore.data
            .map { preferences ->
                TabletCommentPanelWidthPreset.fromValue(
                    preferences[KEY_TABLET_COMMENT_PANEL_WIDTH_PRESET]
                        ?: TabletCommentPanelWidthPreset.STANDARD.value
                )
            }


    fun getPlayerDiagnosticLoggingEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[KEY_PLAYER_DIAGNOSTIC_LOGGING_ENABLED]
                ?: DEFAULT_PLAYER_DIAGNOSTIC_LOGGING_ENABLED
        }


    fun getSubtitleAutoPreference(context: Context): Flow<SubtitleAutoPreference> =
        context.settingsDataStore.data.map { preferences ->
            val raw = preferences[KEY_SUBTITLE_AUTO_PREFERENCE] ?: SubtitleAutoPreference.OFF.ordinal
            SubtitleAutoPreference.entries.getOrElse(raw) { SubtitleAutoPreference.OFF }
        }


    fun getHorizontalAdaptationEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[KEY_HORIZONTAL_ADAPTATION] ?: isLargeScreenOrFoldableConfiguration(context)
        }


    fun getFullscreenMode(context: Context): Flow<FullscreenMode> = context.settingsDataStore.data
        .map { preferences ->
            FullscreenMode.fromValue(preferences[KEY_FULLSCREEN_MODE] ?: FullscreenMode.AUTO.value)
        }


    suspend fun setFullscreenAspectRatio(context: Context, ratio: FullscreenAspectRatio) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_FULLSCREEN_ASPECT_RATIO] = ratio.value
        }
    }
    

    
    /**
     *  圆角大小比例 (0.5 ~ 1.5, 默认 1.0)
     * 控制全局 UI 圆角大小
     */

    
    // ========== 📱 平板导航模式 ==========
    

}
