package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore
import com.android.purebilibili.feature.video.ui.gesture.TwoFingerSpeedToggleState
import com.android.purebilibili.feature.video.ui.gesture.applyHorizontalTwoFingerSpeedToggle
import com.android.purebilibili.feature.video.ui.gesture.applyVerticalTwoFingerSpeedToggle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged
object DesktopOriginalVideoControlSettings {

    private val KEY_PLAYBACK_COMPLETION_BEHAVIOR = intPreferencesKey("playback_completion_behavior")

    private val KEY_SHOW_PLAYER_CAST_BUTTON = booleanPreferencesKey("show_player_cast_button")

    private val KEY_SHOW_VIDEO_FOLLOW_BUTTON = booleanPreferencesKey("show_video_follow_button")

    private val KEY_COMPACT_PLAYER_CHROME = booleanPreferencesKey("compact_player_chrome")

    private val KEY_PLAYER_PROGRESS_PLACEMENT = intPreferencesKey("player_progress_placement")

    private val KEY_DOUBLE_TAP_SEEK_ENABLED = booleanPreferencesKey("double_tap_seek_enabled")

    private val KEY_SEEK_FORWARD_SECONDS = intPreferencesKey("seek_forward_seconds")

    private val KEY_SEEK_BACKWARD_SECONDS = intPreferencesKey("seek_backward_seconds")

    private val KEY_LONG_PRESS_SPEED_LOCK_ENABLED =
        booleanPreferencesKey("long_press_speed_lock_enabled")

    private val KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN =
        booleanPreferencesKey("long_press_speed_lock_hint_shown")

    private val KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED =
        booleanPreferencesKey("two_finger_vertical_speed_enabled")

    private val KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED =
        booleanPreferencesKey("two_finger_horizontal_speed_enabled")

    private val KEY_CARD_ANIMATION_ENABLED = booleanPreferencesKey("card_animation_enabled")
    //  [新增] 卡片过渡动画开关

    fun getPlayerControlVisibilitySettings(
        context: Context
    ): Flow<PlayerControlVisibilitySettings> = context.settingsDataStore.data
        .map { preferences ->
            PlayerControlVisibilitySettings(
                showCastButton = preferences[KEY_SHOW_PLAYER_CAST_BUTTON] ?: true,
                showFollowButton = preferences[KEY_SHOW_VIDEO_FOLLOW_BUTTON] ?: true,
                compactPlayerChrome = preferences[KEY_COMPACT_PLAYER_CHROME] ?: false
            )
        }
        .distinctUntilChanged()


    fun getPlayerProgressPlacement(context: Context): Flow<PlayerProgressPlacement> =
        context.settingsDataStore.data.map { preferences ->
            PlayerProgressPlacement.fromValue(
                preferences[KEY_PLAYER_PROGRESS_PLACEMENT]
                    ?: PlayerProgressPlacement.ABOVE_CONTROLS.value
            )
        }.distinctUntilChanged()


    private const val LONG_PRESS_SPEED_LOCK_CACHE_PREFS = "long_press_speed_lock_cache"

    private const val CACHE_KEY_LONG_PRESS_SPEED_LOCK_ENABLED = "long_press_speed_lock_enabled"

    private const val CACHE_KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN = "long_press_speed_lock_hint_shown"

    private const val VIDEO_PAGE_STATUS_BAR_CACHE_PREFS = "video_page_status_bar_cache"

    private const val CACHE_KEY_HIDE_VIDEO_PAGE_STATUS_BAR = "hide_video_page_status_bar"


    private const val CACHE_KEY_PLAYBACK_COMPLETION_BEHAVIOR = "playback_completion_behavior"


    @Volatile
    private var playbackCompletionBehaviorMemoryCache: Int? = null


    fun getPlaybackCompletionBehavior(context: Context): Flow<PlaybackCompletionBehavior> =
        context.settingsDataStore.data
            .map { preferences ->
                val value = preferences[KEY_PLAYBACK_COMPLETION_BEHAVIOR]
                    ?: PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC.value
                PlaybackCompletionBehavior.fromValue(value)
            }
            .onEach { behavior ->
                // Flow（UI）与 Sync（播完回调）对齐：缓存 + 回写 SP，修复「界面顺序播放、实际单循」.
                rememberPlaybackCompletionBehavior(behavior)
                healPlaybackCompletionSharedPreferences(context, behavior)
            }
            .distinctUntilChanged()


    suspend fun setPlaybackCompletionBehavior(
        context: Context,
        behavior: PlaybackCompletionBehavior
    ) {
        rememberPlaybackCompletionBehavior(behavior)
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_PLAYBACK_COMPLETION_BEHAVIOR] = behavior.value
        }
        healPlaybackCompletionSharedPreferences(context, behavior)
    }


    private fun rememberPlaybackCompletionBehavior(behavior: PlaybackCompletionBehavior) {
        playbackCompletionBehaviorMemoryCache = behavior.value
    }


    private fun healPlaybackCompletionSharedPreferences(
        context: Context,
        behavior: PlaybackCompletionBehavior,
    ) {
        val prefs = context.getSharedPreferences("auto_play_cache", Context.MODE_PRIVATE)
        val current = if (prefs.contains(CACHE_KEY_PLAYBACK_COMPLETION_BEHAVIOR)) {
            prefs.getInt(
                CACHE_KEY_PLAYBACK_COMPLETION_BEHAVIOR,
                PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC.value
            )
        } else {
            null
        }
        if (!shouldHealPlaybackCompletionSharedPreferences(behavior.value, current)) {
            return
        }
        prefs.edit()
            .putInt(CACHE_KEY_PLAYBACK_COMPLETION_BEHAVIOR, behavior.value)
            .apply()
    }

    // --- HW Decode ---

    fun getDoubleTapSeekEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_DOUBLE_TAP_SEEK_ENABLED] ?: false } // 新用户默认关闭，已保存用户不受影响


    suspend fun setDoubleTapSeekEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_DOUBLE_TAP_SEEK_ENABLED] = value }
    }


    fun getSeekForwardSeconds(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SEEK_FORWARD_SECONDS] ?: 10 }

    /** 直播清晰度记忆（对齐 PiliPlus liveQuality）：0 表示未选择，走默认策略。 */

    suspend fun setSeekForwardSeconds(context: Context, seconds: Int) {
        context.settingsDataStore.edit { preferences -> 
            preferences[KEY_SEEK_FORWARD_SECONDS] = seconds.coerceIn(1, 60)
        }
    }


    fun getSeekBackwardSeconds(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SEEK_BACKWARD_SECONDS] ?: 10 }


    suspend fun setSeekBackwardSeconds(context: Context, seconds: Int) {
        context.settingsDataStore.edit { preferences -> 
            preferences[KEY_SEEK_BACKWARD_SECONDS] = seconds.coerceIn(1, 60)
        }
    }


    fun getLongPressSpeed(context: Context): Flow<Float> =
        PlayerSettingsStore.getLongPressSpeed(context)


    suspend fun setLongPressSpeed(context: Context, speed: Float) {
        PlayerSettingsStore.setLongPressSpeed(context, speed)
    }


    fun getLongPressSpeedLockEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_LONG_PRESS_SPEED_LOCK_ENABLED] ?: false }


    suspend fun setLongPressSpeedLockEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LONG_PRESS_SPEED_LOCK_ENABLED] = enabled
        }
        context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CACHE_KEY_LONG_PRESS_SPEED_LOCK_ENABLED, enabled)
            .apply()
    }


    suspend fun setLongPressSpeedLockHintShown(context: Context, shown: Boolean) {
        context.getSharedPreferences(LONG_PRESS_SPEED_LOCK_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CACHE_KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN, shown)
            .apply()
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN] = shown
        }
    }


    fun getTwoFingerVerticalSpeedEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED] ?: false }


    suspend fun setTwoFingerVerticalSpeedEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val current = TwoFingerSpeedToggleState(
                verticalEnabled = preferences[KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED] ?: false,
                horizontalEnabled = preferences[KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED] ?: false
            )
            val updated = applyVerticalTwoFingerSpeedToggle(current, enabled)
            preferences[KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED] = updated.verticalEnabled
            preferences[KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED] = updated.horizontalEnabled
        }
    }


    fun getTwoFingerHorizontalSpeedEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED] ?: false }


    suspend fun setTwoFingerHorizontalSpeedEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val current = TwoFingerSpeedToggleState(
                verticalEnabled = preferences[KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED] ?: false,
                horizontalEnabled = preferences[KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED] ?: false
            )
            val updated = applyHorizontalTwoFingerSpeedToggle(current, enabled)
            preferences[KEY_TWO_FINGER_VERTICAL_SPEED_ENABLED] = updated.verticalEnabled
            preferences[KEY_TWO_FINGER_HORIZONTAL_SPEED_ENABLED] = updated.horizontalEnabled
        }
    }


    fun getPlaybackSpeedOptions(context: Context): Flow<List<Float>> =
        PlayerSettingsStore.getPlaybackSpeedOptions(context)


    fun getDefaultPlaybackSpeed(context: Context): Flow<Float> =
        PlayerSettingsStore.getDefaultPlaybackSpeed(context)


    suspend fun setDefaultPlaybackSpeed(context: Context, speed: Float) {
        PlayerSettingsStore.setDefaultPlaybackSpeed(context, speed)
    }


    fun getRememberLastPlaybackSpeed(context: Context): Flow<Boolean> =
        PlayerSettingsStore.getRememberLastPlaybackSpeed(context)


    suspend fun setRememberLastPlaybackSpeed(context: Context, enabled: Boolean) {
        PlayerSettingsStore.setRememberLastPlaybackSpeed(context, enabled)
    }


    suspend fun setLastPlaybackSpeed(context: Context, speed: Float) {
        PlayerSettingsStore.setLastPlaybackSpeed(context, speed)
    }


    fun getCardAnimationEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_CARD_ANIMATION_ENABLED] ?: false }  // 默认关闭


    private val KEY_HIDE_VIDEO_PAGE_STATUS_BAR = booleanPreferencesKey("hide_video_page_status_bar")

    private val KEY_SHOW_FULLSCREEN_LOCK_BUTTON = booleanPreferencesKey("show_fullscreen_lock_button")

    private val KEY_SHOW_FULLSCREEN_SCREENSHOT_BUTTON = booleanPreferencesKey("show_fullscreen_screenshot_button")

    private val KEY_SHOW_FULLSCREEN_BATTERY_LEVEL = booleanPreferencesKey("show_fullscreen_battery_level")

    private val KEY_SHOW_FULLSCREEN_TIME = booleanPreferencesKey("show_fullscreen_time")

    private val KEY_SHOW_FULLSCREEN_ACTION_ITEMS = booleanPreferencesKey("show_fullscreen_action_items")

    private val KEY_SHOW_ONLINE_COUNT = booleanPreferencesKey("show_online_count")

    private val KEY_BOTTOM_PROGRESS_BEHAVIOR = intPreferencesKey("bottom_progress_behavior")

    private val KEY_PROGRESS_PEAK_DANMAKU_ENABLED =
        booleanPreferencesKey("progress_peak_danmaku_enabled")

    fun getHideVideoPageStatusBar(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_HIDE_VIDEO_PAGE_STATUS_BAR] ?: false }
        .onEach { enabledFromDataStore ->
            context.getSharedPreferences(VIDEO_PAGE_STATUS_BAR_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(CACHE_KEY_HIDE_VIDEO_PAGE_STATUS_BAR, enabledFromDataStore)
                .apply()
        }


    fun getHideVideoPageStatusBarSync(context: Context): Boolean {
        return context.getSharedPreferences(VIDEO_PAGE_STATUS_BAR_CACHE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_HIDE_VIDEO_PAGE_STATUS_BAR, false)
    }


    fun getShowFullscreenLockButton(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SHOW_FULLSCREEN_LOCK_BUTTON] ?: true }


    fun getShowFullscreenScreenshotButton(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SHOW_FULLSCREEN_SCREENSHOT_BUTTON] ?: true }


    fun getShowFullscreenBatteryLevel(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SHOW_FULLSCREEN_BATTERY_LEVEL] ?: true }


    fun getShowFullscreenTime(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SHOW_FULLSCREEN_TIME] ?: true }


    fun getShowFullscreenActionItems(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SHOW_FULLSCREEN_ACTION_ITEMS] ?: true }


    fun getShowOnlineCount(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SHOW_ONLINE_COUNT] ?: false }


    fun getBottomProgressBehavior(context: Context): Flow<BottomProgressBehavior> =
        context.settingsDataStore.data.map { preferences ->
            BottomProgressBehavior.fromValue(
                preferences[KEY_BOTTOM_PROGRESS_BEHAVIOR]
                    ?: BottomProgressBehavior.ALWAYS_HIDE.value
            )
        }


    fun getProgressPeakDanmakuEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_PROGRESS_PEAK_DANMAKU_ENABLED] ?: false
        }


    suspend fun setProgressPeakDanmakuEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_PROGRESS_PEAK_DANMAKU_ENABLED] = enabled
        }
    }


    fun getPlaybackCompletionBehaviorSync(context: Context): PlaybackCompletionBehavior {
        val prefs = context.getSharedPreferences("auto_play_cache", Context.MODE_PRIVATE)
        val sharedPreferencesValue = if (prefs.contains(CACHE_KEY_PLAYBACK_COMPLETION_BEHAVIOR)) {
            prefs.getInt(
                CACHE_KEY_PLAYBACK_COMPLETION_BEHAVIOR,
                PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC.value
            )
        } else {
            null
        }
        return resolvePlaybackCompletionBehaviorSyncSource(
            memoryCacheValue = playbackCompletionBehaviorMemoryCache,
            sharedPreferencesValue = sharedPreferencesValue,
        )
    }
}
