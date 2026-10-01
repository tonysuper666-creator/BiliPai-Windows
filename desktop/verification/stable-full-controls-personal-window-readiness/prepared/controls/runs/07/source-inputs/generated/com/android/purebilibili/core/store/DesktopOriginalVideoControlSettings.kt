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
object DesktopOriginalVideoControlSettings {
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

    private val KEY_PROGRESS_PEAK_DANMAKU_ENABLED =
        booleanPreferencesKey("progress_peak_danmaku_enabled")

    private const val LONG_PRESS_SPEED_LOCK_CACHE_PREFS = "long_press_speed_lock_cache"

    private const val CACHE_KEY_LONG_PRESS_SPEED_LOCK_ENABLED = "long_press_speed_lock_enabled"

    private const val CACHE_KEY_LONG_PRESS_SPEED_LOCK_HINT_SHOWN = "long_press_speed_lock_hint_shown"

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

    fun getProgressPeakDanmakuEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_PROGRESS_PEAK_DANMAKU_ENABLED] ?: false
        }

    suspend fun setProgressPeakDanmakuEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_PROGRESS_PEAK_DANMAKU_ENABLED] = enabled
        }
    }

}
