// Original stable selected settings; single existing Root global settings authority.
// Target: 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589
package com.bilipai.desktop.ui
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object DesktopOriginalNavigationHostSettings {
    private val KEY_CLICK_TO_PLAY = booleanPreferencesKey("click_to_play")
    private val KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED = booleanPreferencesKey("video_transition_realtime_blur_enabled")
    private val KEY_RELATED_VIDEO_TRANSITION_ENABLED = booleanPreferencesKey("related_video_transition_enabled")
    private val keyFullScreenSwipeBackEnabled = booleanPreferencesKey("full_screen_swipe_back_enabled")

    fun getClickToPlay(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_CLICK_TO_PLAY] ?: true }

    suspend fun setClickToPlay(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_CLICK_TO_PLAY] = value }
        // Sync to SharedPreferences for synchronous access
        context.getSharedPreferences("auto_play_cache", Context.MODE_PRIVATE)
            .edit().putBoolean("click_to_play_enabled", value).apply()
    }

    fun getClickToPlaySync(context: Context): Boolean {
        return context.getSharedPreferences("auto_play_cache", Context.MODE_PRIVATE)
            .getBoolean("click_to_play_enabled", true)
    }

    fun getVideoTransitionRealtimeBlurEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED] ?: false }

    suspend fun setVideoTransitionRealtimeBlurEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED] = value
        }
    }

    fun getRelatedVideoTransitionEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_RELATED_VIDEO_TRANSITION_ENABLED] ?: true
        }

    suspend fun setRelatedVideoTransitionEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_RELATED_VIDEO_TRANSITION_ENABLED] = value
        }
    }

    fun getFullScreenSwipeBackEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[keyFullScreenSwipeBackEnabled] ?: false }

    suspend fun setFullScreenSwipeBackEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyFullScreenSwipeBackEnabled] = enabled
        }
    }
}
